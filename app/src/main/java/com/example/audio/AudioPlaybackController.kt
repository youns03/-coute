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
import com.example.domain.FrenchTextPreprocessor
import com.example.domain.SpeedControlMode
import com.example.domain.WordTimestampRange
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
    val errorMessage: String? = null
)

private data class PreparedAudioChunk(
    val sentence: SentenceEntity,
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>
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

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var playbackJob: Job? = null
    private var currentAudioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var isPausedFlag: Boolean = false

    @Volatile
    private var queueSentences: List<SentenceEntity> = emptyList()

    @Volatile
    private var currentQueuePosition: Int = 0

    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var repeatSingle: Boolean = false

    fun playChapterFrom(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        startSentenceIndex: Int = 0
    ) {
        scope.launch {
            val sentences = bookDao.getChapterSentences(bookId, chapterIndex)
            if (sentences.isEmpty()) {
                _state.value = _state.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "لا توجد جمل في هذا الفصل."
                )
                return@launch
            }
            val safeIndex = startSentenceIndex.coerceIn(0, sentences.lastIndex)
            settingsRepository.setActiveBookId(bookId)
            startQueuePlayback(
                bookId = bookId,
                bookTitle = bookTitle,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                sentences = sentences,
                startIndex = safeIndex
            )
        }
    }

    /**
     * Synthesizes and plays a single French phrase or word (e.g. from Vocabulary or Word sheet).
     */
    fun pronounceStandalone(frenchText: String, title: String = "نطق المفردة") {
        val cleaned = frenchText.trim()
        if (cleaned.isEmpty()) return
        val singleSentence = SentenceEntity(
            id = -1L,
            bookId = "_standalone_",
            chapterIndex = 0,
            sentenceIndex = 0,
            paragraphIndex = 0,
            originalText = cleaned,
            spokenText = FrenchTextPreprocessor.prepareForSpeech(cleaned)
        )
        startQueuePlayback(
            bookId = "_standalone_",
            bookTitle = title,
            chapterIndex = 0,
            chapterTitle = cleaned,
            sentences = listOf(singleSentence),
            startIndex = 0
        )
    }

    private fun startQueuePlayback(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        sentences: List<SentenceEntity>,
        startIndex: Int
    ) {
        stopPlaybackInternal(resetPositionState = false)
        ensureServiceStarted()
        queueSentences = sentences
        currentQueuePosition = startIndex
        isPausedFlag = false

        playbackJob = scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            currentSpeed = settings.playbackSpeed
            val spec = ModelCatalog.findById(settings.activeModelId)

            _state.value = PlaybackUiState(
                status = PlaybackStatus.BUFFERING,
                bookId = bookId,
                bookTitle = bookTitle,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                sentenceIndex = sentences[startIndex].sentenceIndex,
                totalSentencesInChapter = sentences.size,
                currentSentenceText = sentences[startIndex].originalText,
                playbackSpeed = currentSpeed,
                repeatCurrentSentence = repeatSingle,
                activeModelName = spec?.displayName ?: settings.activeModelId
            )

            if (!requestAudioFocus()) {
                _state.value = _state.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "تعذر الحصول على تركيز الصوت من النظام."
                )
                return@launch
            }

            try {
                var idx = startIndex
                var prefetchedIndex = -1
                var prefetchedDeferred: kotlinx.coroutines.Deferred<Result<PreparedAudioChunk>>? = null

                while (isActive && idx < queueSentences.size) {
                    val currentSentence = queueSentences[idx]
                    currentQueuePosition = idx

                    // Persist reading progress for real books
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

                    val chunkResult = if (prefetchedIndex == idx && prefetchedDeferred != null) {
                        val res = prefetchedDeferred.await()
                        prefetchedDeferred = null
                        prefetchedIndex = -1
                        res
                    } else {
                        prefetchedDeferred?.cancel()
                        prefetchedDeferred = null
                        prefetchedIndex = -1
                        synthesizeOrLoadCached(currentSentence, settings)
                    }

                    if (chunkResult.isFailure) {
                        val err = chunkResult.exceptionOrNull()?.message ?: "فشل توليد الصوت محلياً"
                        _state.value = _state.value.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = err
                        )
                        break
                    }

                    val chunk = chunkResult.getOrThrow()

                    // Start 1-sentence lookahead prefetch in background for gapless playback
                    val nextIdx = if (repeatSingle) idx else idx + 1
                    if (!repeatSingle && nextIdx < queueSentences.size) {
                        val nextSentence = queueSentences[nextIdx]
                        prefetchedIndex = nextIdx
                        prefetchedDeferred = async { synthesizeOrLoadCached(nextSentence, settings) }
                        _state.value = _state.value.copy(bufferedAheadCount = 1)
                    } else {
                        _state.value = _state.value.copy(bufferedAheadCount = 0)
                    }

                    // Wait if user paused while buffering
                    while (isActive && isPausedFlag) {
                        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
                        delay(80L)
                    }
                    if (!isActive) break

                    playChunkWithTracking(chunk, settings.speedControlMode)

                    if (!isActive) break
                    if (!repeatSingle) {
                        idx++
                    }
                }

                if (isActive && _state.value.status != PlaybackStatus.ERROR) {
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
                releaseAudioTrack()
                abandonAudioFocus()
            }
        }
    }

    private suspend fun synthesizeOrLoadCached(
        sentence: SentenceEntity,
        settings: UserSettings
    ): Result<PreparedAudioChunk> {
        val synthSpeed = if (settings.speedControlMode == SpeedControlMode.MODEL_SYNTHESIS_RATE) {
            currentSpeed
        } else {
            1.0f
        }
        val cacheKey = "${settings.activeModelId}_${synthSpeed}_${sentence.spokenText.hashCode()}"
        val cacheFile = File(storageManager.audioCacheRoot, "$cacheKey.pcmbin")

        if (cacheFile.exists() && cacheFile.length() > 16L) {
            val cached = readCachedChunk(sentence, cacheFile)
            if (cached != null) return Result.success(cached)
        }

        val spec = ModelCatalog.findById(settings.activeModelId)
        val voiceId = spec?.voiceId ?: "ff_siwis"
        val res = ttsEngineManager.synthesizeSentence(
            modelId = settings.activeModelId,
            acceleratorMode = settings.acceleratorMode,
            spokenText = sentence.spokenText,
            voiceId = voiceId,
            speed = synthSpeed
        )
        return res.map { synth ->
            val chunk = PreparedAudioChunk(
                sentence = sentence,
                samples = synth.samples,
                sampleRateHz = synth.sampleRateHz,
                wordTimestamps = synth.wordTimestamps
            )
            writeCachedChunk(cacheFile, chunk)
            pruneAudioCacheIfOversized()
            chunk
        }
    }

    private suspend fun playChunkWithTracking(
        chunk: PreparedAudioChunk,
        speedMode: SpeedControlMode
    ) = withContext(Dispatchers.IO) {
        if (chunk.samples.isEmpty()) return@withContext
        val sampleRate = chunk.sampleRateHz
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate / 5 * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentAudioTrack = track
        applyPlaybackSpeedToTrack(track, speedMode)
        track.play()

        _state.value = _state.value.copy(
            status = PlaybackStatus.PLAYING,
            sentenceIndex = chunk.sentence.sentenceIndex,
            currentSentenceText = chunk.sentence.originalText
        )

        var offset = 0
        val step = 2048
        while (offset < chunk.samples.size && playbackJob?.isActive == true) {
            if (isPausedFlag) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
                while (isPausedFlag && playbackJob?.isActive == true) {
                    Thread.sleep(50)
                }
                if (playbackJob?.isActive != true) break
                applyPlaybackSpeedToTrack(track, speedMode)
                track.play()
                _state.value = _state.value.copy(status = PlaybackStatus.PLAYING)
            }

            val count = minOf(step, chunk.samples.size - offset)
            val written = track.write(chunk.samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            offset += written

            updateWordHighlightIfAvailable(track, sampleRate, chunk)
        }

        // Wait until buffered audio finishes playing before highlighting next sentence
        val totalFrames = chunk.samples.size
        while (playbackJob?.isActive == true && !isPausedFlag) {
            val head = track.playbackHeadPosition
            updateWordHighlightIfAvailable(track, sampleRate, chunk)
            if (head >= totalFrames - 256) break
            Thread.sleep(30)
        }

        releaseAudioTrack()
    }

    private fun updateWordHighlightIfAvailable(
        track: AudioTrack,
        sampleRate: Int,
        chunk: PreparedAudioChunk
    ) {
        if (chunk.wordTimestamps.isEmpty()) return
        val sec = track.playbackHeadPosition.toFloat() / sampleRate.toFloat()
        val match = chunk.wordTimestamps.firstOrNull { sec >= it.startSec && sec <= it.endSec }
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
        runCatching { currentAudioTrack?.pause() }
        _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
    }

    fun resume() {
        val st = _state.value
        if (st.status == PlaybackStatus.PAUSED) {
            isPausedFlag = false
            _state.value = st.copy(status = PlaybackStatus.PLAYING)
        } else if (st.status == PlaybackStatus.IDLE && st.bookId != null && st.bookId != "_standalone_") {
            playChapterFrom(
                bookId = st.bookId,
                bookTitle = st.bookTitle,
                chapterIndex = st.chapterIndex,
                chapterTitle = st.chapterTitle,
                startSentenceIndex = st.sentenceIndex
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
        startQueuePlayback(bId, st.bookTitle, st.chapterIndex, st.chapterTitle, queueSentences, nextPos)
    }

    fun previousSentence() {
        val st = _state.value
        val bId = st.bookId ?: return
        if (bId == "_standalone_" || queueSentences.isEmpty()) return
        val prevPos = (currentQueuePosition - 1).coerceAtLeast(0)
        startQueuePlayback(bId, st.bookTitle, st.chapterIndex, st.chapterTitle, queueSentences, prevPos)
    }

    fun replayCurrentSentence() {
        val st = _state.value
        val bId = st.bookId ?: return
        if (queueSentences.isEmpty()) return
        startQueuePlayback(
            bId,
            st.bookTitle,
            st.chapterIndex,
            st.chapterTitle,
            queueSentences,
            currentQueuePosition.coerceIn(0, queueSentences.lastIndex)
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        scope.launch { settingsRepository.setPlaybackSpeed(speed) }
        _state.value = _state.value.copy(playbackSpeed = speed)
        currentAudioTrack?.let { track ->
            scope.launch {
                val mode = settingsRepository.settingsFlow.first().speedControlMode
                if (mode == SpeedControlMode.AUDIO_TRACK_TIME_STRETCH) {
                    applyPlaybackSpeedToTrack(track, mode)
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
        val track = currentAudioTrack ?: return
        currentAudioTrack = null
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

    private fun writeCachedChunk(file: File, chunk: PreparedAudioChunk) {
        runCatching {
            DataOutputStream(FileOutputStream(file)).use { out ->
                out.writeInt(chunk.sampleRateHz)
                out.writeInt(chunk.samples.size)
                for (s in chunk.samples) out.writeFloat(s)
                out.writeInt(chunk.wordTimestamps.size)
                for (wt in chunk.wordTimestamps) {
                    out.writeUTF(wt.word)
                    out.writeInt(wt.startChar)
                    out.writeInt(wt.endChar)
                    out.writeFloat(wt.startSec)
                    out.writeFloat(wt.endSec)
                }
            }
        }
    }

    private fun readCachedChunk(sentence: SentenceEntity, file: File): PreparedAudioChunk? {
        return runCatching {
            DataInputStream(FileInputStream(file)).use { inp ->
                val sr = inp.readInt()
                val count = inp.readInt()
                val samples = FloatArray(count) { inp.readFloat() }
                val wtCount = inp.readInt()
                val wts = List(wtCount) {
                    WordTimestampRange(
                        word = inp.readUTF(),
                        startChar = inp.readInt(),
                        endChar = inp.readInt(),
                        startSec = inp.readFloat(),
                        endSec = inp.readFloat()
                    )
                }
                file.setLastModified(System.currentTimeMillis())
                PreparedAudioChunk(sentence, samples, sr, wts)
            }
        }.getOrNull()
    }

    private fun pruneAudioCacheIfOversized() {
        val maxBytes = 64L * 1024L * 1024L
        val files = storageManager.audioCacheRoot.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= maxBytes * 3 / 4) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
