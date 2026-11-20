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
            mode = ExportMode.ARABIC_ONLY
        )
        assertTrue(arabicOnly.contains("## ترجمة: 0"))
        assertFalse(arabicOnly.contains("Premier paragraphe court."))

        val bilingual = DocumentChunker.formatForExport(
            title = doc.title,
            direction = TranslationDirection.FR_TO_AR,
            chunks = simulatedTranslated,
            mode = ExportMode.BILINGUAL
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
    fun `translation job dao persists partial chunks supports pause reconcile and resume`() = runTest {
        val dao = db.translationJobDao()
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

        // Only 2 pending chunks remain
        val pending = dao.getPendingChunks(job.id)
        assertEquals(2, pending.size)
        assertEquals("Paragraph two.", pending[0].originalText)
    }

    @Test
    fun `phase 1 translation catalog enables only FR to AR and EN to AR`() {
        assertEquals(2, TranslationDirection.entries.size)
        val phase1Opus = TranslationModelCatalog.opusReferenceMatrix.filter { it.includedInPhase1 }
        assertEquals(2, phase1Opus.size)
        assertEquals(listOf("opus-mt-fr-ar", "opus-mt-en-ar"), phase1Opus.map { it.modelId })
    }
}
