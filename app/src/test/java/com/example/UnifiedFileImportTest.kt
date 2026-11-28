package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.FormatDetector
import com.example.data.importengine.PdfDocumentParser
import com.example.data.importengine.PdfExtractionMode
import com.example.data.importengine.PdfPageOcrEngine
import com.example.data.importengine.SupportedImportFormat
import com.example.translation.FileTranslationRepository
import com.example.translation.SourceLanguage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedFileImportTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "unified_import_tests_${System.nanoTime()}").apply { mkdirs() }
        dbFile = File(tempDir, "ecoute_test.db")
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tempDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // 1. FormatDetector & Rejection of Unsupported / Masquerading Binaries
    // -------------------------------------------------------------------------

    @Test
    fun `format detector rejects generic zip legacy doc and png even if misnamed`() {
        val zipFile = File(tempDir, "archive.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val zipRes = FormatDetector.detectFromFile(zipFile, "archive.zip", "application/zip")
        assertEquals(null, zipRes.format)
        assertTrue(zipRes.rejectionReasonAr?.contains("zip", ignoreCase = true) == true)

        val legacyDoc = File(tempDir, "legacy.doc").apply {
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        }
        val docRes = FormatDetector.detectFromFile(legacyDoc, "legacy.doc", "application/msword")
        assertEquals(null, docRes.format)
        assertTrue(docRes.rejectionReasonAr?.contains("DOCX") == true)

        val fakeTxtWithPngMagic = File(tempDir, "fake.txt").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        val pngRes = FormatDetector.detectFromFile(fakeTxtWithPngMagic, "fake.txt", "text/plain")
        assertEquals(null, pngRes.format)
        assertNotNull(pngRes.rejectionReasonAr)
    }

    // -------------------------------------------------------------------------
    // 2. Plain Text Encodings (UTF-8 BOM, UTF-16LE/BE, CP1256, CP1252, Large)
    // -------------------------------------------------------------------------

    @Test
    fun `txt parser decodes UTF-8 BOM UTF-16LE UTF-16BE Windows-1256 and Windows-1252 accurately`() = runTest {
        val importer = BookImporter(context)

        // UTF-8 with BOM (Arabic + French)
        val utf8BomFile = File(tempDir, "utf8_bom.txt")
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "مرحباً بكم في تطبيق إيكوت للقراءة والاستماع. هذه جملة عربية واضحة.".toByteArray(Charsets.UTF_8)
        utf8BomFile.writeBytes(utf8BomBytes)
        val utf8Doc = importer.parseDocumentFromUri(Uri.fromFile(utf8BomFile)).getOrThrow()
        assertEquals("TXT", utf8Doc.sourceType)
        assertEquals("ar", utf8Doc.detectedLanguageCode)
        assertTrue(utf8Doc.sections.first().body.startsWith("مرحباً"))

        // UTF-16LE with BOM
        val utf16LeFile = File(tempDir, "utf16le.txt")
        val leText = "La lecture attentive enrichit le vocabulaire français chaque jour."
        utf16LeFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + leText.toByteArray(Charsets.UTF_16LE))
        val leDoc = importer.parseDocumentFromUri(Uri.fromFile(utf16LeFile)).getOrThrow()
        assertEquals("fr", leDoc.detectedLanguageCode)
        assertTrue(leDoc.sections.first().body.contains("vocabulaire français"))

        // UTF-16BE with BOM
        val utf16BeFile = File(tempDir, "utf16be.txt")
        val beText = "Reading complex books offline improves comprehension and listening skills."
        utf16BeFile.writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + beText.toByteArray(Charsets.UTF_16BE))
        val beDoc = importer.parseDocumentFromUri(Uri.fromFile(utf16BeFile)).getOrThrow()
        assertEquals("en", beDoc.detectedLanguageCode)
        assertTrue(beDoc.sections.first().body.contains("comprehension"))

        // Windows-1256 (Arabic without BOM)
        val cp1256File = File(tempDir, "arabic_1256.txt")
        val arSample = "إن القراءة المتواصلة تفتح آفاق المعرفة وتنمي الفكر السليم لدى القارئ العربي في كل مكان."
        cp1256File.writeBytes(arSample.toByteArray(Charset.forName("windows-1256")))
        val cp1256Doc = importer.parseDocumentFromUri(Uri.fromFile(cp1256File)).getOrThrow()
        assertEquals("ar", cp1256Doc.detectedLanguageCode)
        assertTrue(cp1256Doc.sections.first().body.contains("القراءة المتواصلة"))

        // Windows-1252 (French without BOM)
        val cp1252File = File(tempDir, "french_1252.txt")
        val frSample = "Les élèves étudient la littérature française à l'école avec beaucoup d'intérêt et de sérieux."
        cp1252File.writeBytes(frSample.toByteArray(Charset.forName("windows-1252")))
        val cp1252Doc = importer.parseDocumentFromUri(Uri.fromFile(cp1252File)).getOrThrow()
        assertEquals("fr", cp1252Doc.detectedLanguageCode)
        assertTrue(cp1252Doc.sections.first().body.contains("élèves étudient"))
    }

    @Test
    fun `txt parser streams large multi-thousand-line file and fails cleanly on empty file`() = runTest {
        val importer = BookImporter(context)

        val emptyFile = File(tempDir, "empty.txt").apply { writeText("") }
        assertTrue(importer.parseDocumentFromUri(Uri.fromFile(emptyFile)).isFailure)

        val largeFile = File(tempDir, "large_book.txt")
        largeFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            repeat(2500) { i ->
                writer.write("Sentence number $i explains offline document streaming without excessive memory pressure.")
                writer.newLine()
                if (i % 5 == 0) writer.newLine()
            }
        }
        val parsed = importer.parseDocumentFromUri(Uri.fromFile(largeFile)).getOrThrow()
        assertEquals("TXT", parsed.sourceType)
        assertEquals("en", parsed.detectedLanguageCode)
        val combined = parsed.sections.joinToString("\n") { it.body }
        assertTrue(combined.contains("Sentence number 0"))
        assertTrue(combined.contains("Sentence number 2499"))
    }

    // -------------------------------------------------------------------------
    // 3. Markdown (.md)
    // -------------------------------------------------------------------------

    @Test
    fun `markdown parser extracts YAML metadata splits sections strips syntax and preserves lists and tables`() = runTest {
        val mdFile = File(tempDir, "notes.md").apply {
            writeText(
                """
                ---
                title: دليل القراءة السريع
                author: الكاتب التجريبي
                lang: ar
                ---

                # المقدمة الأولى

                هذا نص **مهم** يحتوي على [رابط داخلي](https://example.com) و `كود مضمن`.

                - العنصر الأول في القائمة
                - العنصر الثاني في القائمة

                > اقتباس أدبي مفيد للقراءة.

                ```kotlin
                val hiddenCode = 42
                ```

                ## جدول المقارنة

                | الصيغة | الدعم |
                | --- | --- |
                | PDF | مدعوم |
                | EPUB | مدعوم |
                """.trimIndent()
            )
        }

        val importer = BookImporter(context)
        val doc = importer.parseDocumentFromUri(Uri.fromFile(mdFile)).getOrThrow()
        assertEquals("MD", doc.sourceType)
        assertEquals("دليل القراءة السريع", doc.title)
        assertEquals("الكاتب التجريبي", doc.author)
        assertEquals("ar", doc.detectedLanguageCode)
        assertEquals(2, doc.sections.size)
        assertEquals("المقدمة الأولى", doc.sections[0].title)
        assertTrue(doc.sections[0].body.contains("هذا نص مهم يحتوي على رابط داخلي"))
        assertTrue(doc.sections[0].body.contains("• العنصر الأول"))
        assertFalse(doc.sections[0].body.contains("hiddenCode"))
        assertEquals("جدول المقارنة", doc.sections[1].title)
        assertTrue(doc.sections[1].body.contains("PDF — مدعوم"))
    }

    // -------------------------------------------------------------------------
    // 4. HTML / XHTML (.html)
    // -------------------------------------------------------------------------

    @Test
    fun `html parser removes scripts styles hidden elements and extracts readable sections`() = runTest {
        val htmlFile = File(tempDir, "article.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <title>Understanding Audio Sync</title>
                  <meta name="author" content="Dr. Claire Martin" />
                  <style>body { color: #fff; }</style>
                  <script>console.log("ignore me");</script>
                </head>
                <body>
                  <div style="display:none">Hidden promo text</div>
                  <p aria-hidden="true">Screen reader hidden</p>
                  <h1>Chapter One</h1>
                  <p>First real paragraph in the HTML document.</p>
                  <ul>
                    <li>Point Alpha</li>
                    <li>Point Beta</li>
                  </ul>
                  <h2>Summary Table</h2>
                  <table>
                    <tr><th>Format</th><th>Status</th></tr>
                    <tr><td>HTML</td><td>Supported</td></tr>
                  </table>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val importer = BookImporter(context)
        val doc = importer.parseDocumentFromUri(Uri.fromFile(htmlFile)).getOrThrow()
        assertEquals("HTML", doc.sourceType)
        assertEquals("Understanding Audio Sync", doc.title)
        assertEquals("Dr. Claire Martin", doc.author)
        assertEquals("en", doc.detectedLanguageCode)
        val fullText = doc.sections.joinToString("\n") { "${it.title}\n${it.body}" }
        assertTrue(fullText.contains("First real paragraph"))
        assertTrue(fullText.contains("• Point Alpha"))
        assertTrue(fullText.contains("HTML — Supported"))
        assertFalse(fullText.contains("ignore me"))
        assertFalse(fullText.contains("Hidden promo text"))
        assertFalse(fullText.contains("Screen reader hidden"))
    }

    // -------------------------------------------------------------------------
    // 5. Subtitles & Transcripts (.srt / .vtt)
    // -------------------------------------------------------------------------

    @Test
    fun `subtitle parser sorts out-of-order SRT cues chronologically and cleans VTT metadata`() = runTest {
        val importer = BookImporter(context)

        // Unsorted SRT in Windows-1256 Arabic
        val srtFile = File(tempDir, "lecture.srt")
        val srtContent = """
            2
            00:00:10,500 --> 00:00:14,000
            <b>الجملة الثانية زمنياً.</b>

            1
            00:00:01,000 --> 00:00:04,500
            الجملة الأولى زمنياً.
        """.trimIndent()
        srtFile.writeBytes(srtContent.toByteArray(Charset.forName("windows-1256")))

        val srtDoc = importer.parseDocumentFromUri(Uri.fromFile(srtFile)).getOrThrow()
        assertEquals("SRT", srtDoc.sourceType)
        assertEquals("ar", srtDoc.detectedLanguageCode)
        val cues = srtDoc.diagnostics?.subtitleCues.orEmpty()
        assertEquals(2, cues.size)
        assertEquals("الجملة الأولى زمنياً.", cues[0].text)
        assertEquals("الجملة الثانية زمنياً.", cues[1].text)
        val body = srtDoc.sections.first().body
        assertTrue(body.indexOf("الجملة الأولى") < body.indexOf("الجملة الثانية"))

        // VTT with NOTE, STYLE, speaker tag <v> and unsorted cues
        val vttFile = File(tempDir, "interview.vtt").apply {
            writeText(
                """
                WEBVTT

                NOTE This comment block must be stripped

                STYLE
                ::cue { color: white; }

                00:01:05.000 --> 00:01:10.000
                <v Alice>Bonjour tout le monde dans cet entretien.</v>

                00:00:02.000 --> 00:00:06.000
                <c.highlight>Bienvenue à l'émission d'aujourd'hui.</c>
                """.trimIndent()
            )
        }
        val vttDoc = importer.parseDocumentFromUri(Uri.fromFile(vttFile)).getOrThrow()
        assertEquals("VTT", vttDoc.sourceType)
        assertEquals("fr", vttDoc.detectedLanguageCode)
        val vttBody = vttDoc.sections.joinToString("\n") { it.body }
        assertTrue(vttBody.contains("Bienvenue à l'émission d'aujourd'hui."))
        assertTrue(vttBody.contains("Alice: Bonjour tout le monde dans cet entretien."))
        assertFalse(vttBody.contains("must be stripped"))
        assertFalse(vttBody.contains("color: white"))
        assertTrue(vttBody.indexOf("Bienvenue") < vttBody.indexOf("Alice:"))
    }

    // -------------------------------------------------------------------------
    // 6. DOCX & EPUB
    // -------------------------------------------------------------------------

    @Test
    fun `docx parser extracts metadata headings lists and tables`() = runTest {
        val docxFile = createSampleDocxFile(
            fileName = "guide.docx",
            title = "Rapport Technique",
            author = "Équipe Écoute",
            heading = "Introduction Générale",
            paragraph = "Ce document décrit les formats pris en charge hors ligne.",
            listItems = listOf("Premier point vérifié", "Deuxième point vérifié"),
            tableRows = listOf(listOf("Module", "État"), listOf("Import", "Prêt"))
        )

        val importer = BookImporter(context)
        val doc = importer.parseDocumentFromUri(Uri.fromFile(docxFile)).getOrThrow()
        assertEquals("DOCX", doc.sourceType)
        assertEquals("Rapport Technique", doc.title)
        assertEquals("Équipe Écoute", doc.author)
        assertEquals("fr", doc.detectedLanguageCode)
        val text = doc.sections.joinToString("\n") { "${it.title}\n${it.body}" }
        assertTrue(text.contains("Introduction Générale"))
        assertTrue(text.contains("Ce document décrit"))
        assertTrue(text.contains("• Premier point vérifié"))
        assertTrue(text.contains("Import — Prêt"))
    }

    @Test
    fun `epub parser resolves url-encoded hrefs and detects Arabic content`() = runTest {
        val epubFile = File(tempDir, "arabic_book.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
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
                    <dc:title>رحلة القراءة</dc:title>
                    <dc:creator>مؤلف عربي</dc:creator>
                    <dc:language>ar</dc:language>
                  </metadata>
                  <manifest>
                    <item id="c1" href="Chapter%20One.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/Chapter One.xhtml"))
            zos.write(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body>
                    <h1>الفصل الأول</h1>
                    <p>هذه قصة عربية واضحة مخزنة داخل كتاب إلكتروني بصيغة EPUB مع مسافة مشفرة في المسار.</p>
                  </body>
                </html>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()
        }

        val importer = BookImporter(context)
        val doc = importer.parseDocumentFromUri(Uri.fromFile(epubFile)).getOrThrow()
        assertEquals("EPUB", doc.sourceType)
        assertEquals("رحلة القراءة", doc.title)
        assertEquals("ar", doc.detectedLanguageCode)
        assertTrue(doc.sections.first().body.contains("قصة عربية واضحة"))
    }

    // -------------------------------------------------------------------------
    // 7. PDF (Text-layer, Arabic NFKC, Scanned OCR, Mixed, Encrypted, Corrupt)
    // -------------------------------------------------------------------------

    @Test
    fun `pdf parser extracts text-layer English and Arabic PDFs without invoking OCR`() = runTest {
        var ocrCalls = 0
        val spyOcr = object : PdfPageOcrEngine {
            override suspend fun recognizePages(
                pdfFile: File,
                pageIndicesZeroBased: List<Int>,
                onPageDone: suspend (Int, Float) -> Unit
            ): Map<Int, String> {
                ocrCalls++
                return emptyMap()
            }
        }
        val importer = BookImporter(context = context, pdfOcrEngine = spyOcr)

        val textPdf = createTextPdfFile(
            fileName = "english_text.pdf",
            title = "Offline PDF Guide",
            author = "Écoute Lab",
            pages = listOf(
                listOf(
                    "First page contains plenty of searchable English text for direct extraction.",
                    "No OCR engine should be triggered when text layer density is high."
                )
            )
        )

        val enDoc = importer.parseDocumentFromUri(
            Uri.fromFile(textPdf),
            DocumentExtractionOptions(pdfMode = PdfExtractionMode.AUTO_PREFER_TEXT)
        ).getOrThrow()
        assertEquals("PDF", enDoc.sourceType)
        assertEquals("Offline PDF Guide", enDoc.title)
        assertEquals("en", enDoc.detectedLanguageCode)
        assertEquals(0, ocrCalls)
        assertTrue(enDoc.sections.first().body.contains("searchable English text"))
        assertEquals(true, enDoc.diagnostics?.pdfHasTextLayer)
        assertEquals(0, enDoc.diagnostics?.ocrPagesUsed)

        // Arabic text-layer PDF using bundled Readex Pro TTF + NFKC presentation form normalization check
        val arabicFontFile = File("src/main/res/font/readex_pro_regular.ttf")
            .takeIf { it.exists() }
            ?: File("app/src/main/res/font/readex_pro_regular.ttf")
        if (arabicFontFile.exists()) {
            val arPdf = File(tempDir, "arabic_text.pdf")
            PDDocument().use { doc ->
                val font = PDType0Font.load(doc, arabicFontFile)
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("السلام عليكم ورحمة الله وبركاته هذا نص عربي داخل مستند pdf")
                    cs.endText()
                }
                doc.save(arPdf)
            }
            val arDoc = importer.parseDocumentFromUri(Uri.fromFile(arPdf)).getOrThrow()
            assertEquals("ar", arDoc.detectedLanguageCode)
            assertEquals(0, ocrCalls)
        }

        // Verify NFKC presentation form normalization directly
        val normalized = PdfDocumentParser.cleanExtractedPdfText("\uFE8E\uFE92\uFEA0")
        assertEquals("ابج", normalized)
    }

    @Test
    fun `pdf parser handles scanned and mixed PDFs with OCR modes plus encrypted and corrupt PDFs`() = runTest {
        val invokedPages = mutableListOf<Int>()
        val fakeOcr = object : PdfPageOcrEngine {
            override suspend fun recognizePages(
                pdfFile: File,
                pageIndicesZeroBased: List<Int>,
                onPageDone: suspend (Int, Float) -> Unit
            ): Map<Int, String> {
                invokedPages.addAll(pageIndicesZeroBased)
                val out = linkedMapOf<Int, String>()
                pageIndicesZeroBased.forEachIndexed { i, p ->
                    out[p] = "Scanned OCR recognized text for page ${p + 1} in English."
                    onPageDone(p, (i + 1).toFloat() / pageIndicesZeroBased.size)
                }
                return out
            }
        }
        val importer = BookImporter(context = context, pdfOcrEngine = fakeOcr)

        // 1. Image-only scanned PDF
        val scannedPdf = createMixedOrScannedPdfFile(
            fileName = "scanned_only.pdf",
            pageTextLists = listOf(emptyList(), emptyList())
        )
        val textOnlyRes = importer.parseDocumentFromUri(
            Uri.fromFile(scannedPdf),
            DocumentExtractionOptions(pdfMode = PdfExtractionMode.TEXT_ONLY)
        )
        assertTrue(textOnlyRes.isFailure)
        assertTrue(textOnlyRes.exceptionOrNull()?.message?.contains("مصور") == true)
        assertTrue(invokedPages.isEmpty())

        val autoRes = importer.parseDocumentFromUri(
            Uri.fromFile(scannedPdf),
            DocumentExtractionOptions(pdfMode = PdfExtractionMode.AUTO_PREFER_TEXT)
        ).getOrThrow()
        assertEquals(listOf(0, 1), invokedPages)
        assertEquals(2, autoRes.diagnostics?.ocrPagesUsed)
        assertTrue(autoRes.sections.first().body.contains("Scanned OCR recognized text for page 1"))
        assertTrue(autoRes.sections.first().body.contains("Scanned OCR recognized text for page 2"))

        // 2. Mixed PDF (Page 0 has full text, Page 1 is scanned image only)
        invokedPages.clear()
        val mixedPdf = createMixedOrScannedPdfFile(
            fileName = "mixed.pdf",
            pageTextLists = listOf(
                listOf("Page one already has rich embedded searchable text layer ready for extraction."),
                emptyList()
            )
        )
        val mixedRes = importer.parseDocumentFromUri(
            Uri.fromFile(mixedPdf),
            DocumentExtractionOptions(pdfMode = PdfExtractionMode.AUTO_PREFER_TEXT)
        ).getOrThrow()
        // OCR must ONLY be triggered on page index 1, NOT page index 0!
        assertEquals(listOf(1), invokedPages)
        val mixedFull = mixedRes.sections.joinToString("\n") { it.body }
        assertTrue(mixedFull.contains("rich embedded searchable text layer"))
        assertTrue(mixedFull.contains("Scanned OCR recognized text for page 2"))

        // 3. FORCE_OCR triggers OCR on all pages even when text exists
        invokedPages.clear()
        val forceRes = importer.parseDocumentFromUri(
            Uri.fromFile(mixedPdf),
            DocumentExtractionOptions(pdfMode = PdfExtractionMode.FORCE_OCR)
        ).getOrThrow()
        assertEquals(listOf(0, 1), invokedPages)
        assertEquals(2, forceRes.diagnostics?.ocrPagesUsed)

        // 4. Password-encrypted PDF
        val encryptedPdf = File(tempDir, "encrypted.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val policy = StandardProtectionPolicy("secret", "user", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(encryptedPdf)
        }
        val encRes = importer.parseDocumentFromUri(Uri.fromFile(encryptedPdf))
        assertTrue(encRes.isFailure)
        assertTrue(encRes.exceptionOrNull()?.message?.contains("كلمة مرور") == true)

        // 5. Corrupt PDF
        val corruptPdf = File(tempDir, "corrupt.pdf").apply {
            writeBytes("%PDF-1.7\ncorrupted-junk-stream-bytes".toByteArray())
        }
        val corruptRes = importer.parseDocumentFromUri(Uri.fromFile(corruptPdf))
        assertTrue(corruptRes.isFailure)
        assertTrue(corruptRes.exceptionOrNull()?.message?.contains("PDF") == true)
    }

    // -------------------------------------------------------------------------
    // 8. End-to-End Persistence (DB Reopen), Translation Chunker & Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `library repository persists imported DOCX and Arabic MD across Room DB reopen and deduplicates`() = runTest {
        val importer = BookImporter(context)
        val repo = LibraryRepository(db.bookDao(), db.vocabularyDao(), importer, context, db.recentActivityDao())

        val arMd = File(tempDir, "arabic_essay.md").apply {
            writeText(
                """
                # المقال الأول

                القراءة العميقة تمنح الإنسان قدرة أكبر على التركيز والفهم. وهذه جملة ثانية بالعربية!
                """.trimIndent()
            )
        }
        val firstOutcome = repo.importBookChecked(Uri.fromFile(arMd)).getOrThrow()
        assertFalse(firstOutcome.wasDuplicate)
        assertEquals("MD", firstOutcome.book.sourceType)
        assertEquals("ar", firstOutcome.book.sourceLanguage)
        assertEquals(2, firstOutcome.book.totalSentences)

        // Duplicate import with identical bytes returns existing book without duplication
        val dupOutcome = repo.importBookChecked(Uri.fromFile(arMd)).getOrThrow()
        assertTrue(dupOutcome.wasDuplicate)
        assertEquals(firstOutcome.book.id, dupOutcome.book.id)

        // Close and reopen real Room DB file to verify persistence after app restart
        db.close()
        val reopenedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val persistedBook = reopenedDb.bookDao().getBook(firstOutcome.book.id)
            assertNotNull(persistedBook)
            assertEquals("ar", persistedBook!!.sourceLanguage)
            assertEquals("MD", persistedBook.sourceType)
            val sentences = reopenedDb.bookDao().observeChapterSentences(persistedBook.id, 0).first()
            assertEquals(2, sentences.size)
            assertTrue(sentences[0].spokenText.contains("القراءة العميقة"))
        } finally {
            reopenedDb.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
        }
    }

    @Test
    fun `file translation repository builds chunks from DOCX SRT and guards Arabic source auto-detect`() = runTest {
        val importer = BookImporter(context)
        val txRepo = FileTranslationRepository(context, db.translationJobDao(), importer, db.recentActivityDao())

        val docxFile = createSampleDocxFile(
            fileName = "for_translation.docx",
            title = "French Essay",
            author = "Auteur",
            heading = "Chapitre Premier",
            paragraph = "La lecture enrichit l'esprit humain. Chaque phrase bien construite éclaire la compréhension.",
            listItems = listOf("Première idée"),
            tableRows = emptyList()
        )
        val job = txRepo.createJobFromUri(Uri.fromFile(docxFile), selectedSourceLanguage = null).getOrThrow()
        assertEquals("DOCX", job.sourceType)
        assertEquals("fr", job.sourceLanguage)
        val chunks = db.translationJobDao().getAllChunks(job.id)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.first().isHeading)

        // Arabic document with auto-detect (null override) should be rejected cleanly with guidance
        val arabicTxt = File(tempDir, "ar_only.txt").apply {
            writeText("هذا نص عربي خالص مخصص للقراءة في المكتبة وليس للترجمة إلى العربية.")
        }
        val arRes = txRepo.createJobFromUri(Uri.fromFile(arabicTxt), selectedSourceLanguage = null)
        assertTrue(arRes.isFailure)
        assertTrue(arRes.exceptionOrNull()?.message?.contains("العربية") == true)
    }

    @Test
    fun `cancelling import cleans up temp cache files and leaves no partial database records`() = runTest {
        // Count existing temp files before import
        val beforeTmp = context.cacheDir.listFiles { _, name -> name.startsWith("doc_import_") }?.size ?: 0
        val slowOcr = object : PdfPageOcrEngine {
            override suspend fun recognizePages(
                pdfFile: File,
                pageIndicesZeroBased: List<Int>,
                onPageDone: suspend (Int, Float) -> Unit
            ): Map<Int, String> {
                kotlinx.coroutines.delay(5_000)
                return emptyMap()
            }
        }
        val importer = BookImporter(context = context, pdfOcrEngine = slowOcr)
        val repo = LibraryRepository(db.bookDao(), db.vocabularyDao(), importer, context, db.recentActivityDao())
        val scannedPdf = createMixedOrScannedPdfFile("cancel_me.pdf", listOf(emptyList()))

        val job = launch(Dispatchers.IO) {
            repo.importBookChecked(
                Uri.fromFile(scannedPdf),
                DocumentExtractionOptions(pdfMode = PdfExtractionMode.FORCE_OCR)
            )
        }
        // Let coroutine enter OCR delay then cancel cooperatively
        kotlinx.coroutines.delay(150)
        yield()
        job.cancelAndJoin()

        val afterTmp = context.cacheDir.listFiles { _, name -> name.startsWith("doc_import_") }?.size ?: 0
        assertEquals(beforeTmp, afterTmp)
        assertEquals(0, db.bookDao().countBooks())
    }

    @Test
    fun `inspectUris accurately previews mixed batch and marks unsupported files`() = runTest {
        val importer = BookImporter(context)
        val validMd = File(tempDir, "valid.md").apply { writeText("# Title\n\nSome English body text here.") }
        val unsupportedZip = File(tempDir, "bundle.zip")
        ZipOutputStream(FileOutputStream(unsupportedZip)).use { zos ->
            zos.putNextEntry(ZipEntry("data.bin"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }

        val previews = importer.inspectUris(listOf(Uri.fromFile(validMd), Uri.fromFile(unsupportedZip)))
        assertEquals(2, previews.size)
        assertTrue(previews[0].isSupported)
        assertEquals(SupportedImportFormat.MD, previews[0].detectedFormat)
        assertFalse(previews[1].isSupported)
        assertNotNull(previews[1].unsupportedReason)
    }

    // -------------------------------------------------------------------------
    // Helpers for Generating Real DOCX & PDF Files
    // -------------------------------------------------------------------------

    private fun createTextPdfFile(
        fileName: String,
        title: String,
        author: String,
        pages: List<List<String>>
    ): File {
        val file = File(tempDir, fileName)
        PDDocument().use { doc ->
            doc.documentInformation.title = title
            doc.documentInformation.author = author
            pages.forEach { lines ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.setLeading(16f)
                    cs.newLineAtOffset(50f, 720f)
                    lines.forEach { line ->
                        cs.showText(line)
                        cs.newLine()
                    }
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun createMixedOrScannedPdfFile(
        fileName: String,
        pageTextLists: List<List<String>>
    ): File {
        val file = File(tempDir, fileName)
        val tinyBmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        try {
            PDDocument().use { doc ->
                val xImg = LosslessFactory.createFromImage(doc, tinyBmp)
                pageTextLists.forEach { lines ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        if (lines.isEmpty()) {
                            cs.drawImage(xImg, 50f, 500f, 200f, 200f)
                        } else {
                            cs.beginText()
                            cs.setFont(PDType1Font.HELVETICA, 12f)
                            cs.setLeading(16f)
                            cs.newLineAtOffset(50f, 720f)
                            lines.forEach { line ->
                                cs.showText(line)
                                cs.newLine()
                            }
                            cs.endText()
                        }
                    }
                }
                doc.save(file)
            }
        } finally {
            tinyBmp.recycle()
        }
        return file
    }

    private fun createSampleDocxFile(
        fileName: String,
        title: String,
        author: String,
        heading: String,
        paragraph: String,
        listItems: List<String>,
        tableRows: List<List<String>>
    ): File {
        val file = File(tempDir, fileName)
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                </Types>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                </Relationships>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("docProps/core.xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>$title</dc:title>
                  <dc:creator>$author</dc:creator>
                </cp:coreProperties>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            val listXml = listItems.joinToString("\n") { item ->
                """
                <w:p>
                  <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                  <w:r><w:t>$item</w:t></w:r>
                </w:p>
                """.trimIndent()
            }
            val tableXml = if (tableRows.isEmpty()) "" else {
                val rows = tableRows.joinToString("\n") { cols ->
                    val cells = cols.joinToString("") { col ->
                        "<w:tc><w:p><w:r><w:t>$col</w:t></w:r></w:p></w:tc>"
                    }
                    "<w:tr>$cells</w:tr>"
                }
                "<w:tbl>$rows</w:tbl>"
            }

            zos.putNextEntry(ZipEntry("word/document.xml"))
            val docXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append('\n')
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
                append('\n')
                append("  <w:body>\n")
                append("    <w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>$heading</w:t></w:r></w:p>\n")
                append("    <w:p><w:r><w:t>$paragraph</w:t></w:r></w:p>\n")
                if (listXml.isNotBlank()) {
                    append(listXml).append('\n')
                }
                if (tableXml.isNotBlank()) {
                    append(tableXml).append('\n')
                }
                append("  </w:body>\n")
                append("</w:document>")
            }
            zos.write(docXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return file
    }
}
