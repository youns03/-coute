package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import com.example.data.TranslationJobEntity
import com.example.translation.DocumentChunker
import com.example.translation.ExportMode
import com.example.translation.FileTranslationRepository
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationModelCatalog
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
class TranslationAndDocumentChunkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `language detector distinguishes French and English accurately`() {
        val fr = BookImporter.detectSourceLanguage(
            "La lecture est essentielle pour développer les connaissances dans la vie quotidienne.",
            opfLangHint = null
        )
        val en = BookImporter.detectSourceLanguage(
            "Reading in a foreign language becomes easier when you understand the ideas in context.",
            opfLangHint = null
        )
        assertEquals(SourceLanguage.FRENCH, fr)
        assertEquals(SourceLanguage.ENGLISH, en)
        assertEquals(SourceLanguage.ENGLISH, BookImporter.detectSourceLanguage("Bonjour", "en-US"))
    }

    @Test
    fun `book importer gives honest unsupported error for pdf files`() = runTest {
        val importer = BookImporter(context)
        val pdfFile = File(context.cacheDir, "sample.pdf").apply { writeText("%PDF-1.4 dummy") }
        val res = importer.parseDocumentFromUri(Uri.fromFile(pdfFile))
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("PDF") == true)
        pdfFile.delete()
    }

    @Test
    fun `book importer parses streaming EPUB via ZipFile ignoring binary image assets`() = runTest {
        val epubFile = File(context.cacheDir, "test_streaming.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zos ->
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
                    <dc:title>Le Jardin Secret</dc:title>
                    <dc:creator>Auteur Test</dc:creator>
                    <dc:language>fr</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="images/cover.png" media-type="image/png"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body>
                    <h1>Chapitre Un</h1>
                    <p>Bonjour tout le monde dans le jardin secret.</p>
                  </body>
                </html>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/images/cover.png"))
            zos.write(ByteArray(4096) { 0x7F })
            zos.closeEntry()
        }

        val importer = BookImporter(context)
        val parsed = importer.parseDocumentFromUri(Uri.fromFile(epubFile)).getOrThrow()
        assertEquals("EPUB", parsed.sourceType)
        assertEquals("Le Jardin Secret", parsed.title)
        assertEquals("Auteur Test", parsed.author)
        assertEquals(SourceLanguage.FRENCH, parsed.detectedLanguage)
        assertEquals(1, parsed.sections.size)
        assertTrue(parsed.sections[0].body.contains("Bonjour tout le monde"))
        epubFile.delete()
    }

    @Test
    fun `document chunker preserves headings splits oversized paragraphs and exports cleanly`() = runTest {
        val longParagraph = buildString {
            repeat(18) { i ->
                append("La phrase numéro $i explique une idée importante pour les apprenants. ")
            }
        }.trim()

        val doc = ParsedSourceDocument(
            fileName = "guide.txt",
            title = "Guide de Lecture",
            author = "TXT",
            sourceType = "TXT",
            detectedLanguage = SourceLanguage.FRENCH,
            sections = listOf(
                ExtractedDocumentSection("Chapitre 1", "Premier paragraphe court.\n\n$longParagraph")
            )
        )

        val chunks = DocumentChunker.buildChunks("job_1", doc, maxCharsPerChunk = 300)
        assertTrue(chunks.size >= 4)
        assertTrue(chunks.first().isHeading)
        assertEquals("Chapitre 1", chunks.first().originalText)

        // All body chunks respect max chunk size
        chunks.filter { !it.isHeading }.forEach { ch ->
            assertTrue(ch.originalText.length <= 300)
        }

        val simulatedTranslated = chunks.map { ch ->
            ch.copy(translatedText = "ترجمة: ${ch.chunkIndex}", isCompleted = true)
        }

        val arabicOnly = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = simulatedTranslated,
            mode = ExportMode.ARABIC_COMPLETED_ONLY
        )
        assertTrue(arabicOnly.contains("## ترجمة: 0"))
        assertFalse(arabicOnly.contains("Premier paragraphe court."))

        val bilingual = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = simulatedTranslated,
            mode = ExportMode.BILINGUAL_COMPLETED_ONLY
        )
        assertTrue(bilingual.contains("Premier paragraphe court."))
        assertTrue(bilingual.contains("← ترجمة: 1"))

        val outFile = File(context.cacheDir, "exported_ar.txt")
        val exportRes = DocumentChunker.exportToUri(context, Uri.fromFile(outFile), arabicOnly)
        assertTrue(exportRes.isSuccess)
        assertTrue(outFile.readText(Charsets.UTF_8).contains("Guide de Lecture"))
        outFile.delete()
    }

    @Test
    fun `document chunker strictly enforces maxCharsPerChunk even without punctuation`() {
        val noPunctuationWords = (1..120).joinToString(" ") { "mot$it" }
        val unbrokenToken = "x".repeat(260)
        val doc = ParsedSourceDocument(
            fileName = "unpunctuated.txt",
            title = "Unpunctuated",
            author = "TXT",
            sourceType = "TXT",
            detectedLanguage = SourceLanguage.FRENCH,
            sections = listOf(
                ExtractedDocumentSection("", "$noPunctuationWords\n\n$unbrokenToken")
            )
        )

        val chunks = DocumentChunker.buildChunks("job_strict", doc, maxCharsPerChunk = 120)
        assertTrue(chunks.size >= 8)
        chunks.forEach { chunk ->
            assertTrue(
                "Chunk exceeded 120 chars: ${chunk.originalText.length}",
                chunk.originalText.length <= 120
            )
        }
    }

    @Test
    fun `partial export completed-only omits placeholders while draft includes placeholders`() {
        val doc = ParsedSourceDocument(
            fileName = "partial.txt",
            title = "Partial Book",
            author = "TXT",
            sourceType = "TXT",
            detectedLanguage = SourceLanguage.FRENCH,
            sections = listOf(
                ExtractedDocumentSection("", "Première partie.\n\nDeuxième partie.\n\nTroisième partie en attente.")
            )
        )
        val base = DocumentChunker.buildChunks("job_partial", doc)
        val partialChunks = listOf(
            base[0].copy(translatedText = "الجزء الأول.", isCompleted = true),
            base[1].copy(translatedText = "الجزء الثاني.", isCompleted = true),
            base[2].copy(translatedText = null, isCompleted = false)
        )

        val completedOnlyAr = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = partialChunks,
            mode = ExportMode.ARABIC_COMPLETED_ONLY
        )
        assertTrue(completedOnlyAr.contains("الجزء الأول."))
        assertTrue(completedOnlyAr.contains("الجزء الثاني."))
        assertFalse(completedOnlyAr.contains("[لم يُترجم هذا الجزء بعد]"))

        val completedOnlyBi = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = partialChunks,
            mode = ExportMode.BILINGUAL_COMPLETED_ONLY
        )
        assertFalse(completedOnlyBi.contains("Troisième partie en attente."))
        assertFalse(completedOnlyBi.contains("[لم يُترجم هذا الجزء بعد]"))

        val draft = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = partialChunks,
            mode = ExportMode.BILINGUAL_WITH_PLACEHOLDERS
        )
        assertTrue(draft.contains("Troisième partie en attente."))
        assertTrue(draft.contains("[لم يُترجم هذا الجزء بعد]"))
    }

    @Test
    fun `translation job dao persists partial chunks and guards language change until reset`() = runTest {
        val dao = db.translationJobDao()
        val repo = FileTranslationRepository(context, dao, BookImporter(context))

        val job = TranslationJobEntity(
            id = "job_resume_1",
            title = "Long Article",
            originalFileName = "article.txt",
            sourceType = "TXT",
            sourceLanguage = "en",
            status = "RUNNING",
            totalChunks = 3,
            completedChunks = 0
        )
        val doc = ParsedSourceDocument(
            fileName = "article.txt",
            title = "Long Article",
            author = "TXT",
            sourceType = "TXT",
            detectedLanguage = SourceLanguage.ENGLISH,
            sections = listOf(
                ExtractedDocumentSection("", "Paragraph one.\n\nParagraph two.\n\nParagraph three.")
            )
        )
        val chunks = DocumentChunker.buildChunks(job.id, doc)
        dao.insertJobWithChunks(job.copy(totalChunks = chunks.size), chunks)

        val inserted = dao.getAllChunks(job.id)
        assertEquals(3, inserted.size)

        // Complete first chunk
        dao.markChunkTranslated(inserted[0].id, "الفقرة الأولى.")
        dao.updateJobProgress(job.id, 1, "RUNNING", null)

        // Simulate app restart reconciliation
        dao.resetRunningJobsToPaused()
        assertEquals("PAUSED", dao.getJob(job.id)?.status)

        // Direct non-reset language switch is blocked when chunks are already translated
        assertFalse(repo.updateJobSourceLanguageIfEmpty(job.id, SourceLanguage.FRENCH))
        assertEquals("en", dao.getJob(job.id)?.sourceLanguage)

        // Full reset clears completed chunks, bumps jobEpoch, and switches sourceLanguage cleanly
        repo.resetAndChangeJobSourceLanguage(job.id, SourceLanguage.FRENCH)
        val resetJob = dao.getJob(job.id)!!
        assertEquals("fr", resetJob.sourceLanguage)
        assertEquals(0, resetJob.completedChunks)
        assertEquals("PENDING", resetJob.status)
        assertEquals(1, resetJob.jobEpoch)
        assertEquals(3, dao.getPendingChunks(job.id).size)

        // Stale worker holding old epoch 0 must be rejected without touching reset chunks
        val staleChunkRowsUpdated = dao.markChunkTranslatedIfEpochMatches(
            chunkId = inserted[1].id,
            jobId = job.id,
            expectedEpoch = 0,
            translatedText = "STALE_OVERWRITE"
        )
        assertEquals(0, staleChunkRowsUpdated)
        val staleJobRowsUpdated = dao.updateJobProgressIfEpochMatches(
            jobId = job.id,
            expectedEpoch = 0,
            completedChunks = 2,
            status = "COMPLETED",
            lastError = null
        )
        assertEquals(0, staleJobRowsUpdated)
        assertEquals("PENDING", dao.getJob(job.id)?.status)
        assertEquals(3, dao.getPendingChunks(job.id).size)
    }

    @Test
    fun `document chunker honors caller maxCharsPerChunk even below 40`() {
        val doc = ParsedSourceDocument(
            fileName = "tiny_limit.txt",
            title = "Tiny",
            author = "TXT",
            sourceType = "TXT",
            detectedLanguage = SourceLanguage.FRENCH,
            sections = listOf(
                ExtractedDocumentSection("", "Bonjour tout le monde voici un texte assez long sans retour.")
            )
        )
        val chunks = DocumentChunker.buildChunks("job_tiny", doc, maxCharsPerChunk = 15)
        assertTrue(chunks.size >= 4)
        chunks.forEach { chunk ->
            assertTrue(
                "Chunk exceeded 15 chars: '${chunk.originalText}' (${chunk.originalText.length})",
                chunk.originalText.length <= 15
            )
        }
    }

    @Test
    fun `book importer enforces bounded byte and line limits on TXT without knowing URI size`() = runTest {
        // Test byte bound with unknown OpenableColumns.SIZE via small internal byte limit
        val largeTxt = File(context.cacheDir, "oversized_txt.txt").apply {
            writeText("Bonjour " + "a".repeat(2048))
        }
        val tightByteImporter = BookImporter(
            context = context,
            maxImportFileBytes = 512L,
            maxExtractedTextChars = 10_000,
            maxLineChars = 256
        )
        val byteRes = tightByteImporter.parseDocumentFromUri(Uri.fromFile(largeTxt))
        assertTrue(byteRes.isFailure)
        largeTxt.delete()

        // Test single giant line without newlines does not trigger OOM and parses cleanly within caps
        val singleLineNoBreakFile = File(context.cacheDir, "single_giant_line.txt").apply {
            writeText((1..40).joinToString(" ") { "bonjour$it" })
        }
        val lineSegImporter = BookImporter(
            context = context,
            maxImportFileBytes = 1024L * 1024L,
            maxExtractedTextChars = 10_000,
            maxLineChars = 40
        )
        val parsed = lineSegImporter.parseDocumentFromUri(Uri.fromFile(singleLineNoBreakFile)).getOrThrow()
        assertEquals("TXT", parsed.sourceType)
        assertTrue(parsed.sections.first().body.contains("bonjour1"))
        assertTrue(parsed.sections.first().body.contains("bonjour40"))
        singleLineNoBreakFile.delete()
    }

    @Test
    fun `book importer rejects EPUB whose cumulative uncompressed spine exceeds cap`() = runTest {
        val epubFile = File(context.cacheDir, "cumulative_cap.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("content.opf"))
            zos.write(
                """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            val body = "<p>${"Bonjour tout le monde. ".repeat(40)}</p>"
            zos.putNextEntry(ZipEntry("c1.xhtml"))
            zos.write("<html><body>$body</body></html>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("c2.xhtml"))
            zos.write("<html><body>$body</body></html>".toByteArray())
            zos.closeEntry()
        }

        val cappedImporter = BookImporter(
            context = context,
            maxImportFileBytes = 5 * 1024 * 1024L,
            maxExtractedTextChars = 2_000_000,
            maxLineChars = 16_384,
            maxEpubTotalUncompressedBytes = 1200L
        )
        val res = cappedImporter.parseDocumentFromUri(Uri.fromFile(epubFile))
        assertTrue(res.isFailure)
        epubFile.delete()
    }

    @Test
    fun `phase 1 translation catalog enables only FR to AR and EN to AR`() {
        assertEquals(2, TranslationDirection.entries.size)
        val phase1Opus = TranslationModelCatalog.opusReferenceMatrix.filter { it.includedInPhase1 }
        assertEquals(2, phase1Opus.size)
        assertEquals(listOf("opus-mt-fr-ar", "opus-mt-en-ar"), phase1Opus.map { it.modelId })
    }
}
