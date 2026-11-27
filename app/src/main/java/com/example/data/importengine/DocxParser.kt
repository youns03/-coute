package com.example.data.importengine

import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Streaming OOXML (.docx) parser.
 * - Reads optional docProps/core.xml metadata (title, creator).
 * - Detects Heading1/Heading2/Title styles to split real sections/chapters.
 * - Preserves lists (`w:numPr`), line breaks (`w:br`, `w:cr`), and tables (`w:tbl`/`w:tr`/`w:tc`).
 */
internal object DocxParser {

    private data class CoreMetadata(val title: String = "", val author: String = "")

    suspend fun parseFile(
        docxFile: File,
        rawFileName: String,
        maxZipEntries: Int,
        maxTotalUncompressedBytes: Long,
        maxExtractedTextChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        ZipFile(docxFile).use { zip ->
            if (zip.size() > maxZipEntries) {
                error("ملف DOCX يحتوي على عدد عناصر أكبر من الحد المسموح.")
            }
            val entryByName = LinkedHashMap<String, ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory && !entry.name.contains("..")) {
                    entryByName[entry.name] = entry
                }
            }

            val coreMeta = findZipEntry(entryByName, "docProps/core.xml")?.let { coreEntry ->
                runCatching {
                    val bytes = readZipEntryBounded(zip, coreEntry, 512 * 1024L)
                    parseCoreMetadata(bytes)
                }.getOrNull()
            } ?: CoreMetadata()

            val docEntry = findZipEntry(entryByName, "word/document.xml")
                ?: error("ملف DOCX غير صالح (word/document.xml مفقود).")
            val xmlBytes = readZipEntryBounded(zip, docEntry, maxTotalUncompressedBytes)
            onProgress(0.35f)

            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

            val sections = mutableListOf<ExtractedDocumentSection>()
            var currentSectionTitle = ""
            val currentSectionParagraphs = mutableListOf<String>()

            val currentPara = StringBuilder()
            var isHeadingStyle = false
            var isTopHeading = false
            var isListItem = false
            var inTable = false
            var inTableCell = false
            val currentTableRowCells = mutableListOf<String>()
            val currentCellBuf = StringBuilder()
            val langSample = StringBuilder()
            var totalChars = 0L

            fun flushSection(nextHeading: String = "") {
                if (currentSectionParagraphs.isNotEmpty()) {
                    val body = currentSectionParagraphs.joinToString("\n\n").trim()
                    if (body.isNotEmpty()) {
                        sections.add(ExtractedDocumentSection(currentSectionTitle, body))
                    }
                    currentSectionParagraphs.clear()
                }
                currentSectionTitle = nextHeading
            }

            fun appendParagraph(raw: String) {
                val trimmed = raw.replace(Regex("""[ \t]+"""), " ").trim()
                if (trimmed.isEmpty()) return
                if (langSample.length < 3000) langSample.append(trimmed).append(' ')
                if (isTopHeading && trimmed.length <= 160) {
                    flushSection(trimmed)
                } else {
                    val formatted = if (isListItem && !trimmed.startsWith("•")) "• $trimmed" else trimmed
                    currentSectionParagraphs.add(formatted)
                }
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val local = parser.name?.substringAfter(':').orEmpty()
                        when (local) {
                            "tbl" -> inTable = true
                            "tr" -> if (inTable) currentTableRowCells.clear()
                            "tc" -> if (inTable) {
                                inTableCell = true
                                currentCellBuf.clear()
                            }
                            "p" -> {
                                currentPara.clear()
                                isHeadingStyle = false
                                isTopHeading = false
                                isListItem = false
                            }
                            "pStyle" -> {
                                val styleVal = parser.getAttributeValue(null, "val")
                                    ?: (0 until parser.attributeCount)
                                        .firstOrNull { parser.getAttributeName(it) == "val" }
                                        ?.let { parser.getAttributeValue(it) }
                                    .orEmpty()
                                val lower = styleVal.lowercase()
                                if (lower.startsWith("heading") || lower == "title" || lower.startsWith("titre")) {
                                    isHeadingStyle = true
                                    isTopHeading = lower == "heading1" || lower == "heading2" ||
                                        lower == "title" || lower == "titre1" || lower == "titre2"
                                }
                            }
                            "numPr" -> isListItem = true
                            "br", "cr" -> {
                                if (inTableCell) currentCellBuf.append(' ') else currentPara.append('\n')
                            }
                            "tab" -> {
                                if (inTableCell) currentCellBuf.append(' ') else currentPara.append(' ')
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text.orEmpty()
                        if (txt.isNotEmpty()) {
                            totalChars += txt.length
                            if (totalChars > maxExtractedTextChars) {
                                error("حجم النص في ملف DOCX يتجاوز الحد المسموح للمعالجة.")
                            }
                            if (inTableCell) currentCellBuf.append(txt) else currentPara.append(txt)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val local = parser.name?.substringAfter(':').orEmpty()
                        when (local) {
                            "p" -> {
                                if (inTableCell) {
                                    if (currentCellBuf.isNotEmpty() && !currentCellBuf.endsWith(" ")) {
                                        currentCellBuf.append(' ')
                                    }
                                } else {
                                    appendParagraph(currentPara.toString())
                                    currentPara.clear()
                                    if (!isHeadingStyle && currentSectionParagraphs.sumOf { it.length } >= 3600) {
                                        val nextIdx = sections.size + 1
                                        flushSection("Section ${nextIdx + 1}")
                                    }
                                }
                            }
                            "tc" -> if (inTable && inTableCell) {
                                val cellText = currentCellBuf.toString().replace(Regex("""\s+"""), " ").trim()
                                if (cellText.isNotEmpty()) currentTableRowCells.add(cellText)
                                currentCellBuf.clear()
                                inTableCell = false
                            }
                            "tr" -> if (inTable && currentTableRowCells.isNotEmpty()) {
                                val rowText = currentTableRowCells.joinToString(" — ")
                                if (langSample.length < 3000) langSample.append(rowText).append(' ')
                                currentSectionParagraphs.add(rowText)
                                currentTableRowCells.clear()
                            }
                            "tbl" -> inTable = false
                        }
                    }
                }
                event = parser.next()
            }

            if (currentPara.isNotBlank()) appendParagraph(currentPara.toString())
            flushSection("")
            if (sections.isEmpty()) error("ملف DOCX لا يحتوي على نص قابل للاستيراد.")

            val lang = LanguageDetector.detect(langSample.toString(), null)
            val title = coreMeta.title.ifBlank { rawFileName.substringBeforeLast('.').ifBlank { "مستند Word" } }
            val author = coreMeta.author.ifBlank { "مستند Word (DOCX)" }
            onProgress(1f)

            return ParsedSourceDocument(
                fileName = rawFileName,
                title = title,
                author = author,
                sourceType = "DOCX",
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

    private fun parseCoreMetadata(bytes: ByteArray): CoreMetadata {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var title = ""
        var author = ""
        var currentTag = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> currentTag = parser.name?.lowercase().orEmpty()
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (currentTag == "title" && title.isEmpty()) title = text
                        if (currentTag == "creator" && author.isEmpty()) author = text
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            event = parser.next()
        }
        return CoreMetadata(title, author)
    }
}
