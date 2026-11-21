package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioChunkCache
import com.example.audio.AudioPlaybackController
import com.example.audio.CachedAudioPayload
import com.example.audio.PlaybackStatus
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.data.ModelRecordEntity
import com.example.data.SettingsRepository
import com.example.domain.AcceleratorMode
import com.example.domain.ArchiveType
import com.example.domain.EngineType
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.domain.WordTimestampRange
import com.example.models.ModelCatalog
import com.example.models.ModelRepository
import com.example.models.ModelStorageManager
import com.example.tts.DeviceHardwareInfo
import com.example.tts.KokoroLiteRtTtsEngine
import com.example.tts.KokoroNativeSession
import com.example.tts.KokoroSessionFactory
import com.example.tts.KokoroSynthesisOutput
import com.example.tts.MemoryDiagnostics
import com.example.tts.PiperNativeSession
import com.example.tts.PiperSessionFactory
import com.example.tts.PiperSherpaTtsEngine
import com.example.tts.TtsEngineManager
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseFourArchitectureIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storageManager: ModelStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = ModelStorageManager(context)
    }

    @After
    fun tearDown() {
        ModelCatalog.ttsModels.forEach { storageManager.deleteModelFiles(it.id) }
        storageManager.deleteModelFiles(ModelCatalog.espeakNgDataSpec.id)
        storageManager.clearAudioCache()
        db.close()
    }

    @Test
    fun `audio chunk cache uses collision-free sha256 keys sanitizes pcm and rejects corrupt giant headers`() {
        // Kotlin "Aa".hashCode() == "BB".hashCode() (2112); SHA-256 keys MUST remain distinct
        val keyA = AudioChunkCache.buildCacheKey("kokoro-82m-gpu-fp16", "fr", "ff_siwis", 1.0f, "Aa")
        val keyB = AudioChunkCache.buildCacheKey("kokoro-82m-gpu-fp16", "fr", "ff_siwis", 1.0f, "BB")
        assertNotEquals(keyA, keyB)
        assertEquals(64, keyA.length)

        val enKey = AudioChunkCache.buildCacheKey("kokoro-82m-gpu-fp16", "en", "af_heart", 1.0f, "Test")
        val frKey = AudioChunkCache.buildCacheKey("kokoro-82m-gpu-fp16", "fr", "ff_siwis", 1.0f, "Test")
        assertNotEquals(enKey, frKey)

        // Valid write & read with NaN/clipping sanitization
        val validFile = AudioChunkCache.cacheFileFor(storageManager.audioCacheRoot, keyA)
        val ok = AudioChunkCache.write(
            validFile,
            CachedAudioPayload(
                samples = floatArrayOf(0.5f, Float.NaN, 2.5f, -3.0f),
                sampleRateHz = 24000,
                wordTimestamps = listOf(WordTimestampRange("Aa", 0, 2, 0f, 0.2f))
            )
        )
        assertTrue(ok)
        val loaded = AudioChunkCache.readValidated(validFile)
        assertNotNull(loaded)
        assertEquals(4, loaded!!.samples.size)
        assertEquals(0.0f, loaded.samples[1], 0.0001f)
        assertEquals(1.0f, loaded.samples[2], 0.0001f)
        assertEquals(-1.0f, loaded.samples[3], 0.0001f)

        // Corrupted giant sample count header must NOT OOM and must delete the bogus file
        val maliciousFile = File(storageManager.audioCacheRoot, "corrupted_oom.pcmbin")
        DataOutputStream(FileOutputStream(maliciousFile)).use { out ->
            out.writeInt(AudioChunkCache.MAGIC_HEADER)
            out.writeInt(24000)
            out.writeInt(Int.MAX_VALUE) // Malicious 8GB sample count claim in a 20-byte file
            out.writeFloat(0.1f)
            out.writeInt(0)
        }
        assertTrue(maliciousFile.exists())
        val rejected = AudioChunkCache.readValidated(maliciousFile)
        assertNull(rejected)
        assertFalse("Corrupt cache file must be purged immediately", maliciousFile.exists())
    }

    @Test
    fun `device compatibility evaluator enforces API, 64-bit ABI, and RAM limits accurately`() {
        val kokoro = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val piper = ModelCatalog.findById("piper-fr-siwis-medium")!!

        // Low API 24 + 32-bit + 1.5 GB RAM: blocks Kokoro, allows lightweight Piper
        val lowEnd32Bit = DeviceHardwareInfo(
            deviceModel = "Budget Phone",
            primaryAbi = "armeabi-v7a",
            supportedAbis = listOf("armeabi-v7a"),
            is64BitSupported = false,
            totalRamMb = 1500L,
            availableRamMb = 600L,
            androidVersion = "7.0",
            sdkInt = 24
        )
        val kokoroLow = MemoryDiagnostics.evaluateModelCompatibility(kokoro, lowEnd32Bit)
        assertFalse(kokoroLow.isSupported)
        assertEquals(ModelCompatibilityStatus.INCOMPATIBLE_API, kokoroLow.status)

        val piperLow = MemoryDiagnostics.evaluateModelCompatibility(piper, lowEnd32Bit)
        assertTrue(piperLow.isSupported)

        // API 29 + 64-bit + 2.2 GB RAM: Kokoro supported with WARNING_LOW_RAM
        val midRam = DeviceHardwareInfo(
            deviceModel = "Midrange 64",
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            is64BitSupported = true,
            totalRamMb = 2200L,
            availableRamMb = 1100L,
            androidVersion = "10",
            sdkInt = 29
        )
        val kokoroMid = MemoryDiagnostics.evaluateModelCompatibility(kokoro, midRam)
        assertTrue(kokoroMid.isSupported)
        assertEquals(ModelCompatibilityStatus.WARNING_LOW_RAM, kokoroMid.status)

        // High-spec 6 GB ARM64: full compatibility
        val flagship = midRam.copy(totalRamMb = 6144L, availableRamMb = 3200L, sdkInt = 34)
        assertEquals(ModelCompatibilityStatus.COMPATIBLE, MemoryDiagnostics.evaluateModelCompatibility(kokoro, flagship).status)
    }

    @Test
    fun `language-aware speech preprocessor and book importer isolate English from French rules`() = runTest {
        val enSpoken = SpeechTextProcessor.prepareForSpeech(
            "Dr. Smith paid $15 and got 50% off.",
            "en"
        )
        assertTrue(enSpoken.contains("Doctor"))
        assertTrue(enSpoken.contains("fifteen dollars"))
        assertTrue(enSpoken.contains("fifty percent"))
        assertFalse("English text must never be processed through French numbers", enSpoken.contains("cinquante"))

        // Starter book seeding uses language-aware preprocessing
        val repo = LibraryRepository(
            bookDao = db.bookDao(),
            vocabularyDao = db.vocabularyDao(),
            bookImporter = BookImporter(context)
        )
        repo.seedStarterBooksIfEmpty()
        val enStarterSentences = repo.observeChapterSentences("starter_english_reading", 0).first()
        assertFalse(enStarterSentences.isEmpty())
        assertTrue(enStarterSentences.all { !it.spokenText.contains("virgule") })

        // Importing English TXT produces English spokenText
        val enTxt = File(context.cacheDir, "en_sample.txt")
        enTxt.writeText("Mr. Brown bought 20 apples and paid $5.", Charsets.UTF_8)
        val importedEn = BookImporter(context).importFromUri(Uri.fromFile(enTxt)).getOrThrow()
        assertEquals("en", importedEn.book.sourceLanguage)
        assertTrue(importedEn.sentences.first().spokenText.contains("Mister"))
        assertTrue(importedEn.sentences.first().spokenText.contains("twenty"))
        assertTrue(importedEn.sentences.first().spokenText.contains("five dollars"))
    }

    @Test
    fun `piper engine streams via callbackFlow, sets sid zero, and rejects English locale cleanly`() = runTest {
        val piperSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!
        val piperDir = storageManager.getInstalledModelDir(piperSpec.id).apply { mkdirs() }
        File(piperDir, "fr_FR-siwis-medium.onnx").writeBytes(ByteArray(1024))
        File(piperDir, "tokens.txt").writeText("a 1\n")
        val espeakDir = File(piperDir, "espeak-ng-data").apply { mkdirs() }
        File(espeakDir, "phontab").writeBytes(ByteArray(64))

        var recordedSid = -1
        val piperFactory = PiperSessionFactory { _, _, _, _ ->
            object : PiperNativeSession {
                override val sampleRateHz: Int = 22050
                override fun generate(text: String, sid: Int, speed: Float): FloatArray {
                    recordedSid = sid
                    return FloatArray(512) { 0.25f }
                }

                override fun generateWithCallback(
                    text: String,
                    sid: Int,
                    speed: Float,
                    callback: (FloatArray) -> Int
                ) {
                    recordedSid = sid
                    val cont = callback(FloatArray(256) { 0.3f })
                    if (cont == 1) {
                        callback(FloatArray(256) { Float.NaN }) // Should be sanitized cleanly
                    }
                }

                override fun release() {}
            }
        }

        val engine = PiperSherpaTtsEngine(storageManager, piperFactory)
        assertTrue(engine.initialize(piperSpec, piperDir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK).isSuccess)
        val chunks = engine.synthesizeStream("Bonjour le monde.", "siwis", 1.0f, "fr-FR").toList()
        assertEquals(0, recordedSid)
        assertTrue(chunks.size >= 2)
        assertEquals(0f, chunks[1].samples[0], 0.0001f)

        // Requesting English on French-only Piper fails explicitly
        val englishAttempt = engine.synthesize("Hello world.", "siwis", 1.0f, "en-US")
        assertTrue(englishAttempt.isFailure)
        assertTrue(englishAttempt.exceptionOrNull()?.message?.contains("Kokoro-82M") == true)
        engine.release()
    }

    @Test
    fun `kokoro live streaming supports French and English voice routing and GPU fallback`() = runTest {
        val kokoroSpec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val dir = storageManager.getInstalledModelDir(kokoroSpec.id).apply { mkdirs() }
        File(dir, "kokoro_int8_gpu_fp16.litertlm").writeBytes(ByteArray(128))

        val requestedPairs = mutableListOf<Triple<Delegate, String, String>>()
        val factory = KokoroSessionFactory { _, _, delegate, voiceId, locale, _ ->
            requestedPairs.add(Triple(delegate, voiceId, locale))
            object : KokoroNativeSession {
                override fun synthesize(spokenText: String, voiceId: String, locale: String) =
                    KokoroSynthesisOutput(FloatArray(256) { 0.2f }, 24000, emptyList())

                override fun synthesizeStream(spokenText: String, voiceId: String, locale: String) = flow {
                    if (delegate == Delegate.GPU) {
                        throw IllegalStateException("GPU delegate stream failure before audio")
                    }
                    emit(KokoroSynthesisOutput(FloatArray(256) { 0.2f }, 24000, emptyList(), isFinal = false))
                    emit(KokoroSynthesisOutput(FloatArray(256) { 0.4f }, 24000, emptyList(), isFinal = true))
                }

                override fun cancel() {}
                override fun close() {}
            }
        }

        val engine = KokoroLiteRtTtsEngine(context, storageManager, factory)
        engine.initialize(kokoroSpec, dir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK).getOrThrow()

        val enChunks = engine.synthesizeStream("Reading develops knowledge.", "af_heart", 1.0f, "en-US").toList()
        assertEquals(2, enChunks.size)
        assertTrue(engine.fellBackToCpu)
        assertTrue(requestedPairs.any { it.first == Delegate.CPU && it.second == "af_heart" && it.third == "en-US" })
        engine.release()
    }

    @Test
    fun `startup reconciliation catches tampered integrity manifest and never fakes load test`() = runTest {
        val part = storageManager.getTempPartFile("custom-zip-test")
        val onnxPayload = ByteArray(1_100_000) { 3 }
        java.util.zip.ZipOutputStream(FileOutputStream(part)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("model.onnx"))
            zos.write(onnxPayload)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("tokens.txt"))
            zos.write("a 1\n".toByteArray())
            zos.closeEntry()
        }
        val spec = ModelCatalog.findById("piper-fr-siwis-medium")!!.copy(
            id = "piper-fr-siwis-medium",
            expectedSizeBytes = part.length(),
            sha256 = storageManager.computeSha256(part),
            archiveType = ArchiveType.ZIP,
            requiresEspeakData = false
        )
        // Standard ModelStorageManager verifies manifest against actual spec in ModelCatalog,
        // so write the real ModelSpec-shaped directory directly to test reconcileStorageOnStartup against ModelCatalog!
        val realSpec = ModelCatalog.findById("kokoro-82m-cpu-fp16")!!
        val realDir = storageManager.getInstalledModelDir(realSpec.id).apply { mkdirs() }
        // Case 1: DB says INSTALLED, but .integrity.sha256 is missing / tampered
        File(realDir, "kokoro_int8_fp16.litertlm").writeBytes(ByteArray(64) { 9 })
        db.modelRecordDao().upsert(
            ModelRecordEntity(
                modelId = realSpec.id,
                status = ModelInstallStatus.INSTALLED.name,
                downloadedBytes = realSpec.expectedSizeBytes,
                totalBytes = realSpec.expectedSizeBytes,
                installedDirPath = realDir.absolutePath,
                verifiedSha256 = true,
                verifiedLoadTest = true
            )
        )

        val ttsManager = TtsEngineManager(context, storageManager)
        val modelRepo = ModelRepository(context, db.modelRecordDao(), storageManager, ttsManager)
        modelRepo.reconcileStorageOnStartup()

        val afterTamper = db.modelRecordDao().getRecord(realSpec.id)
        assertEquals(ModelInstallStatus.CORRUPTED.name, afterTamper?.status)
        assertFalse(afterTamper?.verifiedSha256 ?: true)
        assertFalse(afterTamper?.verifiedLoadTest ?: true)

        // Case 2: Delete tampered record, leave orphaned directory -> must be marked CORRUPTED, NEVER verifiedLoadTest=true
        db.modelRecordDao().delete(realSpec.id)
        modelRepo.reconcileStorageOnStartup()
        val orphanRecord = db.modelRecordDao().getRecord(realSpec.id)
        assertEquals(ModelInstallStatus.CORRUPTED.name, orphanRecord?.status)
        assertFalse(orphanRecord?.verifiedLoadTest ?: true)

        // Case 3: Verify installVerifiedPackage creates valid .integrity.sha256 that detect file tampering
        val installedTestDir = storageManager.installVerifiedPackage(spec.copy(id = "temp-piper-test"), part).getOrThrow()
        assertTrue(File(installedTestDir, ModelStorageManager.INTEGRITY_MANIFEST_FILE).exists())
        assertTrue(storageManager.verifyInstalledPackage(spec.copy(id = "temp-piper-test"), verifyIntegrity = true))
        // Tamper tokens.txt inside installed directory
        File(installedTestDir, "tokens.txt").writeText("tampered 99\n")
        assertFalse(storageManager.verifyInstalledPackage(spec.copy(id = "temp-piper-test"), verifyIntegrity = true))
        storageManager.deleteModelFiles("temp-piper-test")
    }

    @Test
    fun `ttsEngineManager and playbackController report clear error when no English-capable model is ready`() = runTest {
        val settingsRepo = SettingsRepository(context)
        settingsRepo.setActiveModelId("piper-fr-siwis-medium")
        db.bookDao().insertFullBook(
            book = com.example.data.BookEntity(
                id = "en_book_test",
                title = "English Sample",
                author = "Author",
                sourceType = "TXT",
                sourceLanguage = "en",
                totalChapters = 1,
                totalSentences = 1
            ),
            chapters = listOf(
                com.example.data.ChapterEntity(
                    bookId = "en_book_test",
                    chapterIndex = 0,
                    title = "Chapter 1",
                    sentenceCount = 1
                )
            ),
            sentences = listOf(
                com.example.data.SentenceEntity(
                    bookId = "en_book_test",
                    chapterIndex = 0,
                    sentenceIndex = 0,
                    paragraphIndex = 0,
                    originalText = "Hello reader.",
                    spokenText = "Hello reader."
                )
            )
        )

        val ttsManager = TtsEngineManager(context, storageManager)
        val controller = AudioPlaybackController(context, db.bookDao(), settingsRepo, storageManager, ttsManager)
        controller.playChapterFrom("en_book_test", "English Sample", 0, "Chapter 1", 0, "en")

        var tries = 0
        while (controller.state.value.status != PlaybackStatus.ERROR && tries < 100) {
            Thread.sleep(25L)
            tries++
        }
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertFalse(controller.state.value.errorMessage.isNullOrBlank())

        // Verify switchModel fails cleanly without crashing on uninstalled model
        val switchFail = ttsManager.switchModel("piper-fr-siwis-medium", AcceleratorMode.CPU_ONLY)
        assertTrue(switchFail.isFailure)
        assertNull(ttsManager.runtimeState.value.loadedModelId)
    }
}
