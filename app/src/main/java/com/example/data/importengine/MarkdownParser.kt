package com.example.data.importengine

import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Clean Markdown (.md / .markdown) parser that preserves headings as sections,
 * extracts YAML frontmatter metadata, formats lists/blockquotes/tables cleanly,
 * and strips noisy Markdown syntax (links, badges, emphasis markers) while keeping readable prose.
 */
internal object MarkdownParser {

    private val atxHeadingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val setextH1Regex = Regex("""^={3,}\s*$""")
    private val setextH2Regex = Regex("""^-{3,}\s*$""")
    private val thematicBreakRegex = Regex("""^\s{0,3}(?:([-*_])\s*){3,}$""")
    private val bulletListRegex = Regex("""^\s*[-*+]\s+(?:\[[ xX]\]\s+)?(.*)$""")
    private val orderedListRegex = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
    private val tableSeparatorRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    private val referenceLinkDefRegex = Regex("""^\s{0,3}\[[^\]]+]:\s+\S+.*$""")

    private val linkedBadgeRegex = Regex("""\[!\[[^\]]*]\([^)]*\)]\([^)]*\)""")
    private val imageRegex = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val inlineLinkRegex = Regex("""\[([^\]]+)]\((?:[^()]+|\([^)]*\))*\)""")
    private val refLinkUseRegex = Regex("""\[([^\]]+)]\[[^\]]*]""")
    private val inlineCodeRegex = Regex("""`([^`]+)`""")
    private val boldItalicRegex = Regex("""(\*\*\*|___)(.+?)\1""")
    private val boldRegex = Regex("""(\*\*|__)(.+?)\1""")
    private val italicRegex = Regex("""(?<!\*)\*([^*]+)\*(?!\*)|(?<!_)_([^_]+)_(?!_)""")
    private val strikeRegex = Regex("""~~(.+?)~~""")
    private val htmlTagRegex = Regex("""</?[a-zA-Z][^>]*>""")

