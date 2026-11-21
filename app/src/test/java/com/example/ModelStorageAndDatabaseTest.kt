package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.domain.ArchiveType
import com.example.domain.BenchmarkCorpus
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.models.ModelCatalog
import com.example.models.ModelDownloadWorker
import com.example.models.ModelRepository
import com.example.models.ModelStorageManager
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelStorageAndDatabaseTest {

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
        db.close()
    }

    @Test
    fun `storage manager verifies sha256 extracts zip and cleans up part file`() = runTest {
        val partFile = storageManager.getTempPartFile("test-zip-model")
        ZipOutputStream(FileOutputStream(partFile)).use { zos ->
            zos.putNextEntry(ZipEntry("nested/phontab"))
            zos.write("PHONTAB_DATA".toByteArray())
            zos.closeEntry()
        }

        val sha = storageManager.computeSha256(partFile)
        val spec = ModelSpec(
            id = "espeak-ng-data",
            displayName = "Test eSpeak Zip",
            language = "fr-FR",
            voiceId = "phonemizer",
            voiceDescriptionAr = "اختبار",
            format = "ZIP",
            engineType = EngineType.PIPER_SHERPA_ONNX,
            version = "1.0",
            downloadUrl = "https://example.com/espeak.zip",
            expectedSizeBytes = partFile.length(),
            sha256 = sha,
            archiveType = ArchiveType.ZIP,
            requiresEspeakData = false,
            licenseName = "GPL-3.0+",
            ownerName = "Test",
            sourceUrl = "https://example.com",
            commercialAllowed = false,
            attributionNotes = ""
        )

        val result = storageManager.installVerifiedPackage(spec, partFile)
        assertTrue(result.isSuccess)
        val installedDir = result.getOrThrow()
        assertTrue(File(installedDir, "phontab").exists())
        assertFalse(partFile.exists())
        assertTrue(storageManager.verifyInstalledPackage(spec))

        // Cleanup
        assertTrue(storageManager.deleteModelFiles(spec.id))
        assertFalse(storageManager.verifyInstalledPackage(spec))
    }

    @Test
    fun `storage manager rejects corrupted sha256 package`() = runTest {
        val badFile = File(storageManager.tempDownloadsRoot, "corrupt.litertlm")
        badFile.writeBytes(ByteArray(128) { 42 })

        val spec = ModelSpec(
            id = "bad-kokoro",
            displayName = "Bad Kokoro",
            language = "fr-FR",
            voiceId = "ff_siwis",
            voiceDescriptionAr = "",
            format = "litertlm",
            engineType = EngineType.KOKORO_LITERT,
            version = "1.0",
            downloadUrl = "https://example.com/model.litertlm",
            expectedSizeBytes = badFile.length(),
            sha256 = "0".repeat(64),
            archiveType = ArchiveType.SINGLE_LITERTLM,
            requiresEspeakData = false,
            licenseName = "Apache-2.0",
            ownerName = "Test",
            sourceUrl = "https://example.com",
            commercialAllowed = true,
            attributionNotes = ""
        )

        val result = storageManager.installVerifiedPackage(spec, badFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("SHA-256") == true)
        assertFalse("Corrupted .part file must be deleted automatically to avoid retry loops", badFile.exists())
    }

    @Test
    fun `storage manager deletes temp file on size mismatch and differentiates archive peak bytes`() = runTest {
        val wrongSizeFile = File(storageManager.tempDownloadsRoot, "wrong_size.litertlm")
        wrongSizeFile.writeBytes(ByteArray(64) { 7 })

        val spec = ModelCatalog.findById("kokoro-82m-gpu-fp16")!!
        val result = storageManager.installVerifiedPackage(spec, wrongSizeFile)
        assertTrue(result.isFailure)
        assertFalse("Size-mismatched .part file must be deleted", wrongSizeFile.exists())

        val kokoroPeak = storageManager.requiredPeakBytes(spec, includeEspeakDependency = false)
        val kokoroResumedPeak = storageManager.requiredPeakBytes(
            spec,
            includeEspeakDependency = false,
            alreadyDownloadedBytes = spec.expectedSizeBytes / 2L
        )
        val piperSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!
        val piperPeak = storageManager.requiredPeakBytes(piperSpec, includeEspeakDependency = true)

        // Worst-case copy fallback reserves remaining download + full copy size + 100 MB safety margin
        assertTrue(kokoroPeak >= spec.expectedSizeBytes * 2L)
        assertTrue(kokoroResumedPeak < kokoroPeak)
        assertTrue(piperPeak > piperSpec.expectedSizeBytes * 2L)
    }

    @Test
    fun `content range header validation strictly enforces offset end and total bytes`() {
        val total = 309_517_248L
        assertTrue(
            ModelDownloadWorker.isValidContentRangeHeader(
                header = "bytes 1000-${total - 1}/$total",
                expectedStart = 1000L,
                expectedTotalBytes = total
            )
        )
        assertTrue(
            ModelDownloadWorker.isValidContentRangeHeader(
                header = "bytes 1000-${total - 1}/*",
                expectedStart = 1000L,
                expectedTotalBytes = total
            )
        )
        // Wrong start offset
        assertFalse(
            ModelDownloadWorker.isValidContentRangeHeader(
                header = "bytes 0-${total - 1}/$total",
                expectedStart = 1000L,
                expectedTotalBytes = total
            )
        )
        // Truncated range end offset
        assertFalse(
            ModelDownloadWorker.isValidContentRangeHeader(
                header = "bytes 1000-${total - 2}/$total",
                expectedStart = 1000L,
                expectedTotalBytes = total
            )
        )
        // Mismatched total length
        assertFalse(
            ModelDownloadWorker.isValidContentRangeHeader(
                header = "bytes 1000-${total - 1}/${total + 5}",
                expectedStart = 1000L,
                expectedTotalBytes = total
            )
        )
        // Malformed / missing headers
        assertFalse(ModelDownloadWorker.isValidContentRangeHeader(null, 1000L, total))
        assertFalse(ModelDownloadWorker.isValidContentRangeHeader("items 1000-2000/2001", 1000L, 2001L))
    }

    @Test
    fun `library repository seeds starter books and supports search and vocabulary`() = runTest {
        val repo = LibraryRepository(
            bookDao = db.bookDao(),
            vocabularyDao = db.vocabularyDao(),
            bookImporter = BookImporter(context)
        )
        repo.seedStarterBooksIfEmpty()

        val books = repo.observeAllBooks().first()
        assertEquals(BenchmarkCorpus.starterBooks.size, books.size)

        val frenchBook = books.first { it.sourceLanguage == "fr" }
        val englishBook = books.first { it.sourceLanguage == "en" }
        val chapters = repo.observeChapters(frenchBook.id).first()
        assertFalse(chapters.isEmpty())

        val searchHits = repo.searchInBook(frenchBook.id, "Bonjour")
        assertFalse(searchHits.isEmpty())

        val enHits = repo.searchInBook(englishBook.id, "Reading")
        assertFalse(enHits.isEmpty())

        repo.saveVocabulary(
            frenchTerm = "bonjour",
            arabicTranslation = "صباح الخير",
            contextSentence = "Bonjour tout le monde.",
            bookTitle = frenchBook.title
        )
        val vocab = repo.observeVocabulary("bonjour").first()
        assertEquals(1, vocab.size)
        assertEquals("صباح الخير", vocab.first().arabicTranslation)
    }

    @Test
    fun `model repository protects active model from deletion`() = runTest {
        val ttsManager = TtsEngineManager(context, storageManager)
        val modelRepo = ModelRepository(
            context = context,
            modelRecordDao = db.modelRecordDao(),
            storageManager = storageManager,
            ttsEngineManager = ttsManager
        )
        val res = modelRepo.deleteInstalledModel(
            modelId = "kokoro-82m-gpu-fp16",
            activeModelId = "kokoro-82m-gpu-fp16"
        )
        assertTrue(res.isFailure)
    }
}
