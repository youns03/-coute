package com.example

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPlaybackController
import com.example.audio.PlaybackService
import com.example.audio.PlaybackStatus
import com.example.data.AppDatabase
import com.example.data.BookEntity
import com.example.data.ChapterEntity
import com.example.data.SettingsRepository
import com.example.domain.AcceleratorMode
import com.example.domain.ModelSpec
import com.example.domain.WordTimestampRange
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.tts.KokoroLiteRtTtsEngine
import com.example.tts.KokoroNativeSession
import com.example.tts.KokoroSessionFactory
import com.example.tts.KokoroSynthesisOutput
import com.example.tts.TtsEngineManager
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KokoroAndPlaybackTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storageManager: ModelStorageManager
    private lateinit var baseGpuSpec: ModelSpec
    private lateinit var installedGpuDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = ModelStorageManager(context)
        baseGpuSpec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        installedGpuDir = storageManager.getInstalledModelDir(baseGpuSpec.id).apply { mkdirs() }
        File(installedGpuDir, "kokoro_int8_gpu_fp16.litertlm").writeBytes(ByteArray(128) { 1 })
    }

    @After
    fun tearDown() {
        storageManager.deleteModelFiles(baseGpuSpec.id)
        db.close()
    }

    @Test
    fun `kokoro falls back to CPU on runtime synthesis throw and speed change throws on GPU`() = runTest {
        val createdDelegates = mutableListOf<Pair<Delegate, Float>>()
        val createdLocales = mutableListOf<String>()
        val factory = KokoroSessionFactory { _, _, delegate, _, locale, speakingRate ->
            createdDelegates.add(delegate to speakingRate)
            createdLocales.add(locale)
            if (delegate == Delegate.GPU && speakingRate > 1.1f) {
                throw IllegalStateException("OpenCL shader failure at high speed")
            }
            object : KokoroNativeSession {
                override fun synthesize(
                    spokenText: String,
                    voiceId: String,
                    locale: String
                ): KokoroSynthesisOutput {
                    if (delegate == Delegate.GPU) {
                        throw RuntimeException("GPU runtime execution error")
                    }
                    return KokoroSynthesisOutput(
                        samples = FloatArray(1024) { 0.1f },
                        sampleRateHz = 24000,
                        wordTimestamps = listOf(WordTimestampRange("Bonjour", 0, 7, 0f, 0.2f))
                    )
                }

                override fun synthesizeStream(
                    spokenText: String,
                    voiceId: String,
                    locale: String
                ): Flow<KokoroSynthesisOutput> = flow {
                    if (delegate == Delegate.GPU) {
                        throw RuntimeException("GPU streaming runtime error")
                    }
                    emit(
                        KokoroSynthesisOutput(
                            samples = FloatArray(512) { 0.1f },
                            sampleRateHz = 24000,
                            wordTimestamps = emptyList(),
                            isFinal = false
                        )
                    )
                    emit(
                        KokoroSynthesisOutput(
                            samples = FloatArray(512) { 0.2f },
                            sampleRateHz = 24000,
                            wordTimestamps = listOf(WordTimestampRange("Hello", 0, 5, 0f, 0.15f)),
                            isFinal = true
                        )
                    )
                }

                override fun cancel() {}
                override fun close() {}
            }
        }

        // Case 1: Runtime GPU synthesize() error triggers clean CPU delegate fallback
        val engineRuntimeFallback = KokoroLiteRtTtsEngine(context, storageManager, factory)
        val initRes = engineRuntimeFallback.initialize(
            spec = baseGpuSpec,
            modelDir = installedGpuDir,
            acceleratorMode = AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK
        )
        assertTrue(initRes.isSuccess)
        assertFalse(engineRuntimeFallback.fellBackToCpu)

        val synthRes = engineRuntimeFallback.synthesize("Bonjour tout le monde.", "ff_siwis", 1.0f, "fr-FR")
        assertTrue(synthRes.isSuccess)
        val output = synthRes.getOrThrow()
        assertTrue(output.fellBackToCpu)
        assertTrue(output.usedAccelerator.contains("CPU"))
        assertEquals(1024, output.samples.size)

        // Streaming English routing after fallback
        val streamChunks = engineRuntimeFallback.synthesizeStream("Hello world.", "af_heart", 1.0f, "en-US").toList()
        assertEquals(2, streamChunks.size)
        assertTrue(createdLocales.contains("en-US"))
        engineRuntimeFallback.release()

        // Case 2: GPU init succeeds at 1.0f, then speed change to 1.5f fails on GPU -> falls back to CPU at 1.5f
        createdDelegates.clear()
        val speedEngine = KokoroLiteRtTtsEngine(context, storageManager, factory)
        speedEngine.initialize(baseGpuSpec, installedGpuDir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK).getOrThrow()

        val speedSynthRes = speedEngine.synthesize("Bonjour à vitesse rapide.", "ff_siwis", 1.5f, "fr-FR")
        assertTrue(speedSynthRes.isSuccess)
        assertTrue(speedSynthRes.getOrThrow().fellBackToCpu)
        assertTrue(createdDelegates.any { it.first == Delegate.CPU && kotlin.math.abs(it.second - 1.5f) < 0.01f })
        speedEngine.release()
    }

    @Test
    fun `playback controller reports error state safely on empty chapter without crashing`() = runBlocking {
        db.bookDao().insertFullBook(
            book = BookEntity(
                id = "empty_book",
                title = "Empty",
                author = "Author",
                sourceType = "TXT",
                sourceLanguage = "fr",
                totalChapters = 1,
                totalSentences = 0
            ),
            chapters = listOf(
                ChapterEntity(
                    bookId = "empty_book",
                    chapterIndex = 0,
                    title = "Ch 1",
                    sentenceCount = 0
                )
            ),
            sentences = emptyList()
        )
        val controller = AudioPlaybackController(
            context = context,
            bookDao = db.bookDao(),
            settingsRepository = SettingsRepository(context),
            storageManager = storageManager,
            ttsEngineManager = TtsEngineManager(context, storageManager),
            playbackDispatcher = Dispatchers.IO
        )
        controller.playChapterFrom("empty_book", "Empty", 0, "Ch 1", 0)
        val errorState = withTimeout(5_000) {
            controller.state.first { it.status == PlaybackStatus.ERROR }
        }
        assertEquals("لا توجد جمل في هذا الفصل.", errorState.errorMessage)
    }

    @Test
    fun `playback service promotes to foreground immediately on plain start intent and cleans up`() {
        val serviceController = Robolectric.buildService(
            PlaybackService::class.java,
            Intent(context, PlaybackService::class.java)
        )
        val service = serviceController.create().startCommand(0, 0).get()
        // Verify MediaSession was registered on MediaSessionService and lifecycle did not throw
        assertTrue(service.sessions.isNotEmpty())
        serviceController.destroy()
    }
}