    suspend fun parseFile(
        file: File,
        rawFileName: String,
        maxExtractedTextChars: Int,
        maxLineChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        val (selection, bis) = CharsetDetection.openStreamSelection(file)

        val sections = mutableListOf<ExtractedDocumentSection>()
        var currentSectionTitle = ""
        val currentParagraphs = mutableListOf<String>()
        val paraBuf = StringBuilder()
        val langSample = StringBuilder()

        var docTitle = ""
        var docAuthor = ""
        var totalChars = 0L

        var inFrontmatter = false
        var frontmatterEnded = false
        var lineIndex = 0
        var inFencedCode = false
        var fenceChar = '`'
        var prevNonBlankLineForSetext: String? = null

        fun flushParagraph() {
            if (paraBuf.isNotBlank()) {
                currentParagraphs.add(paraBuf.toString().trim())
                paraBuf.clear()
            }
        }

        fun flushSectionIfReady(nextTitle: String = "") {
            flushParagraph()
            if (currentParagraphs.isNotEmpty()) {
                val body = currentParagraphs.joinToString("\n\n").trim()
                if (body.isNotEmpty()) {
                    sections.add(
                        ExtractedDocumentSection(
                            title = currentSectionTitle,
                            body = body
                        )
                    )
                }
                currentParagraphs.clear()
            }
            currentSectionTitle = nextTitle
        }

        BufferedReader(InputStreamReader(bis, selection.charset)).use { reader ->
            val lineReader = BoundedLineReader(reader, maxLineChars)
            while (true) {
                currentCoroutineContext().ensureActive()
                val rawLine = lineReader.readNextSegment() ?: break
                val line = if (lineIndex == 0) rawLine.removePrefix("\uFEFF") else rawLine
                lineIndex++

                totalChars += line.length + 1
                if (totalChars > maxExtractedTextChars) {
                    error("حجم مستند Markdown يتجاوز الحد الأقصى المسموح ($maxExtractedTextChars حرفاً).")
                }

                // YAML frontmatter at very top of document
                if (lineIndex == 1 && line.trim() == "---") {
                    inFrontmatter = true
                    continue
                }
                if (inFrontmatter && !frontmatterEnded) {
                    val trimmed = line.trim()
                    if (trimmed == "---" || trimmed == "...") {
                        frontmatterEnded = true
                        inFrontmatter = false
                    } else {
                        val key = trimmed.substringBefore(':').trim().lowercase()
                        val value = trimmed.substringAfter(':', "").trim().trim('"', '\'')
                        if (key == "title" && value.isNotEmpty() && docTitle.isEmpty()) docTitle = value
                        if (key == "author" && value.isNotEmpty() && docAuthor.isEmpty()) docAuthor = value
                    }
                    continue
                }

                val trimmed = line.trim()

                // Fenced code blocks ``` or ~~~
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    flushParagraph()
                    val marker = trimmed[0]
                    if (!inFencedCode) {
                        inFencedCode = true
                        fenceChar = marker
                    } else if (marker == fenceChar) {
                        inFencedCode = false
                    }
                    prevNonBlankLineForSetext = null
                    continue
                }
                if (inFencedCode) {
                    if (trimmed.isNotEmpty()) {
                        if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                        paraBuf.append(trimmed)
                    }
                    continue
                }

                // Setext heading underline detection on previous line
                if (prevNonBlankLineForSetext != null && (setextH1Regex.matches(trimmed) || setextH2Regex.matches(trimmed))) {
                    val headingText = cleanInlineMarkdown(prevNonBlankLineForSetext!!)
                    prevNonBlankLineForSetext = null
                    paraBuf.clear()
                    if (headingText.isNotEmpty()) {
                        if (docTitle.isEmpty() && setextH1Regex.matches(trimmed)) docTitle = headingText
                        flushSectionIfReady(headingText)
                    }
                    continue
                }

                if (trimmed.isBlank()) {
                    prevNonBlankLineForSetext = null
                    flushParagraph()
                    continue
                }

                if (referenceLinkDefRegex.matches(trimmed) || thematicBreakRegex.matches(trimmed) || tableSeparatorRegex.matches(trimmed)) {
                    prevNonBlankLineForSetext = null
                    flushParagraph()
                    continue
                }

                // ATX Heading (# Title, ## Section)
                val atxMatch = atxHeadingRegex.matchEntire(trimmed)
                if (atxMatch != null) {
                    prevNonBlankLineForSetext = null
                    val level = atxMatch.groupValues[1].length
                    val heading = cleanInlineMarkdown(atxMatch.groupValues[2])
                    if (heading.isNotEmpty()) {
                        if (level == 1 && docTitle.isEmpty()) {
                            docTitle = heading
                        }
                        if (level <= 2) {
                            flushSectionIfReady(heading)
                        } else {
                            flushParagraph()
                            currentParagraphs.add(heading)
                        }
                        if (langSample.length < 3000) langSample.append(heading).append(' ')
                    }
                    continue
                }

                // Blockquote (> quote)
                if (trimmed.startsWith(">")) {
                    prevNonBlankLineForSetext = null
                    val quoteLine = cleanInlineMarkdown(trimmed.trimStart('>', ' '))
                    if (quoteLine.isNotEmpty()) {
                        if (paraBuf.isNotEmpty()) paraBuf.append(' ')
                        paraBuf.append(quoteLine)
                        if (langSample.length < 3000) langSample.append(quoteLine).append(' ')
                    }
                    continue
                }

                // Bullet or ordered list item -> keep each item crisp
                val bulletMatch = bulletListRegex.matchEntire(line)
                if (bulletMatch != null) {
                    prevNonBlankLineForSetext = null
                    flushParagraph()
                    val itemText = cleanInlineMarkdown(bulletMatch.groupValues[1])
                    if (itemText.isNotEmpty()) {
                        currentParagraphs.add("• $itemText")
                        if (langSample.length < 3000) langSample.append(itemText).append(' ')
                    }
                    continue
                }
                val orderedMatch = orderedListRegex.matchEntire(line)
                if (orderedMatch != null) {
                    prevNonBlankLineForSetext = null
                    flushParagraph()
                    val num = orderedMatch.groupValues[1]
                    val itemText = cleanInlineMarkdown(orderedMatch.groupValues[2])
                    if (itemText.isNotEmpty()) {
                        currentParagraphs.add("$num. $itemText")
                        if (langSample.length < 3000) langSample.append(itemText).append(' ')
                    }
                    continue
                }

                // Markdown table row -> convert columns cleanly
                if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
                    prevNonBlankLineForSetext = null
                    flushParagraph()
                    val cells = trimmed.trim('|').split('|')
                        .map { cleanInlineMarkdown(it) }
                        .filter { it.isNotEmpty() }
                    if (cells.isNotEmpty()) {
                        val rowText = cells.joinToString(" — ")
                        currentParagraphs.add(rowText)
                        if (langSample.length < 3000) langSample.append(rowText).append(' ')
                    }
                    continue
                }

                val cleaned = cleanInlineMarkdown(trimmed)
                if (cleaned.isNotEmpty()) {
                    prevNonBlankLineForSetext = if (paraBuf.isEmpty()) cleaned else null
                    if (paraBuf.isNotEmpty()) paraBuf.append(' ')
                    paraBuf.append(cleaned)
                    if (langSample.length < 3000) langSample.append(cleaned).append(' ')
                }
            }
        }

        flushSectionIfReady()
        if (sections.isEmpty()) error("ملف Markdown فارغ أو لا يحتوي على نص قابل للقراءة.")

        val resolvedTitle = docTitle.ifBlank { rawFileName.substringBeforeLast('.').ifBlank { "مستند Markdown" } }
        val resolvedAuthor = docAuthor.ifBlank { "Markdown (${selection.label})" }
        val lang = LanguageDetector.detect(langSample.toString(), null)
        onProgress(1f)

        return ParsedSourceDocument(
            fileName = rawFileName,
            title = resolvedTitle,
            author = resolvedAuthor,
            sourceType = "MD",
            detectedLanguage = lang.translationSourceLanguage,
            detectedLanguageCode = lang.languageCode,
            sections = sections,
            diagnostics = ExtractionDiagnostics(
                detectedLanguageCode = lang.languageCode,
                translationSourceLanguage = lang.translationSourceLanguage,
                detectedCharset = selection.label
            )
        )
    }

    internal fun cleanInlineMarkdown(raw: String): String {
        var text = raw.trim()
        text = linkedBadgeRegex.replace(text, "")
        text = imageRegex.replace(text) { m -> m.groupValues[1].trim() }
        text = inlineLinkRegex.replace(text) { m -> m.groupValues[1].trim() }
        text = refLinkUseRegex.replace(text) { m -> m.groupValues[1].trim() }
        text = boldItalicRegex.replace(text) { m -> m.groupValues[2] }
        text = boldRegex.replace(text) { m -> m.groupValues[2] }
        text = italicRegex.replace(text) { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2] }
        }
        text = strikeRegex.replace(text) { m -> m.groupValues[1] }
        text = inlineCodeRegex.replace(text) { m -> m.groupValues[1] }
        text = htmlTagRegex.replace(text, " ")
        return text
            .replace("""\*""", "*")
            .replace("""\_""", "_")
            .replace("""\#""", "#")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}
