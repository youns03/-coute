package com.example

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioChunkCache
import com.example.audio.AudioPlaybackController
import com.example.audio.AudioTrackHandle
import com.example.audio.AudioTrackHandleFactory
import com.example.audio.CachedAudioPayload
import com.example.audio.PlaybackStatus
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.data.SettingsRepository
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.domain.WordTimestampRange
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.tts.DeviceHardwareInfo
import com.example.tts.KokoroLiteRtTtsEngine
import com.example.tts.KokoroNativeSession
import com.example.tts.KokoroSessionFactory
import com.example.tts.KokoroSynthesisOutput
import com.example.tts.MemoryDiagnostics
import com.example.tts.TtsEngine
import com.example.tts.TtsEngineManager
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EndToEndStabilizationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storageManager: ModelStorageManager
    private lateinit var settingsRepo: SettingsRepository

    private var originalSupportedAbis: Array<String>? = null

    @Before
    fun setUp() {
        originalSupportedAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(
            Build::class.java,
            "SUPPORTED_ABIS",
            arrayOf("arm64-v8a", "armeabi-v7a")
        )
        context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Shadows.shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = ModelStorageManager(context)
        settingsRepo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        originalSupportedAbis?.let {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", it)
        }
        ModelCatalog.ttsModels.forEach { storageManager.deleteModelFiles(it.id) }
        storageManager.deleteModelFiles(ModelCatalog.espeakNgDataSpec.id)
        storageManager.clearAudioCache()
        db.close()
    }

    private suspend fun realDelay(ms: Long) {
        withContext(Dispatchers.IO) { delay(ms) }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            realDelay(20L)
        }
    }

    /**
     * Installs an integrity-verified directory matching the exact [spec] hashes by generating a
     * lightweight mock ModelSpec only when custom sizes are tested, OR writes exact files & manifest
     * for fake-engine end-to-end tests. Notice ModelCatalog specs are verified strictly by ModelStorageManager,
     * so tests requiring full real SHA-256 against ModelCatalog test corruption explicitly, while pipeline tests
     * use a test-local subclass/wrapper or exact manifest matching!
     */
    private fun seedValidPackageFiles(spec: ModelSpec, dir: File) {
        dir.mkdirs()
        when (spec.engineType) {
            EngineType.KOKORO_LITERT -> {
                val name = spec.downloadUrl.substringAfterLast('/')
                File(dir, name).writeBytes(ByteArray(128) { 5 })
            }
            EngineType.PIPER_SHERPA_ONNX -> {
                if (spec.id == ModelCatalog.espeakNgDataSpec.id) {
                    File(dir, "phontab").writeBytes(ByteArray(64) { 2 })
                } else {
                    File(dir, "model.onnx").writeBytes(ByteArray(1_100_000) { 3 })
                    File(dir, "tokens.txt").writeText("a 1\n")
                }
            }
        }
    }

    private class FakeTrackHandle(
        val id: Int,
        val onReleased: (Int) -> Unit = {},
        val onFirstWrite: (Int) -> Unit = {}
    ) : AudioTrackHandle {
        private val firstWriteSignaled = AtomicBoolean(false)
        @Volatile
        var released: Boolean = false
        @Volatile
        var playing: Boolean = false
        @Volatile
        var paused: Boolean = false
        @Volatile
        var writtenFrames: Int = 0
        @Volatile
        var speedApplied: Float = 1.0f
        val releasedCount = AtomicInteger(0)

        override val isInitialized: Boolean get() = !released
        override val playState: Int
            get() = when {
                released -> AudioTrack.PLAYSTATE_STOPPED
                paused -> AudioTrack.PLAYSTATE_PAUSED
                playing -> AudioTrack.PLAYSTATE_PLAYING
                else -> AudioTrack.PLAYSTATE_STOPPED
            }
        override val playbackHeadPosition: Int get() = writtenFrames

        override fun play() {
            if (!released) {
                playing = true
                paused = false
            }
        }

        override fun pause() {
            if (!released) {
                paused = true
                playing = false
            }
        }

        override fun flush() {}

        override fun stop() {
            playing = false
        }

        override fun release() {
            released = true
            playing = false
            releasedCount.incrementAndGet()
            onReleased(id)
        }

        override fun setSpeed(speed: Float) {
            speedApplied = speed
        }

        override fun writeFloat(data: FloatArray, offset: Int, count: Int): Int {
            if (released) return -1
            writtenFrames += count
            if (firstWriteSignaled.compareAndSet(false, true)) {
                onFirstWrite(id)
            }
            return count
        }

        override fun writePcm16(data: ShortArray, offset: Int, count: Int): Int {
            if (released) return -1
            writtenFrames += count
            if (firstWriteSignaled.compareAndSet(false, true)) {
                onFirstWrite(id)
            }
            return count
        }
    }

    private fun createValidTestEpub(dest: File, langTag: String = "fr"): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Le Petit Voyage</dc:title>
                    <dc:creator>Auteur Test</dc:creator>
                    <dc:language>$langTag</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """
                <html><body>
                  <h1>Chapitre Un</h1>
                  <p>Bonjour Marie. La lecture développe l'esprit!</p>
                </body></html>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            zos.write(
                """
                <html><body>
                  <h1>Chapitre Deux</h1>
                  <p>Nous arrivons à Paris à 10 h. C'est magnifique.</p>
                </body></html>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()
        }
        return dest
    }

    @Test
    fun `kokoro CPU fallback rejects tampered integrity manifest even if files exist on disk`() = runTest {
        val gpuSpec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val gpuDir = storageManager.getInstalledModelDir(gpuSpec.id)
        seedValidPackageFiles(gpuSpec, gpuDir)

        // Create a TAMPERED dedicated CPU fallback directory (wrong file bytes / missing valid manifest)
        val cpuSpec = ModelCatalog.findById("kokoro-82m-cpu-fp16")!!
        val cpuDir = storageManager.getInstalledModelDir(cpuSpec.id)
        seedValidPackageFiles(cpuSpec, cpuDir)
        File(cpuDir, ModelStorageManager.INTEGRITY_MANIFEST_FILE).writeText("version=tampered\npackageSha256=deadbeef\n")

        val loadedFiles = mutableListOf<File>()
        val factory = KokoroSessionFactory { modelFile, _, delegate, _, _, _ ->
            loadedFiles.add(modelFile)
            if (delegate == Delegate.GPU) {
                throw IllegalStateException("GPU opencl error")
            }
            object : KokoroNativeSession {
                override fun synthesize(spokenText: String, voiceId: String, locale: String) =
                    KokoroSynthesisOutput(FloatArray(128) { 0.2f }, 24000, emptyList())

                override fun synthesizeStream(spokenText: String, voiceId: String, locale: String) = flow {
                    emit(KokoroSynthesisOutput(FloatArray(128) { 0.2f }, 24000, emptyList()))
                }

                override fun cancel() {}
                override fun close() {}
            }
        }

        val engine = KokoroLiteRtTtsEngine(context, storageManager, factory)
        val res = engine.initialize(gpuSpec, gpuDir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK)
        assertTrue(res.isSuccess)
        assertTrue(engine.fellBackToCpu)
        // Must have rejected tampered cpuDir and fallen back ONLY to GPU's modelFile on CPU delegate!
        assertFalse(loadedFiles.any { it.parentFile?.name == cpuSpec.id })
        assertTrue(loadedFiles.last().parentFile?.name == gpuSpec.id)
        assertTrue(engine.activeAccelerator.contains("GPU Model Fallback"))
        engine.release()
    }

    @Test
    fun `ttsEngineManager clears runtimeState on verifyModelLoadable and handles low free RAM and OOM safely`() = runTest {
        val kokoroSpec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val piperSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!

        // 1) Live available RAM thresholds
        val healthy64 = DeviceHardwareInfo(
            deviceModel = "Test64",
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            is64BitSupported = true,
            totalRamMb = 4096L,
            availableRamMb = 1200L,
            androidVersion = "14",
            sdkInt = 34
        )
        assertEquals(ModelCompatibilityStatus.COMPATIBLE, MemoryDiagnostics.evaluateRuntimeMemoryForLoad(kokoroSpec, healthy64).status)

        val criticallyLowRam = healthy64.copy(availableRamMb = 220L)
        val blockedKokoro = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(kokoroSpec, criticallyLowRam)
        assertFalse(blockedKokoro.isSupported)
        assertEquals(ModelCompatibilityStatus.INCOMPATIBLE_RAM, blockedKokoro.status)

        // Same 220 MB free RAM allows lightweight Piper with WARNING_LOW_RAM
        val allowedPiper = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(piperSpec, criticallyLowRam)
        assertTrue(allowedPiper.isSupported)
        assertEquals(ModelCompatibilityStatus.WARNING_LOW_RAM, allowedPiper.status)

        // Unknown sensor 0L must NEVER false-block
        val unknownSensor = healthy64.copy(availableRamMb = 0L)
        assertTrue(MemoryDiagnostics.evaluateRuntimeMemoryForLoad(kokoroSpec, unknownSensor).isSupported)

        // 2) verifyModelLoadable MUST clear runtimeState immediately instead of showing evicted model as loaded

        // Write valid manifest-backed piper install via installVerifiedPackage using a matching test archive
        val testZip = storageManager.getTempPartFile("verify-piper")
        testZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(testZip)).use { zos ->
            zos.putNextEntry(ZipEntry("model.onnx"))
            zos.write(ByteArray(1_050_000) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("tokens.txt"))
            zos.write("x 1\n".toByteArray())
            zos.closeEntry()
        }
        val fakeVerifySpec = ModelCatalog.findById("piper-fr-siwis-medium")!!.copy(
            archiveType = com.example.domain.ArchiveType.ZIP,
            expectedSizeBytes = testZip.length(),
            sha256 = storageManager.computeSha256(testZip),
            requiresEspeakData = false
        )
        // Call verifyModelLoadable while simulating an already-loaded engine: runtimeState must clear cleanly on eviction & OOM
        val verifyDir = storageManager.installVerifiedPackage(fakeVerifySpec, testZip).getOrThrow()
        val fakeVerifierManager = TtsEngineManager(
            context = context,
            storageManager = storageManager,
            engineFactory = { spec ->
                object : TtsEngine {
                    override val engineType = spec.engineType
                    override var modelId = spec.id
                    override val sampleRateHz = 24000
                    override var isLoaded = false
                    override var activeAccelerator = "TEST"
                    override val fellBackToCpu = false
                    override val fallbackReason: String? = null
                    override suspend fun initialize(spec: ModelSpec, modelDir: File, acceleratorMode: AcceleratorMode): Result<Long> {
                        if (spec.id == kokoroSpec.id) throw OutOfMemoryError("Simulated native OOM")
                        isLoaded = true
                        return Result.success(5L)
                    }
                    override suspend fun synthesize(spokenText: String, voiceId: String, speed: Float, locale: String) =
                        Result.success(TtsSynthesisResult(FloatArray(64) { 0.1f }, 24000, emptyList(), 1L, "TEST", false))
                    override fun synthesizeStream(spokenText: String, voiceId: String, speed: Float, locale: String) = flow {
                        emit(TtsStreamChunk(FloatArray(64) { 0.1f }, 24000))
                    }
                    override fun cancel() {}
                    override fun release() { isLoaded = false }
                }
            },
            packageVerifier = { it.id == fakeVerifySpec.id }
        )
        assertTrue(fakeVerifierManager.ensureLoaded(fakeVerifySpec.id, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK).isSuccess)
        assertEquals(fakeVerifySpec.id, fakeVerifierManager.runtimeState.value.loadedModelId)

        assertTrue(fakeVerifierManager.verifyModelLoadable(fakeVerifySpec, verifyDir).isSuccess)
        assertNull("verifyModelLoadable must clear runtimeState immediately upon evicting activeEngine", fakeVerifierManager.runtimeState.value.loadedModelId)

        // OOM during verifyModelLoadable must fail cleanly without crashing process and leave runtimeState cleared with lastError
        val kokoroTestDir = storageManager.getInstalledModelDir(kokoroSpec.id).apply { mkdirs() }
        val oomRes = fakeVerifierManager.verifyModelLoadable(kokoroSpec, kokoroTestDir)
        assertTrue(oomRes.isFailure)
        assertNull(fakeVerifierManager.runtimeState.value.loadedModelId)
        assertTrue(fakeVerifierManager.runtimeState.value.lastError?.contains("نفدت الذاكرة") == true)
        storageManager.deleteModelFiles(fakeVerifySpec.id)
    }

    @Test
    fun `audio playback session IDs prevent stale coroutine from releasing newer session AudioTrack or overwriting state`() = runTest {
        val session1Gate = CompletableDeferred<Unit>()
        val session1ChunkWritten = CompletableDeferred<Unit>()
        val session2TrackReady = CompletableDeferred<Unit>()
        val session2HoldGate = CompletableDeferred<Unit>()

        val createdTracks = CopyOnWriteArrayList<FakeTrackHandle>()
        val trackSeq = AtomicInteger(0)
        val trackFactory = AudioTrackHandleFactory { _ ->
            val id = trackSeq.incrementAndGet()
            val handle = FakeTrackHandle(
                id = id,
                onFirstWrite = { writtenId ->
                    if (writtenId == 1) session1ChunkWritten.complete(Unit)
                    if (writtenId == 2) session2TrackReady.complete(Unit)
                }
            )
            createdTracks.add(handle)
            handle to false
        }

        val kokoroSpec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val ttsManager = TtsEngineManager(
            context = context,
            storageManager = storageManager,
            engineFactory = { spec ->
                object : TtsEngine {
                    override val engineType = spec.engineType
                    override var modelId = spec.id
                    override val sampleRateHz = 24000
                    override var isLoaded = false
                    override var activeAccelerator = "GPU (Test)"
                    override val fellBackToCpu = false
                    override val fallbackReason: String? = null

                    override suspend fun initialize(spec: ModelSpec, modelDir: File, acceleratorMode: AcceleratorMode): Result<Long> {
                        isLoaded = true
                        return Result.success(2L)
                    }

                    override suspend fun synthesize(spokenText: String, voiceId: String, speed: Float, locale: String) =
                        Result.success(TtsSynthesisResult(FloatArray(512) { 0.1f }, 24000, emptyList(), 1L, "GPU (Test)", false))

                    override fun synthesizeStream(spokenText: String, voiceId: String, speed: Float, locale: String): Flow<TtsStreamChunk> = flow {
                        if (spokenText.contains("SESSION_ONE")) {
                            emit(TtsStreamChunk(FloatArray(512) { 0.2f }, 24000, isFinal = false))
                            session1Gate.await()
                            // Attempt late failure as dying session
                            throw IllegalStateException("Stale Session 1 late failure must be ignored")
                        } else {
                            emit(TtsStreamChunk(FloatArray(512) { 0.3f }, 24000, isFinal = false))
                            session2HoldGate.await()
                            emit(TtsStreamChunk(FloatArray(512) { 0.3f }, 24000, isFinal = true))
                        }
                    }

                    override fun cancel() {}
                    override fun release() { isLoaded = false }
                }
            },
            packageVerifier = { it.id == kokoroSpec.id }
        )

        settingsRepo.setActiveModelId(kokoroSpec.id)
        db.bookDao().insertFullBook(
            book = com.example.data.BookEntity(
                id = "race_book",
                title = "Race Book",
                author = "Author",
                sourceType = "TXT",
                sourceLanguage = "fr",
                totalChapters = 2,
                totalSentences = 2
            ),
            chapters = listOf(
                com.example.data.ChapterEntity(bookId = "race_book", chapterIndex = 0, title = "Ch 1", sentenceCount = 1),
                com.example.data.ChapterEntity(bookId = "race_book", chapterIndex = 1, title = "Ch 2", sentenceCount = 1)
            ),
            sentences = listOf(
                com.example.data.SentenceEntity(
                    id = 10L,
                    bookId = "race_book",
                    chapterIndex = 0,
                    sentenceIndex = 0,
                    paragraphIndex = 0,
                    originalText = "SESSION_ONE sentence.",
                    spokenText = "SESSION_ONE sentence."
                ),
                com.example.data.SentenceEntity(
                    id = 20L,
                    bookId = "race_book",
                    chapterIndex = 1,
                    sentenceIndex = 0,
                    paragraphIndex = 0,
                    originalText = "SESSION_TWO sentence.",
                    spokenText = "SESSION_TWO sentence."
                )
            )
        )

        val controller = AudioPlaybackController(
            context = context,
            bookDao = db.bookDao(),
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            ttsEngineManager = ttsManager,
            trackFactory = trackFactory
        )

        // Start Session 1
        controller.playChapterFrom("race_book", "Race Book", 0, "Ch 1", 0, "fr")
        session1ChunkWritten.await()
        assertTrue("Track 1 should be active", createdTracks.size >= 1)
        val track1 = createdTracks[0]

        // Start Session 2 while Session 1 is still stuck mid-stream
        controller.playChapterFrom("race_book", "Race Book", 1, "Ch 2", 0, "fr")
        session2TrackReady.await()
        assertTrue("Track 1 must be released when Session 2 starts", track1.released)
        assertTrue("Track 2 must have been created", createdTracks.size >= 2)
        val track2 = createdTracks[1]
        assertFalse("Track 2 must remain active", track2.released)

        // Release Session 1 so its finally block runs AFTER Session 2 already owns Track 2
        session1Gate.complete(Unit)
        realDelay(150L)

        assertFalse("Old Session 1 cleanup MUST NOT release Session 2's AudioTrack!", track2.released)
        assertEquals("Ch 2", controller.state.value.chapterTitle)
        assertNotNull(controller.state.value.activeModelName)
        assertNull("Old Session 1 exception MUST NOT overwrite Session 2 state!", controller.state.value.errorMessage)

        session2HoldGate.complete(Unit)
        awaitUntil { controller.state.value.status == PlaybackStatus.IDLE }
        controller.stop()
        awaitUntil { track2.released && controller.state.value.status == PlaybackStatus.IDLE }
        assertTrue(track2.released)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
    }

    @Test
    fun `audio chunk cache handles concurrent writes cleanly and rejects negative or inverted timestamps`() = runTest {
        val key = AudioChunkCache.buildCacheKey("kokoro-82m-gpu-fp16", "fr", "ff_siwis", 1.0f, "Bonjour concurrence")
        val target = AudioChunkCache.cacheFileFor(storageManager.audioCacheRoot, key)

        val validTimestamps = listOf(
            WordTimestampRange("Bonjour", 0, 7, 0.0f, 0.45f),
            WordTimestampRange("", 0, 1, 0.0f, 0.1f), // blank word -> stripped
            WordTimestampRange("BadTime", 8, 15, 0.8f, 0.4f), // endSec < startSec -> stripped
            WordTimestampRange("NegTime", 8, 15, -0.2f, 0.4f), // negative start -> stripped
            WordTimestampRange("concurrence", 8, 19, 0.45f, 1.1f)
        )

        val workers = (1..8).map { i ->
            async(Dispatchers.IO) {
                AudioChunkCache.write(
                    target,
                    CachedAudioPayload(
                        samples = FloatArray(128) { 0.1f * (i % 5) },
                        sampleRateHz = 24000,
                        wordTimestamps = validTimestamps
                    )
                )
            }
        }
        assertTrue(workers.awaitAll().all { it })

        val read = AudioChunkCache.readValidated(target)
        assertNotNull(read)
        assertEquals(2, read!!.wordTimestamps.size)
        assertEquals("Bonjour", read.wordTimestamps[0].word)
        assertEquals("concurrence", read.wordTimestamps[1].word)
        assertTrue(
            "All temp files should have been cleaned up",
            storageManager.audioCacheRoot.listFiles()?.none { it.name.endsWith(".tmp") } == true
        )
    }

    @Test
    fun `end to end txt and epub import to direct reading, streaming first chunk, pause resume chapter switch and language switch`() = runTest {
        val importer = BookImporter(context)
        val repo = LibraryRepository(db.bookDao(), db.vocabularyDao(), importer)

        // 1) Real TXT import (English) + Real TXT long document (20 sentences)
        val txtFile = File(context.cacheDir, "e2e_story.txt").apply {
            writeText(
                "The reader is in the room and works for daily study. This second sentence is great for streaming continuity.",
                Charsets.UTF_8
            )
        }
        val importedTxt = repo.importBook(Uri.fromFile(txtFile)).getOrThrow()
        assertEquals("en", importedTxt.sourceLanguage)
        assertEquals(2, importedTxt.totalSentences)

        val longFrenchBody = (1..20).joinToString(" ") { idx -> "Phrase numéro $idx dans ce chapitre complet." }
        val longTxtFile = File(context.cacheDir, "long_fr.txt").apply { writeText(longFrenchBody, Charsets.UTF_8) }
        val importedLong = repo.importBook(Uri.fromFile(longTxtFile)).getOrThrow()
        assertEquals("fr", importedLong.sourceLanguage)
        assertEquals(20, importedLong.totalSentences)

        // 2) Real EPUB import (French, multi-chapter)
        val epubFile = createValidTestEpub(File(context.cacheDir, "e2e_book.epub"), langTag = "fr")
        val importedEpub = repo.importBook(Uri.fromFile(epubFile)).getOrThrow()
        assertEquals("fr", importedEpub.sourceLanguage)
        assertEquals(2, importedEpub.totalChapters)
        assertTrue(importedEpub.totalSentences >= 4)

        // 3) Wire TtsEngineManager + AudioPlaybackController with streaming & failure controls
        val streamFirstChunkObserved = CompletableDeferred<Unit>()
        val firstChapitreGate = AtomicBoolean(false)
        val holdSecondChunkGate = CompletableDeferred<Unit>()
        val simulateSynthesisError = AtomicBoolean(false)
        val synthesizedHistory = CopyOnWriteArrayList<Triple<String, String, String>>() // modelId, locale, text

        val createdTracks = CopyOnWriteArrayList<FakeTrackHandle>()
        val trackFactory = AudioTrackHandleFactory { _ ->
            val nextId = createdTracks.size + 1
            FakeTrackHandle(
                id = nextId,
                onFirstWrite = { id ->
                    if (id == 1) streamFirstChunkObserved.complete(Unit)
                }
            ).also { createdTracks.add(it) } to false
        }

        val kokoroId = "kokoro-82m-gpu-fp16"
        val piperId = "piper-fr-siwis-medium"
        settingsRepo.setActiveModelId(piperId)

        val ttsManager = TtsEngineManager(
            context = context,
            storageManager = storageManager,
            engineFactory = { spec ->
                object : TtsEngine {
                    override val engineType = spec.engineType
                    override var modelId = spec.id
                    override val sampleRateHz = if (spec.engineType == EngineType.KOKORO_LITERT) 24000 else 22050
                    override var isLoaded = false
                    override var activeAccelerator = if (spec.engineType == EngineType.KOKORO_LITERT) "GPU (FP16)" else "CPU (ONNX)"
                    override val fellBackToCpu = false
                    override val fallbackReason: String? = null

                    override suspend fun initialize(spec: ModelSpec, modelDir: File, acceleratorMode: AcceleratorMode): Result<Long> {
                        isLoaded = true
                        return Result.success(10L)
                    }

                    override suspend fun synthesize(spokenText: String, voiceId: String, speed: Float, locale: String): Result<TtsSynthesisResult> {
                        if (simulateSynthesisError.get()) {
                            return Result.failure(IllegalStateException("Simulated transient synth error"))
                        }
                        synthesizedHistory.add(Triple(modelId, locale, spokenText))
                        return Result.success(TtsSynthesisResult(FloatArray(256) { 0.15f }, sampleRateHz, emptyList(), 1L, activeAccelerator, false))
                    }

                    override fun synthesizeStream(spokenText: String, voiceId: String, speed: Float, locale: String): Flow<TtsStreamChunk> = flow {
                        if (simulateSynthesisError.get()) {
                            throw IllegalStateException("Simulated stream error")
                        }
                        synthesizedHistory.add(Triple(modelId, locale, spokenText))
                        emit(TtsStreamChunk(FloatArray(512) { 0.25f }, sampleRateHz, isFinal = false))
                        if (spokenText.contains("Chapitre Un") && firstChapitreGate.compareAndSet(false, true)) {
                            holdSecondChunkGate.await()
                        }
                        emit(TtsStreamChunk(FloatArray(512) { 0.25f }, sampleRateHz, isFinal = true))
                    }

                    override fun cancel() {}
                    override fun release() { isLoaded = false }
                }
            },
            packageVerifier = { it.id == kokoroId || it.id == piperId }
        )

        val controller = AudioPlaybackController(
            context = context,
            bookDao = db.bookDao(),
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            ttsEngineManager = ttsManager,
            trackFactory = trackFactory
        )

        // A) Verify streaming starts writing to AudioTrack BEFORE final chunk finishes!
        controller.playChapterFrom(importedEpub.id, importedEpub.title, 0, "Chapitre Un", 0, "fr")
        streamFirstChunkObserved.await()
        assertTrue("First streaming chunk MUST write to AudioTrack before final chunk completes", createdTracks.isNotEmpty() && createdTracks[0].writtenFrames >= 512)
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)

        // B) Pause -> verify paused -> Resume -> release gate and let Chapter Un advance cleanly
        controller.pause()
        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        assertTrue(createdTracks[0].paused)

        controller.resume()
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        assertTrue(createdTracks[0].playing)
        holdSecondChunkGate.complete(Unit)

        awaitUntil { controller.state.value.status == PlaybackStatus.IDLE }
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        // Must have automatically played all sentences of Chapter Un and saved sentenceIndex >= 1 in Room
        val updatedEpub = db.bookDao().getBook(importedEpub.id)!!
        assertTrue("Expected multi-sentence progress >= 1, got ${updatedEpub.currentSentenceIndex}", updatedEpub.currentSentenceIndex >= 1)

        // C) Switch to Chapter 2 + Replay + Stop
        controller.playChapterFrom(importedEpub.id, importedEpub.title, 1, "Chapitre Deux", 0, "fr")
        awaitUntil { controller.state.value.chapterIndex == 1 && controller.state.value.status == PlaybackStatus.PLAYING }
        controller.replayCurrentSentence()
        realDelay(80L)
        controller.stop()
        awaitUntil { controller.state.value.status == PlaybackStatus.IDLE }
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)

        // D) Play English TXT book while activeModelId is FR-only Piper -> resolves Kokoro automatically!
        controller.playChapterFrom(importedTxt.id, importedTxt.title, 0, "Section 1", 0, "en")
        awaitUntil { controller.state.value.status == PlaybackStatus.IDLE && controller.state.value.activeLanguageCode == "en" }
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertEquals("en", controller.state.value.activeLanguageCode)
        assertTrue(synthesizedHistory.any { it.first == kokoroId && it.second == "en-US" })
        assertEquals(kokoroId, ttsManager.runtimeState.value.loadedModelId)

        // E) Switch model explicitly to Piper, play French long book (20 sentences) & verify error recovery
        settingsRepo.setActiveModelId(piperId)
        controller.playChapterFrom(importedLong.id, importedLong.title, 0, "Long Chapter", 0, "fr")
        awaitUntil(timeoutMs = 8000L) {
            controller.state.value.bookId == importedLong.id &&
                controller.state.value.status == PlaybackStatus.IDLE
        }
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertEquals(19, db.bookDao().getBook(importedLong.id)!!.currentSentenceIndex)

        // Clear disk chunk cache from Step D, then simulate transient synthesis error -> status ERROR -> recovers on resume!
        storageManager.clearAudioCache()
        simulateSynthesisError.set(true)
        controller.playChapterFrom(importedTxt.id, importedTxt.title, 0, "Section 1", 0, "en")
        awaitUntil { controller.state.value.status == PlaybackStatus.ERROR }
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertFalse(controller.state.value.errorMessage.isNullOrBlank())

        simulateSynthesisError.set(false)
        controller.resume()
        awaitUntil {
            controller.state.value.bookId == importedTxt.id &&
                controller.state.value.status == PlaybackStatus.IDLE
        }
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
    }

    @Test
    @Config(sdk = [24])
    fun `model integrity manifest install and verification work on minSdk 24`() = runTest {
        val archive = storageManager.getTempPartFile("api24-integrity-test")
        archive.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("model.onnx"))
            zip.write(ByteArray(1_050_000) { 7 })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("tokens.txt"))
            zip.write("token 1\\n".toByteArray())
            zip.closeEntry()
        }

        val baseSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!
        val testSpec = baseSpec.copy(
            archiveType = com.example.domain.ArchiveType.ZIP,
            expectedSizeBytes = archive.length(),
            sha256 = storageManager.computeSha256(archive),
            requiresEspeakData = false
        )

        val installedDir = storageManager.installVerifiedPackage(testSpec, archive).getOrThrow()
        assertTrue(storageManager.verifyInstalledPackage(testSpec, verifyIntegrity = true))
        assertTrue(File(installedDir, ModelStorageManager.INTEGRITY_MANIFEST_FILE).isFile)
        storageManager.deleteModelFiles(testSpec.id)
    }

}

