package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.BookDao
import com.example.data.SentenceEntity
import com.example.data.SettingsRepository
import com.example.data.UserSettings
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.domain.SpeedControlMode
import com.example.domain.WordTimestampRange
import com.example.models.ModelStorageManager
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

data class PlaybackUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val sentenceIndex: Int = 0,
    val totalSentencesInChapter: Int = 0,
    val currentSentenceText: String = "",
    val activeWordStartChar: Int = -1,
    val activeWordEndChar: Int = -1,
    val playbackSpeed: Float = 1.0f,
    val repeatCurrentSentence: Boolean = false,
    val bufferedAheadCount: Int = 0,
    val activeModelName: String = "",
    val activeLanguageCode: String = "fr",
    val errorMessage: String? = null
)

internal interface AudioTrackHandle {
    val isInitialized: Boolean
    val playState: Int
    val playbackHeadPosition: Int
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun setSpeed(speed: Float)
    fun writeFloat(data: FloatArray, offset: Int, count: Int): Int
    fun writePcm16(data: ShortArray, offset: Int, count: Int): Int
}

internal fun interface AudioTrackHandleFactory {
    fun create(sampleRate: Int): Pair<AudioTrackHandle, Boolean>?
}

private class RealAndroidAudioTrackHandle(
    private val track: AudioTrack
) : AudioTrackHandle {
    override val isInitialized: Boolean
        get() = track.state == AudioTrack.STATE_INITIALIZED

    override val playState: Int
        get() = runCatching { track.playState }.getOrDefault(AudioTrack.PLAYSTATE_STOPPED)

    override val playbackHeadPosition: Int
        get() = runCatching { track.playbackHeadPosition }.getOrDefault(-1)

    override fun play() {
        runCatching { track.play() }
    }

    override fun pause() {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        }
    }

    override fun flush() {
        runCatching { track.flush() }
    }

    override fun stop() {
        runCatching { track.stop() }
    }

    override fun release() {
        runCatching { track.release() }
    }

    override fun setSpeed(speed: Float) {
        runCatching {
            val params = PlaybackParams().setSpeed(speed.coerceIn(0.5f, 2.0f)).setPitch(1.0f)
            track.playbackParams = params
        }
    }

    override fun writeFloat(data: FloatArray, offset: Int, count: Int): Int {
        return runCatching {
            track.write(data, offset, count, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
    }

    override fun writePcm16(data: ShortArray, offset: Int, count: Int): Int {
        return runCatching {
            track.write(data, offset, count, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
    }
}

private object DefaultAudioTrackHandleFactory : AudioTrackHandleFactory {
    override fun create(sampleRate: Int): Pair<AudioTrackHandle, Boolean>? {
        var usePcm16 = false
        var track = createAndroidAudioTrackOrNull(sampleRate, AudioFormat.ENCODING_PCM_FLOAT)
        if (track == null) {
            track = createAndroidAudioTrackOrNull(sampleRate, AudioFormat.ENCODING_PCM_16BIT)
            usePcm16 = true
        }
        val validTrack = track ?: return null
        return RealAndroidAudioTrackHandle(validTrack) to usePcm16
    }

    private fun createAndroidAudioTrackOrNull(sampleRate: Int, encoding: Int): AudioTrack? {
        return runCatching {
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val rawMin = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                encoding
            )
            if (rawMin <= 0) return null
            val minBuf = rawMin.coerceAtLeast((sampleRate / 5) * bytesPerSample)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else {
                track
            }
        }.getOrNull()
    }
}

class AudioPlaybackController internal constructor(
    private val context: Context,
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
    private val storageManager: ModelStorageManager,
    private val ttsEngineManager: TtsEngineManager,
    private val trackFactory: AudioTrackHandleFactory
) {
    constructor(
        context: Context,
        bookDao: BookDao,
        settingsRepository: SettingsRepository,
        storageManager: ModelStorageManager,
        ttsEngineManager: TtsEngineManager
    ) : this(
        context = context,
        bookDao = bookDao,
        settingsRepository = settingsRepository,
        storageManager = storageManager,
        ttsEngineManager = ttsEngineManager,
        trackFactory = DefaultAudioTrackHandleFactory
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionLock = Any()
    private val trackLock = Any()
    private val sessionSeq = AtomicLong(0L)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    @Volatile
    private var activeSessionId: Long = 0L

    private var prepareJob: Job? = null
    private var playbackJob: Job? = null

    private var currentTrackHandle: AudioTrackHandle? = null
    private var activeTrackSessionId: Long = 0L

    private var focusRequest: AudioFocusRequest? = null
    private var focusOwnerSessionId: Long = 0L

    @Volatile
    private var isPausedFlag: Boolean = false

    @Volatile
    private var isStoppingFlag: Boolean = false

    @Volatile
    private var queueSentences: List<SentenceEntity> = emptyList()

    @Volatile
    private var currentQueuePosition: Int = 0

    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var repeatSingle: Boolean = false

    @Volatile
    private var currentLanguageCode: String = "fr"

    internal fun currentSessionIdForTest(): Long = activeSessionId

    private fun isSessionValid(sessionId: Long): Boolean =
        sessionId != 0L && sessionId == activeSessionId && !isStoppingFlag

    private inline fun updateStateIfSessionValid(
        sessionId: Long,
        transform: (PlaybackUiState) -> PlaybackUiState
    ) {
        synchronized(sessionLock) {
            if (isSessionValid(sessionId)) {
                _state.value = transform(_state.value)
            }
        }
    }

    /**
     * Atomically invalidates any previous playback/preparation session, cancels its coroutines
     * and active synthesis, releases any previously owned AudioTrack, and mints a fresh sessionId.
     */
    private fun claimNewSession(stoppingOnly: Boolean): Long {
        val prevPrepare: Job?
        val prevPlayback: Job?
        val newId: Long
        synchronized(sessionLock) {
            newId = sessionSeq.incrementAndGet()
            activeSessionId = newId
            isPausedFlag = false
            isStoppingFlag = stoppingOnly
            prevPrepare = prepareJob
            prevPlayback = playbackJob
            prepareJob = null
            playbackJob = null
        }
        ttsEngineManager.cancelActiveSynthesis()
        prevPrepare?.cancel()
        prevPlayback?.cancel()
        releaseAudioTrackUnconditionally()
        abandonAudioFocusUnconditionally()
        return newId
    }

    fun playChapterFrom(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        startSentenceIndex: Int = 0,
        languageCode: String? = null
    ) {
        val sessionId = claimNewSession(stoppingOnly = false)
        val job = scope.launch {
            try {
                val sentences = bookDao.getChapterSentences(bookId, chapterIndex)
                if (!isSessionValid(sessionId)) return@launch
                if (sentences.isEmpty()) {
                    updateStateIfSessionValid(sessionId) {
                        it.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = "لا توجد جمل في هذا الفصل."
                        )
                    }
                    return@launch
                }
                val resolvedLang = SpeechTextProcessor.normalizeLangCode(
                    languageCode ?: bookDao.getBook(bookId)?.sourceLanguage
                )
                if (!isSessionValid(sessionId)) return@launch
                val safeIndex = startSentenceIndex.coerceIn(0, sentences.lastIndex)
                settingsRepository.setActiveBookId(bookId)
                if (!isSessionValid(sessionId)) return@launch
                startQueuePlaybackForSession(
                    sessionId = sessionId,
                    bookId = bookId,
                    bookTitle = bookTitle,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    sentences = sentences,
                    startIndex = safeIndex,
                    languageCode = resolvedLang
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateStateIfSessionValid(sessionId) {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = t.message ?: "تعذر بدء تشغيل هذا الفصل."
                    )
                }
            }
        }
        synchronized(sessionLock) {
            if (isSessionValid(sessionId)) {
                prepareJob = job
            } else {
                job.cancel()
            }
        }
    }

    /**
     * Synthesizes and plays a single word or phrase in French or English.
     */
    fun pronounceStandalone(
        frenchText: String,
        title: String = "نطق المفردة",
        languageCode: String = "fr"
    ) {
        val cleaned = frenchText.trim()
        if (cleaned.isEmpty()) return
        val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
        val singleSentence = SentenceEntity(
            id = -1L,
            bookId = "_standalone_",
            chapterIndex = 0,
            sentenceIndex = 0,
            paragraphIndex = 0,
            originalText = cleaned,
            spokenText = SpeechTextProcessor.prepareForSpeech(cleaned, normLang)
        )
        val sessionId = claimNewSession(stoppingOnly = false)
        startQueuePlaybackForSession(
            sessionId = sessionId,
            bookId = "_standalone_",
            bookTitle = title,
            chapterIndex = 0,
            chapterTitle = cleaned,
            sentences = listOf(singleSentence),
            startIndex = 0,
            languageCode = normLang
        )
    }

    private fun startQueuePlaybackForSession(
        sessionId: Long,
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        sentences: List<SentenceEntity>,
        startIndex: Int,
        languageCode: String
    ) {
        if (!isSessionValid(sessionId)) return
        if (sentences.isEmpty()) {
            updateStateIfSessionValid(sessionId) {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "لا توجد جمل صالحة للتشغيل."
                )
            }
            return
        }
        val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
        val safeStart = startIndex.coerceIn(0, sentences.lastIndex)
        ensureServiceStarted()

        synchronized(sessionLock) {
            if (!isSessionValid(sessionId)) return
            queueSentences = sentences
            currentQueuePosition = safeStart
            currentLanguageCode = normLang
            isPausedFlag = false
        }

        val launchedJob = scope.launch {
            var prefetchedDeferred: Deferred<Result<CachedAudioPayload>>? = null
            var prefetchedIndex = -1
            try {
                val settings = settingsRepository.settingsFlow.first()
                if (!isSessionValid(sessionId)) return@launch
                currentSpeed = settings.playbackSpeed

                val modelResolution = ttsEngineManager.resolveModelForLanguage(
                    preferredModelId = settings.activeModelId,
                    languageCode = normLang
                )
                if (!isSessionValid(sessionId)) return@launch
                if (modelResolution.isFailure) {
                    updateStateIfSessionValid(sessionId) {
                        PlaybackUiState(
                            status = PlaybackStatus.ERROR,
                            bookId = bookId,
                            bookTitle = bookTitle,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            sentenceIndex = sentences[safeStart].sentenceIndex,
                            totalSentencesInChapter = sentences.size,
                            currentSentenceText = sentences[safeStart].originalText,
                            playbackSpeed = currentSpeed,
                            repeatCurrentSentence = repeatSingle,
                            activeLanguageCode = normLang,
                            errorMessage = modelResolution.exceptionOrNull()?.message
                                ?: "لا يوجد نموذج متاح للغة ($normLang)."
                        )
                    }
                    return@launch
                }
                val resolvedSpec = modelResolution.getOrThrow()

                updateStateIfSessionValid(sessionId) {
                    PlaybackUiState(
                        status = PlaybackStatus.BUFFERING,
                        bookId = bookId,
                        bookTitle = bookTitle,
                        chapterIndex = chapterIndex,
                        chapterTitle = chapterTitle,
                        sentenceIndex = sentences[safeStart].sentenceIndex,
                        totalSentencesInChapter = sentences.size,
                        currentSentenceText = sentences[safeStart].originalText,
                        playbackSpeed = currentSpeed,
                        repeatCurrentSentence = repeatSingle,
                        activeModelName = resolvedSpec.displayName,
                        activeLanguageCode = normLang
                    )
                }

                if (!requestAudioFocusForSession(sessionId)) {
                    updateStateIfSessionValid(sessionId) {
                        it.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = "تعذر الحصول على تركيز الصوت من النظام."
                        )
                    }
                    return@launch
                }

                var idx = safeStart
                while (isActive && isSessionValid(sessionId) && idx < queueSentences.size) {
                    val activeQueue = queueSentences
                    if (idx >= activeQueue.size) break
                    val currentSentence = activeQueue[idx]
                    currentQueuePosition = idx

                    if (bookId != "_standalone_") {
                        bookDao.updateReadingPosition(bookId, chapterIndex, currentSentence.sentenceIndex)
                    }
                    if (!isSessionValid(sessionId)) break

                    updateStateIfSessionValid(sessionId) {
                        it.copy(
                            status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.BUFFERING,
                            sentenceIndex = currentSentence.sentenceIndex,
                            currentSentenceText = currentSentence.originalText,
                            activeWordStartChar = -1,
                            activeWordEndChar = -1,
                            errorMessage = null
                        )
                    }

                    while (isActive && isSessionValid(sessionId) && isPausedFlag) {
                        updateStateIfSessionValid(sessionId) { it.copy(status = PlaybackStatus.PAUSED) }
                        delay(80L)
                    }
                    if (!isActive || !isSessionValid(sessionId)) break

                    val nextIdx = if (repeatSingle) idx else idx + 1
                    val spokenText = SpeechTextProcessor.prepareForSpeech(currentSentence.originalText, normLang)
                    val voiceId = resolvedSpec.defaultVoiceFor(normLang)
                    val synthSpeed = if (settings.speedControlMode == SpeedControlMode.MODEL_SYNTHESIS_RATE) {
                        currentSpeed
                    } else {
                        1.0f
                    }
                    val cacheKey = AudioChunkCache.buildCacheKey(
                        modelId = resolvedSpec.id,
                        languageCode = normLang,
                        voiceId = voiceId,
                        speed = synthSpeed,
                        spokenText = spokenText
                    )
                    val cacheFile = AudioChunkCache.cacheFileFor(storageManager.audioCacheRoot, cacheKey)

                    val cachedHit: CachedAudioPayload? = if (prefetchedIndex == idx && prefetchedDeferred != null) {
                        val res = prefetchedDeferred.await()
                        prefetchedDeferred = null
                        prefetchedIndex = -1
                        res.getOrNull()
                    } else {
                        prefetchedDeferred?.cancel()
                        prefetchedDeferred = null
                        prefetchedIndex = -1
                        withContext(Dispatchers.IO) { AudioChunkCache.readValidated(cacheFile) }
                    }

                    if (!isSessionValid(sessionId)) break

                    val scheduleLookahead: () -> Unit = {
                        if (!repeatSingle && nextIdx < queueSentences.size && isActive && isSessionValid(sessionId)) {
                            val nextSentence = queueSentences[nextIdx]
                            prefetchedIndex = nextIdx
                            prefetchedDeferred = async {
                                if (!isSessionValid(sessionId)) {
                                    Result.failure(CancellationException("Session expired"))
                                } else {
                                    synthesizeAndCacheSentence(
                                        sessionId = sessionId,
                                        sentence = nextSentence,
                                        spec = resolvedSpec,
                                        languageCode = normLang,
                                        settings = settings,
                                        synthSpeed = synthSpeed
                                    )
                                }
                            }
                            updateStateIfSessionValid(sessionId) { it.copy(bufferedAheadCount = 1) }
                        } else {
                            updateStateIfSessionValid(sessionId) { it.copy(bufferedAheadCount = 0) }
                        }
                    }

                    if (cachedHit != null) {
                        scheduleLookahead()
                        playCachedPayloadWithTracking(
                            sessionId = sessionId,
                            sentence = currentSentence,
                            payload = cachedHit,
                            speedMode = settings.speedControlMode
                        )
                    } else {
                        playSentenceLiveStreaming(
                            sessionId = sessionId,
                            sentence = currentSentence,
                            spokenText = spokenText,
                            spec = resolvedSpec,
                            languageCode = normLang,
                            voiceId = voiceId,
                            synthSpeed = synthSpeed,
                            acceleratorMode = settings.acceleratorMode,
                            speedMode = settings.speedControlMode,
                            cacheFile = cacheFile,
                            onStreamFinishedBeforeDrain = scheduleLookahead
                        )
                    }

                    if (!isActive || !isSessionValid(sessionId)) break
                    if (!repeatSingle) {
                        idx++
                    }
                }

                if (isActive && isSessionValid(sessionId) && _state.value.status != PlaybackStatus.ERROR) {
                    updateStateIfSessionValid(sessionId) {
                        it.copy(
                            status = PlaybackStatus.IDLE,
                            activeWordStartChar = -1,
                            activeWordEndChar = -1,
                            bufferedAheadCount = 0
                        )
                    }
                }
            } catch (_: CancellationException) {
                // Clean cancellation when pre-empted by a newer session or stop()
            } catch (oom: OutOfMemoryError) {
                updateStateIfSessionValid(sessionId) {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "نفدت الذاكرة المتاحة أثناء التشغيل الصوتي. أغلق التطبيقات الخلفية أو بدّل إلى Piper."
                    )
                }
            } catch (t: Throwable) {
                updateStateIfSessionValid(sessionId) {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = t.message ?: "خطأ غير متوقع أثناء التشغيل"
                    )
                }
            } finally {
                prefetchedDeferred?.cancel()
                releaseAudioTrackIfOwner(sessionId)
                abandonAudioFocusIfOwner(sessionId)
            }
        }

        synchronized(sessionLock) {
            if (isSessionValid(sessionId)) {
                playbackJob = launchedJob
            } else {
                launchedJob.cancel()
            }
        }
    }

    private suspend fun synthesizeAndCacheSentence(
        sessionId: Long,
        sentence: SentenceEntity,
        spec: ModelSpec,
        languageCode: String,
        settings: UserSettings,
        synthSpeed: Float
    ): Result<CachedAudioPayload> = withContext(Dispatchers.IO) {
        if (!isSessionValid(sessionId)) {
            return@withContext Result.failure(CancellationException("Session expired"))
        }
        val spokenText = SpeechTextProcessor.prepareForSpeech(sentence.originalText, languageCode)
        val voiceId = spec.defaultVoiceFor(languageCode)
        val cacheKey = AudioChunkCache.buildCacheKey(spec.id, languageCode, voiceId, synthSpeed, spokenText)
        val cacheFile = AudioChunkCache.cacheFileFor(storageManager.audioCacheRoot, cacheKey)

        AudioChunkCache.readValidated(cacheFile)?.let { return@withContext Result.success(it) }

        val res = ttsEngineManager.synthesizeSentence(
            modelId = spec.id,
            acceleratorMode = settings.acceleratorMode,
            spokenText = spokenText,
            voiceId = voiceId,
            speed = synthSpeed,
            languageCode = languageCode
        )
        res.map { synth ->
            val payload = CachedAudioPayload(
                samples = AudioChunkCache.sanitizeSamples(synth.samples),
                sampleRateHz = synth.sampleRateHz.coerceIn(AudioChunkCache.MIN_SAMPLE_RATE, AudioChunkCache.MAX_SAMPLE_RATE),
                wordTimestamps = synth.wordTimestamps
            )
            AudioChunkCache.write(cacheFile, payload)
            AudioChunkCache.pruneIfOversized(storageManager.audioCacheRoot)
            payload
        }
    }

    /**
     * Streams TTS chunks directly into AudioTrack as soon as the first chunk arrives
     * instead of blocking until full-sentence generation completes.
     */
    private suspend fun playSentenceLiveStreaming(
        sessionId: Long,
        sentence: SentenceEntity,
        spokenText: String,
        spec: ModelSpec,
        languageCode: String,
        voiceId: String,
        synthSpeed: Float,
        acceleratorMode: com.example.domain.AcceleratorMode,
        speedMode: SpeedControlMode,
        cacheFile: java.io.File,
        onStreamFinishedBeforeDrain: () -> Unit
    ) = withContext(Dispatchers.IO) {
        var activeTrack: AudioTrackHandle? = null
        var usePcm16 = false
        var activeSampleRate = 24_000
        var totalFramesWritten = 0
        val sampleSegments = mutableListOf<FloatArray>()
        var totalSamplesCollected = 0
        val collectedTimestamps = mutableListOf<WordTimestampRange>()

        try {
            ttsEngineManager.synthesizeSentenceStream(
                modelId = spec.id,
                acceleratorMode = acceleratorMode,
                spokenText = spokenText,
                languageCode = languageCode,
                voiceId = voiceId,
                speed = synthSpeed
            ).collect { chunk ->
                if (!isActive || !isSessionValid(sessionId)) {
                    throw CancellationException("Playback session $sessionId invalidated during stream")
                }

                val cleanSamples = AudioChunkCache.sanitizeSamples(chunk.samples)
                if (cleanSamples.isEmpty() && chunk.wordTimestamps.isEmpty()) return@collect

                val chunkRate = chunk.sampleRateHz.coerceIn(
                    AudioChunkCache.MIN_SAMPLE_RATE,
                    AudioChunkCache.MAX_SAMPLE_RATE
                )

                if (activeTrack == null && cleanSamples.isNotEmpty()) {
                    val opened = openStreamingTrackForSession(sessionId, chunkRate, speedMode)
                    activeTrack = opened.first
                    usePcm16 = opened.second
                    activeSampleRate = chunkRate
                    updateStateIfSessionValid(sessionId) {
                        it.copy(
                            status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                            sentenceIndex = sentence.sentenceIndex,
                            currentSentenceText = sentence.originalText
                        )
                    }
                }

                if (chunk.wordTimestamps.isNotEmpty()) {
                    collectedTimestamps.addAll(chunk.wordTimestamps)
                }

                if (cleanSamples.isNotEmpty() && totalSamplesCollected < AudioChunkCache.MAX_CACHED_SAMPLES) {
                    sampleSegments.add(cleanSamples)
                    totalSamplesCollected += cleanSamples.size
                }

                val track = activeTrack ?: return@collect
                if (cleanSamples.isNotEmpty()) {
                    val written = writeSamplesToTrack(
                        sessionId = sessionId,
                        track = track,
                        samples = cleanSamples,
                        usePcm16 = usePcm16,
                        sampleRate = activeSampleRate,
                        speedMode = speedMode,
                        timestamps = collectedTimestamps
                    )
                    totalFramesWritten += written
                }
            }

            if (!isActive || !isSessionValid(sessionId)) return@withContext

            if (totalSamplesCollected in 1..AudioChunkCache.MAX_CACHED_SAMPLES) {
                val merged = FloatArray(totalSamplesCollected)
                var pos = 0
                for (seg in sampleSegments) {
                    seg.copyInto(merged, pos)
                    pos += seg.size
                }
                AudioChunkCache.write(
                    cacheFile,
                    CachedAudioPayload(merged, activeSampleRate, collectedTimestamps)
                )
                AudioChunkCache.pruneIfOversized(storageManager.audioCacheRoot)
            }

            onStreamFinishedBeforeDrain()

            val track = activeTrack
            if (track != null && totalFramesWritten > 0 && isSessionValid(sessionId)) {
                drainAudioTrack(sessionId, track, activeSampleRate, totalFramesWritten, collectedTimestamps)
            }
        } finally {
            releaseAudioTrackIfOwner(sessionId)
        }
    }

    private suspend fun playCachedPayloadWithTracking(
        sessionId: Long,
        sentence: SentenceEntity,
        payload: CachedAudioPayload,
        speedMode: SpeedControlMode
    ) = withContext(Dispatchers.IO) {
        if (payload.samples.isEmpty() || !isSessionValid(sessionId)) return@withContext
        val sampleRate = payload.sampleRateHz.coerceIn(
            AudioChunkCache.MIN_SAMPLE_RATE,
            AudioChunkCache.MAX_SAMPLE_RATE
        )
        val (track, usePcm16) = openStreamingTrackForSession(sessionId, sampleRate, speedMode)
        try {
            updateStateIfSessionValid(sessionId) {
                it.copy(
                    status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                    sentenceIndex = sentence.sentenceIndex,
                    currentSentenceText = sentence.originalText
                )
            }
            val written = writeSamplesToTrack(
                sessionId = sessionId,
                track = track,
                samples = payload.samples,
                usePcm16 = usePcm16,
                sampleRate = sampleRate,
                speedMode = speedMode,
                timestamps = payload.wordTimestamps
            )
            if (isActive && isSessionValid(sessionId) && written > 0) {
                drainAudioTrack(sessionId, track, sampleRate, written, payload.wordTimestamps)
            }
        } finally {
            releaseAudioTrackIfOwner(sessionId)
        }
    }

    private fun openStreamingTrackForSession(
        sessionId: Long,
        sampleRate: Int,
        speedMode: SpeedControlMode
    ): Pair<AudioTrackHandle, Boolean> {
        val (createdTrack, usePcm16) = trackFactory.create(sampleRate)
            ?: error("تعذر تهيئة مخرج الصوت AudioTrack بمعدل $sampleRate Hz.")

        synchronized(trackLock) {
            if (!isSessionValid(sessionId)) {
                createdTrack.release()
                throw CancellationException("Session $sessionId invalidated before track start")
            }
            // Release any leftover track from an obsolete session before binding the new owner
            currentTrackHandle?.let { previous ->
                if (previous !== createdTrack) {
                    runCatching {
                        if (previous.playState == AudioTrack.PLAYSTATE_PLAYING) previous.pause()
                        previous.flush()
                        previous.stop()
                    }
                    previous.release()
                }
            }
            currentTrackHandle = createdTrack
            activeTrackSessionId = sessionId
            applyPlaybackSpeedToTrack(createdTrack, speedMode)
            createdTrack.play()
        }
        return createdTrack to usePcm16
    }

    private fun writeSamplesToTrack(
        sessionId: Long,
        track: AudioTrackHandle,
        samples: FloatArray,
        usePcm16: Boolean,
        sampleRate: Int,
        speedMode: SpeedControlMode,
        timestamps: List<WordTimestampRange>
    ): Int {
        val pcm16Samples = if (usePcm16) {
            ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            null
        }

        var offset = 0
        val stepFrames = 512
        var zeroWriteCount = 0

        while (offset < samples.size && isSessionValid(sessionId)) {
            if (isPausedFlag) {
                synchronized(trackLock) {
                    if (isSessionValid(sessionId) && activeTrackSessionId == sessionId && currentTrackHandle === track) {
                        track.pause()
                    }
                }
                updateStateIfSessionValid(sessionId) { it.copy(status = PlaybackStatus.PAUSED) }
                while (isPausedFlag && isSessionValid(sessionId)) {
                    Thread.sleep(45L)
                }
                if (!isSessionValid(sessionId)) break
                synchronized(trackLock) {
                    if (isSessionValid(sessionId) && activeTrackSessionId == sessionId && currentTrackHandle === track) {
                        applyPlaybackSpeedToTrack(track, speedMode)
                        track.play()
                    }
                }
                updateStateIfSessionValid(sessionId) { it.copy(status = PlaybackStatus.PLAYING) }
            }

            val count = minOf(stepFrames, samples.size - offset)
            val written = synchronized(trackLock) {
                if (!isSessionValid(sessionId) ||
                    activeTrackSessionId != sessionId ||
                    currentTrackHandle !== track ||
                    !track.isInitialized
                ) {
                    -1
                } else {
                    if (pcm16Samples != null) {
                        track.writePcm16(pcm16Samples, offset, count)
                    } else {
                        track.writeFloat(samples, offset, count)
                    }
                }
            }

            if (written < 0) break
            if (written == 0) {
                zeroWriteCount++
                if (zeroWriteCount > 50) break
                Thread.sleep(10L)
                continue
            }
            zeroWriteCount = 0
            offset += written
            updateWordHighlightIfAvailable(sessionId, track, sampleRate, timestamps)
        }
        return offset
    }

    private fun drainAudioTrack(
        sessionId: Long,
        track: AudioTrackHandle,
        sampleRate: Int,
        totalFrames: Int,
        timestamps: List<WordTimestampRange>
    ) {
        val estimatedDurationMs = ((totalFrames.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(300L)
        val drainDeadlineMs = System.currentTimeMillis() + estimatedDurationMs + 1500L
        while (isSessionValid(sessionId) && !isPausedFlag && System.currentTimeMillis() < drainDeadlineMs) {
            val (head, isPlaying) = synchronized(trackLock) {
                if (!isSessionValid(sessionId) ||
                    activeTrackSessionId != sessionId ||
                    currentTrackHandle !== track ||
                    !track.isInitialized
                ) {
                    totalFrames to false
                } else {
                    track.playbackHeadPosition to (track.playState == AudioTrack.PLAYSTATE_PLAYING)
                }
            }
            updateWordHighlightIfAvailable(sessionId, track, sampleRate, timestamps)
            if (head >= totalFrames - 256 || !isPlaying) break
            Thread.sleep(25L)
        }
    }

    private fun updateWordHighlightIfAvailable(
        sessionId: Long,
        track: AudioTrackHandle,
        sampleRate: Int,
        timestamps: List<WordTimestampRange>
    ) {
        if (timestamps.isEmpty() || !isSessionValid(sessionId)) return
        val head = synchronized(trackLock) {
            if (!isSessionValid(sessionId) ||
                activeTrackSessionId != sessionId ||
                currentTrackHandle !== track ||
                !track.isInitialized
            ) {
                -1
            } else {
                track.playbackHeadPosition
            }
        }
        if (head < 0) return
        val sec = head.toFloat() / sampleRate.toFloat()
        val match = timestamps.firstOrNull { sec >= it.startSec && sec <= it.endSec }
        if (match != null) {
            updateStateIfSessionValid(sessionId) {
                it.copy(
                    activeWordStartChar = match.startChar,
                    activeWordEndChar = match.endChar
                )
            }
        }
    }

    private fun applyPlaybackSpeedToTrack(track: AudioTrackHandle, speedMode: SpeedControlMode) {
        val targetRate = if (speedMode == SpeedControlMode.AUDIO_TRACK_TIME_STRETCH) currentSpeed else 1.0f
        track.setSpeed(targetRate)
    }

    fun pause() {
        val pausedSessionId = synchronized(sessionLock) {
            if (activeSessionId == 0L || isStoppingFlag) return
            isPausedFlag = true
            activeSessionId
        }
        synchronized(trackLock) {
            if (activeTrackSessionId == pausedSessionId) {
                currentTrackHandle?.pause()
            }
        }
        updateStateIfSessionValid(pausedSessionId) { it.copy(status = PlaybackStatus.PAUSED) }
    }

    fun resume() {
        val st = _state.value
        val resumingSession = synchronized(sessionLock) {
            if (st.status == PlaybackStatus.PAUSED && activeSessionId != 0L && !isStoppingFlag) {
                isPausedFlag = false
                activeSessionId
            } else {
                0L
            }
        }
        if (resumingSession != 0L) {
            synchronized(trackLock) {
                if (activeTrackSessionId == resumingSession && isSessionValid(resumingSession)) {
                    currentTrackHandle?.play()
                }
            }
            updateStateIfSessionValid(resumingSession) { it.copy(status = PlaybackStatus.PLAYING) }
        } else if ((st.status == PlaybackStatus.IDLE || st.status == PlaybackStatus.ERROR) &&
            st.bookId != null &&
            st.bookId != "_standalone_"
        ) {
            playChapterFrom(
                bookId = st.bookId,
                bookTitle = st.bookTitle,
                chapterIndex = st.chapterIndex,
                chapterTitle = st.chapterTitle,
                startSentenceIndex = st.sentenceIndex,
                languageCode = st.activeLanguageCode
            )
        }
    }

    fun togglePlayPause() {
        when (_state.value.status) {
            PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING -> pause()
            PlaybackStatus.PAUSED, PlaybackStatus.IDLE, PlaybackStatus.ERROR -> resume()
        }
    }

    fun stop() {
        stopPlaybackInternal(resetPositionState = true)
    }

    fun nextSentence() {
        val st = _state.value
        val bId = st.bookId ?: return
        if (bId == "_standalone_" || queueSentences.isEmpty()) return
        val nextPos = (currentQueuePosition + 1).coerceAtMost(queueSentences.lastIndex)
        val sessionId = claimNewSession(stoppingOnly = false)
        startQueuePlaybackForSession(
            sessionId = sessionId,
            bookId = bId,
            bookTitle = st.bookTitle,
            chapterIndex = st.chapterIndex,
            chapterTitle = st.chapterTitle,
            sentences = queueSentences,
            startIndex = nextPos,
            languageCode = currentLanguageCode
        )
    }

    fun previousSentence() {
        val st = _state.value
        val bId = st.bookId ?: return
        if (bId == "_standalone_" || queueSentences.isEmpty()) return
        val prevPos = (currentQueuePosition - 1).coerceAtLeast(0)
        val sessionId = claimNewSession(stoppingOnly = false)
        startQueuePlaybackForSession(
            sessionId = sessionId,
            bookId = bId,
            bookTitle = st.bookTitle,
            chapterIndex = st.chapterIndex,
            chapterTitle = st.chapterTitle,
            sentences = queueSentences,
            startIndex = prevPos,
            languageCode = currentLanguageCode
        )
    }

    fun replayCurrentSentence() {
        val st = _state.value
        val bId = st.bookId ?: return
        if (queueSentences.isEmpty()) return
        val sessionId = claimNewSession(stoppingOnly = false)
        startQueuePlaybackForSession(
            sessionId = sessionId,
            bookId = bId,
            bookTitle = st.bookTitle,
            chapterIndex = st.chapterIndex,
            chapterTitle = st.chapterTitle,
            sentences = queueSentences,
            startIndex = currentQueuePosition.coerceIn(0, queueSentences.lastIndex),
            languageCode = currentLanguageCode
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        scope.launch { settingsRepository.setPlaybackSpeed(speed) }
        _state.value = _state.value.copy(playbackSpeed = speed)
        val trackSnapshot = synchronized(trackLock) { currentTrackHandle to activeTrackSessionId }
        val track = trackSnapshot.first ?: return
        val ownerSession = trackSnapshot.second
        scope.launch {
            val mode = settingsRepository.settingsFlow.first().speedControlMode
            if (mode == SpeedControlMode.AUDIO_TRACK_TIME_STRETCH) {
                synchronized(trackLock) {
                    if (isSessionValid(ownerSession) &&
                        activeTrackSessionId == ownerSession &&
                        currentTrackHandle === track
                    ) {
                        applyPlaybackSpeedToTrack(track, mode)
                    }
                }
            }
        }
    }

    fun toggleRepeatCurrentSentence() {
        repeatSingle = !repeatSingle
        _state.value = _state.value.copy(repeatCurrentSentence = repeatSingle)
    }

    private fun stopPlaybackInternal(resetPositionState: Boolean) {
        claimNewSession(stoppingOnly = true)
        if (resetPositionState) {
            synchronized(sessionLock) {
                _state.value = _state.value.copy(
                    status = PlaybackStatus.IDLE,
                    activeWordStartChar = -1,
                    activeWordEndChar = -1,
                    bufferedAheadCount = 0
                )
            }
        }
    }

    private fun releaseAudioTrackIfOwner(sessionId: Long) {
        val track = synchronized(trackLock) {
            if (activeTrackSessionId != sessionId) return
            val t = currentTrackHandle
            currentTrackHandle = null
            activeTrackSessionId = 0L
            t
        } ?: return
        tearDownTrack(track)
    }

    private fun releaseAudioTrackUnconditionally() {
        val track = synchronized(trackLock) {
            val t = currentTrackHandle
            currentTrackHandle = null
            activeTrackSessionId = 0L
            t
        } ?: return
        tearDownTrack(track)
    }

    private fun tearDownTrack(track: AudioTrackHandle) {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            track.flush()
            track.stop()
        }
        track.release()
    }

    private fun requestAudioFocusForSession(sessionId: Long): Boolean {
        if (!isSessionValid(sessionId)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        synchronized(sessionLock) {
                            if (focusOwnerSessionId == sessionId && isSessionValid(sessionId)) {
                                pause()
                            }
                        }
                    }
                }
                .build()
            val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            synchronized(sessionLock) {
                if (granted && isSessionValid(sessionId)) {
                    focusRequest = req
                    focusOwnerSessionId = sessionId
                } else if (granted) {
                    audioManager.abandonAudioFocusRequest(req)
                    return false
                }
            }
            granted
        } else {
            @Suppress("DEPRECATION")
            val granted = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            synchronized(sessionLock) {
                if (granted && isSessionValid(sessionId)) {
                    focusOwnerSessionId = sessionId
                } else if (granted) {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                    return false
                }
            }
            granted
        }
    }

    private fun abandonAudioFocusIfOwner(sessionId: Long) {
        val reqToAbandon: AudioFocusRequest?
        synchronized(sessionLock) {
            if (focusOwnerSessionId != sessionId) return
            reqToAbandon = focusRequest
            focusRequest = null
            focusOwnerSessionId = 0L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reqToAbandon?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private fun abandonAudioFocusUnconditionally() {
        val reqToAbandon: AudioFocusRequest?
        synchronized(sessionLock) {
            reqToAbandon = focusRequest
            focusRequest = null
            focusOwnerSessionId = 0L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reqToAbandon?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private fun ensureServiceStarted() {
        runCatching {
            val intent = Intent(context, PlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
