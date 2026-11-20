package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.example.domain.FrenchTextPreprocessor
import com.example.translation.SourceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class ExtractedDocumentSection(
    val title: String,
    val body: String
)

data class ParsedSourceDocument(
    val fileName: String,
    val title: String,
    val author: String,
    val sourceType: String, // "TXT" or "EPUB"
    val detectedLanguage: SourceLanguage,
    val sections: List<ExtractedDocumentSection>
)

data class ImportedBookPayload(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val sentences: List<SentenceEntity>
)

class BookImporter(private val context: Context) {

    suspend fun parseDocumentFromUri(uri: Uri): Result<ParsedSourceDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveFileName(uri) ?: "Document"
            val mimeType = context.contentResolver.getType(uri).orEmpty()

            if (fileName.endsWith(".pdf", ignoreCase = true) || mimeType.contains("pdf", ignoreCase = true)) {
                error("استيراد ملفات PDF غير متاح حالياً — يدعم التطبيق ملفات TXT وEPUB الآن مع قابلية إضافة PDF لاحقاً.")
            }

            val isEpub = fileName.endsWith(".epub", ignoreCase = true) ||
                mimeType.contains("epub", ignoreCase = true)

            context.contentResolver.openInputStream(uri)?.use { stream ->
                if (isEpub) {
                    parseEpubDocument(stream, fileName)
                } else {
                    parseTxtDocument(stream, fileName)
                }
            } ?: error("تعذر فتح الملف المحدد.")
        }
    }

    suspend fun importFromUri(uri: Uri): Result<ImportedBookPayload> = withContext(Dispatchers.IO) {
        parseDocumentFromUri(uri).mapCatching { doc ->
            val bookId = "${doc.sourceType.lowercase()}_${UUID.randomUUID().toString().take(8)}"
            buildBookPayload(
                bookId = bookId,
                title = doc.title,
                author = doc.author,
                sourceType = doc.sourceType,
                sourceLanguage = doc.detectedLanguage.code,
                sections = doc.sections
            )
        }
    }

    private fun parseTxtDocument(input: InputStream, rawFileName: String): ParsedSourceDocument {
        val rawBytes = input.readBytes()
        val text = decodeUtf8WithBom(rawBytes)
        if (text.isBlank()) error("الملف النصي فارغ.")

        val title = rawFileName.substringBeforeLast('.').ifBlank { "مستند نصي" }
        val paragraphs = text.replace("\r\n", "\n")
            .split(Regex("""\n\s*\n+"""))
            .filter { it.isNotBlank() }

        val sections = mutableListOf<ExtractedDocumentSection>()
        val currentChunk = StringBuilder()
        var sectionNum = 1
        for (p in paragraphs) {
            currentChunk.append(p.trim()).append("\n\n")
            if (currentChunk.length >= 3500) {
                sections.add(ExtractedDocumentSection("Section $sectionNum", currentChunk.toString().trim()))
                currentChunk.clear()
                sectionNum++
            }
        }
        if (currentChunk.isNotBlank()) {
            val label = if (sectionNum == 1) "" else "Section $sectionNum"
            sections.add(ExtractedDocumentSection(label, currentChunk.toString().trim()))
        }

        val detected = detectSourceLanguage(text.take(2500), opfLangHint = null)
        return ParsedSourceDocument(
            fileName = rawFileName,
            title = title,
            author = "ملف نصي (TXT)",
            sourceType = "TXT",
            detectedLanguage = detected,
            sections = sections
        )
    }

    private fun parseEpubDocument(input: InputStream, rawFileName: String): ParsedSourceDocument {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.contains("..")) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (entries.isEmpty()) error("ملف EPUB غير صالح أو فارغ.")

        val containerBytes = entries["META-INF/container.xml"]
            ?: error("ملف EPUB يفتقد META-INF/container.xml")
        val opfPath = parseContainerOpfPath(containerBytes)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: error("تعذر العثور على ملف OPF داخل EPUB")

        val opfDir = if ('/' in opfPath) opfPath.substringBeforeLast('/') + "/" else ""
        val opfBytes = entries[opfPath] ?: error("تعذر قراءة $opfPath")

        val opfMeta = parseOpf(opfBytes)
        val bookTitle = opfMeta.title.ifBlank { rawFileName.substringBeforeLast('.') }
        val bookAuthor = opfMeta.author.ifBlank { "كتاب إلكتروني (EPUB)" }

        val sections = mutableListOf<ExtractedDocumentSection>()
        opfMeta.spinePaths.forEachIndexed { idx, relHref ->
            val fullPath = normalizeZipPath(opfDir + relHref)
            val htmlBytes = entries[fullPath]
                ?: entries.entries.firstOrNull { it.key.equals(fullPath, ignoreCase = true) }?.value
            if (htmlBytes != null) {
                val xhtml = decodeUtf8WithBom(htmlBytes)
                val extracted = extractReadableTextFromXhtml(xhtml)
                if (extracted.second.isNotBlank()) {
                    val chTitle = extracted.first.ifBlank { "Chapter ${idx + 1}" }
                    sections.add(ExtractedDocumentSection(chTitle, extracted.second))
                }
            }
        }

        if (sections.isEmpty()) {
            error("لم يتم العثور على فصول نصية صالحة داخل هذا الـ EPUB.")
        }

        val sample = sections.take(2).joinToString("\n") { it.body }.take(2500)
        val detected = detectSourceLanguage(sample, opfMeta.language)

        return ParsedSourceDocument(
            fileName = rawFileName,
            title = bookTitle,
            author = bookAuthor,
            sourceType = "EPUB",
            detectedLanguage = detected,
            sections = sections
        )
    }

    private fun buildBookPayload(
        bookId: String,
        title: String,
        author: String,
        sourceType: String,
        sourceLanguage: String,
        sections: List<ExtractedDocumentSection>
    ): ImportedBookPayload {
        val chapters = mutableListOf<ChapterEntity>()
        val sentences = mutableListOf<SentenceEntity>()
        var totalSentences = 0

        sections.forEachIndexed { idx, section ->
            val processed = FrenchTextPreprocessor.splitAndPreprocess(section.body)
            if (processed.isNotEmpty()) {
                val realChIndex = chapters.size
                val chTitle = section.title.ifBlank {
                    if (sourceLanguage == "en") "Section ${idx + 1}" else "Section ${idx + 1}"
                }
                chapters.add(
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = realChIndex,
                        title = chTitle,
                        sentenceCount = processed.size
                    )
                )
                processed.forEach { s ->
                    sentences.add(
                        SentenceEntity(
                            bookId = bookId,
                            chapterIndex = realChIndex,
                            sentenceIndex = s.index,
                            paragraphIndex = s.paragraphIndex,
                            originalText = s.originalText,
                            spokenText = if (sourceLanguage == "fr") s.spokenText else s.originalText
                        )
                    )
                }
                totalSentences += processed.size
            }
        }

        if (totalSentences == 0) error("لم يتم استخراج أي جمل صالحة من الكتاب.")

        val book = BookEntity(
            id = bookId,
            title = title,
            author = author,
            sourceType = sourceType,
            sourceLanguage = sourceLanguage,
            totalChapters = chapters.size,
            totalSentences = totalSentences
        )
        return ImportedBookPayload(book, chapters, sentences)
    }

    private fun decodeUtf8WithBom(bytes: ByteArray): String {
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    private fun parseContainerOpfPath(bytes: ByteArray): String? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath
            }
            event = parser.next()
        }
        return null
    }

    private data class OpfMetadata(
        val title: String,
        val author: String,
        val language: String,
        val spinePaths: List<String>
    )

    private fun parseOpf(bytes: ByteArray): OpfMetadata {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var title = ""
        var author = ""
        var language = ""
        val manifest = mutableMapOf<String, Pair<String, String>>()
        val spineIds = mutableListOf<String>()
        var currentTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.lowercase()
                    when (currentTag) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                            if (id != null && href != null) {
                                manifest[id] = href.substringBefore('#') to mediaType
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (idref != null) spineIds.add(idref)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        if (currentTag == "title" && title.isEmpty()) title = txt
                        if (currentTag == "creator" && author.isEmpty()) author = txt
                        if (currentTag == "language" && language.isEmpty()) language = txt
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            event = parser.next()
        }

        val spinePaths = spineIds.mapNotNull { id ->
            val (href, mediaType) = manifest[id] ?: return@mapNotNull null
            if (mediaType.isEmpty() || mediaType.contains("html") || mediaType.contains("xml")) href else null
        }
        return OpfMetadata(title, author, language, spinePaths)
    }

    private fun extractReadableTextFromXhtml(xhtml: String): Pair<String, String> {
        val body = Regex("""<body[^>]*>(.*)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xhtml)?.groupValues?.get(1) ?: xhtml

        val headingMatch = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.get(1)
        val heading = headingMatch?.let { decodeHtmlText(it.replace(Regex("<[^>]+>"), " ")) }?.trim().orEmpty()

        val cleaned = body
            .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(p|div|h[1-6]|li|blockquote)>""", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("""<[^>]+>"""), "")

        val decoded = decodeHtmlText(cleaned)
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

        return heading to decoded
    }

    private fun decodeHtmlText(raw: String): String {
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty()) stack.removeLast()
            } else {
                stack.addLast(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun resolveFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    companion object {
        fun detectSourceLanguage(sampleText: String, opfLangHint: String?): SourceLanguage {
            val hint = opfLangHint.orEmpty().lowercase()
            if (hint.startsWith("en")) return SourceLanguage.ENGLISH
            if (hint.startsWith("fr")) return SourceLanguage.FRENCH

            val lower = " ${sampleText.lowercase()} "
            val frenchDiacritics = lower.count { it in "éèêëàâùûüôîïçœ" }
            val frenchMarkers = listOf(" le ", " la ", " les ", " des ", " une ", " est ", " pour ", " dans ", " avec ", " qui ", " que ")
                .sumOf { marker -> Regex(Regex.escape(marker)).findAll(lower).count() }
            val englishMarkers = listOf(" the ", " and ", " of ", " to ", " is ", " in ", " that ", " with ", " for ", " are ")
                .sumOf { marker -> Regex(Regex.escape(marker)).findAll(lower).count() }

            val frenchScore = frenchMarkers * 2 + frenchDiacritics
            val englishScore = englishMarkers * 2
            return if (englishScore > frenchScore) SourceLanguage.ENGLISH else SourceLanguage.FRENCH
        }
    }
}
