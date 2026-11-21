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

class AudioPlaybackController(
    private val context: Context,
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
    private val storageManager: ModelStorageManager,
    private val ttsEngineManager: TtsEngineManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val trackLock = Any()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var playbackJob: Job? = null
    private var currentAudioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

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

    fun playChapterFrom(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        startSentenceIndex: Int = 0,
        languageCode: String? = null
    ) {
        scope.launch {
            try {
                val sentences = bookDao.getChapterSentences(bookId, chapterIndex)
                if (sentences.isEmpty()) {
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "لا توجد جمل في هذا الفصل."
                    )
                    return@launch
                }
                val resolvedLang = SpeechTextProcessor.normalizeLangCode(
                    languageCode ?: bookDao.getBook(bookId)?.sourceLanguage
                )
                val safeIndex = startSentenceIndex.coerceIn(0, sentences.lastIndex)
                settingsRepository.setActiveBookId(bookId)
                startQueuePlayback(
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
                _state.value = _state.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = t.message ?: "تعذر بدء تشغيل هذا الفصل."
                )
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
        startQueuePlayback(
            bookId = "_standalone_",
            bookTitle = title,
            chapterIndex = 0,
            chapterTitle = cleaned,
            sentences = listOf(singleSentence),
            startIndex = 0,
            languageCode = normLang
        )
    }

    private fun startQueuePlayback(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        sentences: List<SentenceEntity>,
        startIndex: Int,
        languageCode: String
    ) {
        stopPlaybackInternal(resetPositionState = false)
        if (sentences.isEmpty()) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "لا توجد جمل صالحة للتشغيل."
            )
            return
        }
        val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
        val safeStart = startIndex.coerceIn(0, sentences.lastIndex)
        ensureServiceStarted()
        queueSentences = sentences
        currentQueuePosition = safeStart
        currentLanguageCode = normLang
        isPausedFlag = false
        isStoppingFlag = false

        playbackJob = scope.launch {
            var prefetchedDeferred: Deferred<Result<CachedAudioPayload>>? = null
            var prefetchedIndex = -1
            try {
                val settings = settingsRepository.settingsFlow.first()
                currentSpeed = settings.playbackSpeed

                val modelResolution = ttsEngineManager.resolveModelForLanguage(
                    preferredModelId = settings.activeModelId,
                    languageCode = normLang
                )
                if (modelResolution.isFailure) {
                    _state.value = PlaybackUiState(
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
                    return@launch
                }
                val resolvedSpec = modelResolution.getOrThrow()

                _state.value = PlaybackUiState(
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

                if (!requestAudioFocus()) {
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "تعذر الحصول على تركيز الصوت من النظام."
                    )
                    return@launch
                }

                var idx = safeStart
                while (isActive && !isStoppingFlag && idx < queueSentences.size) {
                    val currentSentence = queueSentences[idx]
                    currentQueuePosition = idx

                    if (bookId != "_standalone_") {
                        bookDao.updateReadingPosition(bookId, chapterIndex, currentSentence.sentenceIndex)
                    }

                    _state.value = _state.value.copy(
                        status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.BUFFERING,
                        sentenceIndex = currentSentence.sentenceIndex,
                        currentSentenceText = currentSentence.originalText,
                        activeWordStartChar = -1,
                        activeWordEndChar = -1,
                        errorMessage = null
                    )

                    while (isActive && !isStoppingFlag && isPausedFlag) {
                        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
                        delay(80L)
                    }
                    if (!isActive || isStoppingFlag) break

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

                    val scheduleLookahead: () -> Unit = {
                        if (!repeatSingle && nextIdx < queueSentences.size && isActive && !isStoppingFlag) {
                            val nextSentence = queueSentences[nextIdx]
                            prefetchedIndex = nextIdx
                            prefetchedDeferred = async {
                                synthesizeAndCacheSentence(
                                    sentence = nextSentence,
                                    spec = resolvedSpec,
                                    languageCode = normLang,
                                    settings = settings,
                                    synthSpeed = synthSpeed
                                )
                            }
                            _state.value = _state.value.copy(bufferedAheadCount = 1)
                        } else {
                            _state.value = _state.value.copy(bufferedAheadCount = 0)
                        }
                    }

                    if (cachedHit != null) {
                        scheduleLookahead()
                        playCachedPayloadWithTracking(
                            sentence = currentSentence,
                            payload = cachedHit,
                            speedMode = settings.speedControlMode
                        )
                    } else {
                        playSentenceLiveStreaming(
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

                    if (!isActive || isStoppingFlag) break
                    if (!repeatSingle) {
                        idx++
                    }
                }

                if (isActive && !isStoppingFlag && _state.value.status != PlaybackStatus.ERROR) {
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.IDLE,
                        activeWordStartChar = -1,
                        activeWordEndChar = -1,
                        bufferedAheadCount = 0
                    )
                }
            } catch (_: CancellationException) {
                // Clean cancellation
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = t.message ?: "خطأ غير متوقع أثناء التشغيل"
                )
            } finally {
                prefetchedDeferred?.cancel()
                releaseAudioTrack()
                abandonAudioFocus()
            }
        }
    }

    private suspend fun synthesizeAndCacheSentence(
        sentence: SentenceEntity,
        spec: ModelSpec,
        languageCode: String,
        settings: UserSettings,
        synthSpeed: Float
    ): Result<CachedAudioPayload> = withContext(Dispatchers.IO) {
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
        var activeTrack: AudioTrack? = null
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
                if (!isActive || isStoppingFlag) return@collect

                val cleanSamples = AudioChunkCache.sanitizeSamples(chunk.samples)
                if (cleanSamples.isEmpty() && chunk.wordTimestamps.isEmpty()) return@collect

                val chunkRate = chunk.sampleRateHz.coerceIn(
                    AudioChunkCache.MIN_SAMPLE_RATE,
                    AudioChunkCache.MAX_SAMPLE_RATE
                )

                if (activeTrack == null && cleanSamples.isNotEmpty()) {
                    val opened = openStreamingTrack(chunkRate, speedMode)
                    activeTrack = opened.first
                    usePcm16 = opened.second
                    activeSampleRate = chunkRate
                    _state.value = _state.value.copy(
                        status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                        sentenceIndex = sentence.sentenceIndex,
                        currentSentenceText = sentence.originalText
                    )
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

            if (!isActive || isStoppingFlag) return@withContext

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
            if (track != null && totalFramesWritten > 0) {
                drainAudioTrack(track, activeSampleRate, totalFramesWritten, collectedTimestamps)
            }
        } finally {
            releaseAudioTrack()
        }
    }

    private suspend fun playCachedPayloadWithTracking(
        sentence: SentenceEntity,
        payload: CachedAudioPayload,
        speedMode: SpeedControlMode
    ) = withContext(Dispatchers.IO) {
        if (payload.samples.isEmpty()) return@withContext
        val sampleRate = payload.sampleRateHz.coerceIn(
            AudioChunkCache.MIN_SAMPLE_RATE,
            AudioChunkCache.MAX_SAMPLE_RATE
        )
        val (track, usePcm16) = openStreamingTrack(sampleRate, speedMode)
        try {
            _state.value = _state.value.copy(
                status = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                sentenceIndex = sentence.sentenceIndex,
                currentSentenceText = sentence.originalText
            )
            val written = writeSamplesToTrack(
                track = track,
                samples = payload.samples,
                usePcm16 = usePcm16,
                sampleRate = sampleRate,
                speedMode = speedMode,
                timestamps = payload.wordTimestamps
            )
            if (isActive && !isStoppingFlag && written > 0) {
                drainAudioTrack(track, sampleRate, written, payload.wordTimestamps)
            }
        } finally {
            releaseAudioTrack()
        }
    }

    private fun openStreamingTrack(
        sampleRate: Int,
        speedMode: SpeedControlMode
    ): Pair<AudioTrack, Boolean> {
        var usePcm16 = false
        var track = createAudioTrackOrNull(sampleRate, AudioFormat.ENCODING_PCM_FLOAT)
        if (track == null) {
            track = createAudioTrackOrNull(sampleRate, AudioFormat.ENCODING_PCM_16BIT)
            usePcm16 = true
        }
        val activeTrack = track
            ?: error("تعذر تهيئة مخرج الصوت AudioTrack بمعدل $sampleRate Hz.")

        synchronized(trackLock) {
            if (isStoppingFlag) {
                runCatching { activeTrack.release() }
                throw CancellationException("Stopped before track start")
            }
            currentAudioTrack = activeTrack
            applyPlaybackSpeedToTrack(activeTrack, speedMode)
            activeTrack.play()
        }
        return activeTrack to usePcm16
    }

    private fun writeSamplesToTrack(
        track: AudioTrack,
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

        while (offset < samples.size && playbackJob?.isActive == true && !isStoppingFlag) {
            if (isPausedFlag) {
                synchronized(trackLock) {
                    if (currentAudioTrack === track) {
                        runCatching {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                        }
                    }
                }
                _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
                while (isPausedFlag && playbackJob?.isActive == true && !isStoppingFlag) {
                    Thread.sleep(45L)
                }
                if (playbackJob?.isActive != true || isStoppingFlag) break
                synchronized(trackLock) {
                    if (currentAudioTrack === track) {
                        applyPlaybackSpeedToTrack(track, speedMode)
                        runCatching { track.play() }
                    }
                }
                _state.value = _state.value.copy(status = PlaybackStatus.PLAYING)
            }

            val count = minOf(stepFrames, samples.size - offset)
            val written = synchronized(trackLock) {
                if (isStoppingFlag || currentAudioTrack !== track || track.state != AudioTrack.STATE_INITIALIZED) {
                    -1
                } else {
                    runCatching {
                        if (pcm16Samples != null) {
                            track.write(pcm16Samples, offset, count, AudioTrack.WRITE_BLOCKING)
                        } else {
                            track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                        }
                    }.getOrDefault(-1)
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
            updateWordHighlightIfAvailable(track, sampleRate, timestamps)
        }
        return offset
    }

    private fun drainAudioTrack(
        track: AudioTrack,
        sampleRate: Int,
        totalFrames: Int,
        timestamps: List<WordTimestampRange>
    ) {
        val estimatedDurationMs = ((totalFrames.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(300L)
        val drainDeadlineMs = System.currentTimeMillis() + estimatedDurationMs + 1500L
        while (playbackJob?.isActive == true && !isStoppingFlag && !isPausedFlag &&
            System.currentTimeMillis() < drainDeadlineMs
        ) {
            val (head, isPlaying) = synchronized(trackLock) {
                if (isStoppingFlag || currentAudioTrack !== track || track.state != AudioTrack.STATE_INITIALIZED) {
                    totalFrames to false
                } else {
                    runCatching {
                        track.playbackHeadPosition to (track.playState == AudioTrack.PLAYSTATE_PLAYING)
                    }.getOrDefault(totalFrames to false)
                }
            }
            updateWordHighlightIfAvailable(track, sampleRate, timestamps)
            if (head >= totalFrames - 256 || !isPlaying) break
            Thread.sleep(25L)
        }
    }

    private fun createAudioTrackOrNull(sampleRate: Int, encoding: Int): AudioTrack? {
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

    private fun updateWordHighlightIfAvailable(
        track: AudioTrack,
        sampleRate: Int,
        timestamps: List<WordTimestampRange>
    ) {
        if (timestamps.isEmpty()) return
        val head = synchronized(trackLock) {
            if (currentAudioTrack !== track || track.state != AudioTrack.STATE_INITIALIZED) -1
            else runCatching { track.playbackHeadPosition }.getOrDefault(-1)
        }
        if (head < 0) return
        val sec = head.toFloat() / sampleRate.toFloat()
        val match = timestamps.firstOrNull { sec >= it.startSec && sec <= it.endSec }
        if (match != null) {
            _state.value = _state.value.copy(
                activeWordStartChar = match.startChar,
                activeWordEndChar = match.endChar
            )
        }
    }

    private fun applyPlaybackSpeedToTrack(track: AudioTrack, speedMode: SpeedControlMode) {
        val targetRate = if (speedMode == SpeedControlMode.AUDIO_TRACK_TIME_STRETCH) currentSpeed else 1.0f
        runCatching {
            val params = PlaybackParams().setSpeed(targetRate.coerceIn(0.5f, 2.0f)).setPitch(1.0f)
            track.playbackParams = params
        }
    }

    fun pause() {
        isPausedFlag = true
        synchronized(trackLock) {
            runCatching {
                if (currentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    currentAudioTrack?.pause()
                }
            }
        }
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    fun resume() {
        val st = _state.value
        if (st.status == PlaybackStatus.PAUSED) {
            isPausedFlag = false
            _state.value = st.copy(status = PlaybackStatus.PLAYING)
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
        startQueuePlayback(
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
        startQueuePlayback(
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
        startQueuePlayback(
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
        synchronized(trackLock) {
            currentAudioTrack?.let { track ->
                scope.launch {
                    val mode = settingsRepository.settingsFlow.first().speedControlMode
                    if (mode == SpeedControlMode.AUDIO_TRACK_TIME_STRETCH) {
                        synchronized(trackLock) {
                            if (currentAudioTrack === track) {
                                applyPlaybackSpeedToTrack(track, mode)
                            }
                        }
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
        isPausedFlag = false
        isStoppingFlag = true
        ttsEngineManager.cancelActiveSynthesis()
        playbackJob?.cancel()
        playbackJob = null
        releaseAudioTrack()
        abandonAudioFocus()
        if (resetPositionState) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.IDLE,
                activeWordStartChar = -1,
                activeWordEndChar = -1,
                bufferedAheadCount = 0
            )
        }
    }

    private fun releaseAudioTrack() {
        val track = synchronized(trackLock) {
            val t = currentAudioTrack
            currentAudioTrack = null
            t
        } ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            track.flush()
            track.stop()
        }
        runCatching { track.release() }
    }

    private fun requestAudioFocus(): Boolean {
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
                        pause()
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private fun ensureServiceStarted() {
        runCatching {
            val intent = Intent(context, PlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
