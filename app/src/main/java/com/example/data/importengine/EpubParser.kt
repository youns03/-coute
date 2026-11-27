package com.example.data.importengine

import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal object EpubParser {

    suspend fun parseFile(
        epubFile: File,
        rawFileName: String,
        maxEpubEntries: Int,
        maxSpineEntryBytes: Long,
        maxTotalUncompressedBytes: Long,
        maxExtractedTextChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        ZipFile(epubFile).use { zip ->
            if (zip.size() > maxEpubEntries) {
                error("ملف EPUB يحتوي على عدد عناصر أكبر من الحد المسموح ($maxEpubEntries).")
            }

            val entryByName = LinkedHashMap<String, ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory && !entry.name.contains("..")) {
                    entryByName[entry.name] = entry
                }
            }
            if (entryByName.isEmpty()) error("ملف EPUB غير صالح أو فارغ.")

            var cumulativeUncompressedBytes = 0L

            val containerEntry = findZipEntry(entryByName, "META-INF/container.xml")
                ?: error("ملف EPUB يفتقد META-INF/container.xml")
            val containerBytes = readZipEntryBounded(zip, containerEntry, maxSpineEntryBytes)
            cumulativeUncompressedBytes += containerBytes.size

            val opfPath = parseContainerOpfPath(containerBytes)
                ?: entryByName.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
                ?: error("تعذر العثور على ملف OPF داخل EPUB")

            val opfDir = if ('/' in opfPath) opfPath.substringBeforeLast('/') + "/" else ""
            val opfEntry = findZipEntry(entryByName, opfPath)
                ?: error("تعذر قراءة $opfPath")
            val opfBytes = readZipEntryBounded(zip, opfEntry, maxSpineEntryBytes)
            cumulativeUncompressedBytes += opfBytes.size
            if (cumulativeUncompressedBytes > maxTotalUncompressedBytes) {
                error("إجمالي حجم نصوص EPUB المفكوك يتجاوز الحد المسموح.")
            }

            val opfMeta = parseOpf(opfBytes)
            val bookTitle = opfMeta.title.ifBlank { rawFileName.substringBeforeLast('.') }
            val bookAuthor = opfMeta.author.ifBlank { "كتاب إلكتروني (EPUB)" }

            val sections = mutableListOf<ExtractedDocumentSection>()
            var totalExtractedChars = 0L
            val spineCount = opfMeta.spinePaths.size.coerceAtLeast(1)

            opfMeta.spinePaths.forEachIndexed { idx, rawRelHref ->
                currentCoroutineContext().ensureActive()
                val relHref = runCatching { URLDecoder.decode(rawRelHref, "UTF-8") }.getOrDefault(rawRelHref)
                val fullPath = normalizeZipPath(opfDir + relHref)
                val spineEntry = findZipEntry(entryByName, fullPath)
                    ?: findZipEntry(entryByName, normalizeZipPath(opfDir + rawRelHref))
                if (spineEntry != null) {
                    val remainingBudget = (maxTotalUncompressedBytes - cumulativeUncompressedBytes).coerceAtLeast(0L)
                    if (remainingBudget == 0L) {
                        error("إجمالي حجم نصوص EPUB المفكوك يتجاوز الحد المسموح.")
                    }
                    val htmlBytes = readZipEntryBounded(
                        zip,
                        spineEntry,
                        minOf(maxSpineEntryBytes, remainingBudget)
                    )
                    cumulativeUncompressedBytes += htmlBytes.size
                    val decoded = CharsetDetection.decodeBytes(htmlBytes)
                    val chapterDoc = Jsoup.parse(decoded.text)
                    val extracted = HtmlDocumentParser.extractStructuredDocument(
                        doc = chapterDoc,
                        fallbackTitle = "",
                        maxExtractedTextChars = maxExtractedTextChars - totalExtractedChars.toInt().coerceAtLeast(0)
                    )
                    val chapterBody = extracted.sections.joinToString("\n\n") { sec ->
                        if (sec.title.isNotBlank() && extracted.sections.size > 1) {
                            "${sec.title}\n\n${sec.body}"
                        } else {
                            sec.body
                        }
                    }.trim()
                    if (chapterBody.isNotBlank()) {
                        totalExtractedChars += chapterBody.length
                        if (totalExtractedChars > maxExtractedTextChars) {
                            error("حجم النص المستخرج من EPUB يتجاوز الحد المسموح للمعالجة.")
                        }
                        val firstHeading = extracted.sections.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
                        val chTitle = firstHeading.ifBlank {
                            extracted.title.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                        }
                        sections.add(ExtractedDocumentSection(chTitle, chapterBody))
                    }
                }
                onProgress(((idx + 1).toFloat() / spineCount.toFloat()).coerceIn(0.05f, 0.98f))
            }

            if (sections.isEmpty()) {
                error("لم يتم العثور على فصول نصية صالحة داخل هذا الـ EPUB.")
            }

            val sample = sections.take(2).joinToString("\n") { it.body }.take(3000)
            val lang = LanguageDetector.detect(sample, opfMeta.language)
            onProgress(1f)

            return ParsedSourceDocument(
                fileName = rawFileName,
                title = bookTitle,
                author = bookAuthor,
                sourceType = "EPUB",
                detectedLanguage = lang.translationSourceLanguage,
                detectedLanguageCode = lang.languageCode,
                sections = sections,
                diagnostics = ExtractionDiagnostics(
                    detectedLanguageCode = lang.languageCode,
                    translationSourceLanguage = lang.translationSourceLanguage
                )
            )
        }
    }

    private data class OpfMetadata(
        val title: String,
        val author: String,
        val language: String,
        val spinePaths: List<String>
    )

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
}

internal fun findZipEntry(entries: Map<String, ZipEntry>, targetPath: String): ZipEntry? {
    return entries[targetPath]
        ?: entries.entries.firstOrNull { it.key.equals(targetPath, ignoreCase = true) }?.value
}

internal fun readZipEntryBounded(zip: ZipFile, entry: ZipEntry, maxBytes: Long): ByteArray {
    if (entry.size > maxBytes) {
        error("عنصر داخل الملف المضغوط (${entry.name}) يتجاوز الحد المسموح (${maxBytes / (1024 * 1024)} MB).")
    }
    val baos = ByteArrayOutputStream()
    var totalRead = 0L
    zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            totalRead += read
            if (totalRead > maxBytes) {
                error("الحجم المفكوك لعنصر (${entry.name}) يتجاوز الحد المسموح (${maxBytes / (1024 * 1024)} MB).")
            }
            baos.write(buffer, 0, read)
        }
    }
    return baos.toByteArray()
}
