package com.example.contracts

import android.net.Uri
import com.example.domain.AcceleratorMode
import com.example.domain.BenchmarkItem
import com.example.domain.BenchmarkReport
import com.example.domain.Book
import com.example.domain.EngineRuntimeState
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.PlaybackUiState
import com.example.domain.Sentence
import com.example.domain.TranscriptionRuntimeState
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.domain.WordTimestampRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface TtsEngine {
    val engineType: EngineType
    val modelId: String
    val sampleRateHz: Int
    val isLoaded: Boolean
    val activeAccelerator: String
    val fellBackToCpu: Boolean
    val fallbackReason: String?

    suspend fun initialize(
        spec: ModelSpec,
        modelDir: File,
        acceleratorMode: AcceleratorMode
    ): Result<Long>

    suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float = 1.0f,
        locale: String = "fr-FR"
    ): Result<TtsSynthesisResult>

    fun synthesizeStream(
        spokenText: String,
        voiceId: String,
        speed: Float = 1.0f,
        locale: String = "fr-FR"
    ): Flow<TtsStreamChunk>

    fun cancel()
    fun release()
}

fun interface TtsEngineFactory {
    fun create(spec: ModelSpec): TtsEngine
}

interface TtsRuntimeGateway : ModelLoadVerifier {
    val runtimeState: StateFlow<EngineRuntimeState>
    val isEngineLoaded: Boolean

    fun setOnActivityCallback(callback: () -> Unit)
    fun resolveModelForLanguage(preferredModelId: String, languageCode: String): Result<ModelSpec>
    suspend fun ensureLoaded(modelId: String, acceleratorMode: AcceleratorMode): Result<TtsEngine>
    suspend fun switchModel(modelId: String, acceleratorMode: AcceleratorMode): Result<TtsEngine>
    suspend fun synthesizeSentence(
        modelId: String,
        acceleratorMode: AcceleratorMode,
        spokenText: String,
        voiceId: String,
        speed: Float,
        languageCode: String = "fr"
    ): Result<TtsSynthesisResult>

    fun synthesizeSentenceStream(
        modelId: String,
        acceleratorMode: AcceleratorMode,
        spokenText: String,
        languageCode: String = "fr",
        voiceId: String = "",
        speed: Float = 1.0f
    ): Flow<TtsStreamChunk>

    suspend fun runBenchmarkItem(
        spec: ModelSpec,
        item: BenchmarkItem,
        acceleratorMode: AcceleratorMode,
        speedFactor: Float
    ): Result<BenchmarkReport>

    fun cancelActiveSynthesis()
    suspend fun releaseActiveEngine()
}

interface SpeechRecognitionEngine {
    val isLoaded: Boolean
    val loadedModelId: String?
    suspend fun verifyLoadable(spec: ModelSpec, extractedDir: File): Result<Unit>
    suspend fun ensureLoaded(spec: ModelSpec, languageCode: String): Result<Unit>
    suspend fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String
    suspend fun release()
}

interface AudioTranscriptionGateway : ModelLoadVerifier {
    val state: StateFlow<TranscriptionRuntimeState>
    val isAsrLoaded: Boolean
    val isTranscribing: Boolean

    fun setOnActivityCallback(callback: () -> Unit)
    fun resolveReadyAsrModel(preferredId: String): Result<ModelSpec>
    fun enqueueAudioImportAndTranscribe(
        uri: Uri,
        languageCode: String,
        onCompletedBook: (String) -> Unit = {}
    )

    suspend fun importAndTranscribeBlocking(
        uri: Uri,
        languageCode: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<Book>

    suspend fun releaseActiveEngine()
}

interface AudioSamplePlayerGateway {
    suspend fun playWavFile(path: String)
}

interface PlaybackPersistenceGateway {
    suspend fun getBook(bookId: String): Book?
    suspend fun getChapterSentences(bookId: String, chapterIndex: Int): List<Sentence>
    suspend fun updateReadingPosition(bookId: String, chapterIndex: Int, sentenceIndex: Int)
    suspend fun setBookCompleted(bookId: String, completed: Boolean)
}

data class CachedAudioEntry(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>
)

interface AudioCacheGateway {
    fun buildCacheKey(
        modelId: String,
        languageCode: String,
        voiceId: String,
        speed: Float,
        spokenText: String
    ): String

    fun readValidated(cacheKey: String): CachedAudioEntry?
    fun write(cacheKey: String, entry: CachedAudioEntry): Boolean
    fun pruneIfOversized()
}

fun interface PlaybackServiceStarter {
    fun ensureStarted()
}

interface PlaybackGateway {
    val state: StateFlow<PlaybackUiState>

    fun playChapterFrom(
        bookId: String,
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        startSentenceIndex: Int = 0,
        languageCode: String? = null
    )

    fun pronounceStandalone(
        frenchText: String,
        title: String = "نطق المفردة",
        languageCode: String = "fr"
    )

    fun pause()
    fun resume()
    fun togglePlayPause()
    fun stop()
    fun nextSentence()
    fun previousSentence()
    fun nextParagraph()
    fun previousParagraph()
    fun seekRelativeSeconds(deltaSeconds: Int)
    fun replayCurrentSentence()
    fun setPlaybackSpeed(speed: Float)
    fun setActiveVoice(voiceId: String)
    fun startSleepTimerMinutes(minutes: Int)
    fun startEndOfChapterSleepTimer()
    fun cancelSleepTimer()
    fun toggleRepeatCurrentSentence()
}
