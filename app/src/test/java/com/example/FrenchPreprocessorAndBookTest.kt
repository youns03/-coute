package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.BookImporter
import com.example.domain.FrenchTextPreprocessor
import com.example.domain.SpeechTextProcessor
import com.example.models.ModelCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrenchPreprocessorAndBookTest {

    @Test
    fun `preprocessor handles years decimals currencies abbreviations and preserves original`() {
        val original = "En 2026, M. Dupont paie 1 250 € et 3,75 € pour Mme. Claire, etc."
        val spoken = FrenchTextPreprocessor.prepareForSpeech(original)

        assertTrue(spoken.contains("deux mille vingt-six"))
        assertTrue(spoken.contains("Monsieur"))
        assertTrue(spoken.contains("mille deux cent cinquante euros"))
        assertTrue(spoken.contains("trois virgule soixante-quinze euros"))
        assertTrue(spoken.contains("Madame"))
        assertTrue(spoken.contains("et cætera"))
        // Original text must remain untouched
        assertTrue(original.contains("2026"))
        assertTrue(original.contains("1 250 €"))
    }

    @Test
    fun `sentence splitter does not break on abbreviations or decimals`() {
        val text = "M. Bernard achète 3,75 kg de pommes. Pourquoi êtes-vous ici ? Aujourd'hui, il fait beau."
        val sentences = FrenchTextPreprocessor.splitAndPreprocess(text)

        assertEquals(3, sentences.size)
        assertEquals("M. Bernard achète 3,75 kg de pommes.", sentences[0].originalText)
        assertEquals("Pourquoi êtes-vous ici ?", sentences[1].originalText)
        assertEquals("Aujourd'hui, il fait beau.", sentences[2].originalText)
    }

    @Test
    fun `long unpunctuated source is split into readable translation paragraphs without losing words`() {
        val source = (1..180).joinToString(" ") { "mot$it" }
        val processed = FrenchTextPreprocessor.splitAndPreprocess(source)
        val grouped = processed.groupBy { it.paragraphIndex }.toSortedMap()

        assertTrue("A long run-on text should become multiple reader paragraphs", grouped.size > 1)
        grouped.values.forEach { sentences ->
            val paragraphText = sentences.joinToString(" ") { it.originalText }
            assertTrue("Reader paragraph exceeded its readability bound", paragraphText.length <= 430)
        }
        assertEquals(source, processed.joinToString(" ") { it.originalText })
    }

    @Test
    fun `long comma-separated source prefers clause boundaries when creating paragraphs`() {
        val source = (1..110).joinToString(", ") { "clause$it" }
        val processed = FrenchTextPreprocessor.splitAndPreprocess(source)
        val grouped = processed.groupBy { it.paragraphIndex }

        assertTrue(grouped.size > 1)
        grouped.values.forEach { sentences ->
            assertTrue(sentences.joinToString(" ") { it.originalText }.length <= 430)
        }
        val firstParagraphText = grouped.toSortedMap().values.first().joinToString(" ") { it.originalText }
        assertTrue(
            "When a long clause has commas, paragraph boundaries should prefer punctuation",
            firstParagraphText.endsWith(",") || firstParagraphText.endsWith(";") || firstParagraphText.endsWith(":")
        )
        assertEquals(source, processed.joinToString(" ") { it.originalText })
    }

    @Test
    fun `explicit blank lines remain separate reader paragraphs`() {
        val source = "First paragraph has one sentence.\\n\\nSecond paragraph has another sentence."
        val processed = FrenchTextPreprocessor.splitAndPreprocess(source)
        assertEquals(2, processed.map { it.paragraphIndex }.distinct().size)
        assertEquals(
            listOf("First paragraph has one sentence.", "Second paragraph has another sentence."),
            processed.map { it.originalText }
        )
    }

    @Test
    fun `English long paragraphs use the same smart reader segmentation`() {
        val source = (1..170).joinToString(" ") { "word$it" }
        val processed = SpeechTextProcessor.splitAndPreprocess(source, "en")
        assertTrue(processed.map { it.paragraphIndex }.distinct().size > 1)
        assertEquals(source, processed.joinToString(" ") { it.originalText })
    }

    @Test
    fun `model catalog has valid sha256 sizes and licenses`() {
        assertTrue(ModelCatalog.ttsModels.size >= 4)
        ModelCatalog.ttsModels.forEach { spec ->
            assertTrue(spec.expectedSizeBytes > 10_000_000L)
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.downloadUrl.startsWith("https://"))
            assertTrue(spec.licenseName.isNotBlank())
        }
        assertFalse(ModelCatalog.licenseLedger.isEmpty())
    }

    @Test
    fun `book importer parses utf8 txt and epub zip cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val importer = BookImporter(context)

        // Test UTF-8 TXT with BOM and French accents
        val txtFile = File(context.cacheDir, "test_livre.txt")
        val txtContent = "\uFEFFBonjour les amis !\n\nAujourd'hui, nous lisons à Paris pour 15 €."
        txtFile.writeText(txtContent, Charsets.UTF_8)
        val txtRes = importer.importFromUri(Uri.fromFile(txtFile))
        assertTrue(txtRes.isSuccess)
        val txtPayload = txtRes.getOrThrow()
        assertEquals("TXT", txtPayload.book.sourceType)
        assertEquals(2, txtPayload.sentences.size)
        assertEquals("Bonjour les amis !", txtPayload.sentences[0].originalText)

        // Test real EPUB ZIP structure
        val epubFile = File(context.cacheDir, "test_livre.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Le Petit Test</dc:title><dc:creator>Auteur Français</dc:creator></metadata><manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>""".toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<html><body><h1>Chapitre Un</h1><p>Qu&rsquo;est-ce que vous faites aujourd&rsquo;hui ?</p><p>Les amis sont arriv&eacute;s.</p></body></html>""".toByteArray()
            )
            zos.closeEntry()
        }

        val epubRes = importer.importFromUri(Uri.fromFile(epubFile))
        assertTrue(epubRes.isSuccess)
        val epubPayload = epubRes.getOrThrow()
        assertEquals("Le Petit Test", epubPayload.book.title)
        assertEquals("EPUB", epubPayload.book.sourceType)
        assertTrue(epubPayload.sentences.any { it.originalText.contains("aujourd’hui ?") || it.originalText.contains("aujourd'hui ?") })
        assertTrue(epubPayload.sentences.any { it.originalText.contains("arrivés.") })
    }
}
