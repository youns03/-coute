package com.example.data.importengine

import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File

/**
 * Semantic HTML / HTM / XHTML extractor powered by Jsoup.
 * Strips scripts, styles, SVG/canvas, hidden elements, and navigation chrome cleanly
 * while preserving document headings, paragraphs, lists, blockquotes, and tables in DOM order.
 */
internal object HtmlDocumentParser {

    private val noiseSelector =
        "script, style, noscript, template, svg, canvas, iframe, head, link, meta, [hidden], [aria-hidden=true]"

    private val blockTags = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6",
        "p", "blockquote", "pre", "li", "dt", "dd", "figcaption", "tr"
    )

    suspend fun parseFile(
        file: File,
        rawFileName: String,
        maxExtractedTextChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        currentCoroutineContext().ensureActive()
        val bytes = file.readBytes()
        val decoded = CharsetDetection.decodeBytes(bytes)
        onProgress(0.35f)
        currentCoroutineContext().ensureActive()

        val doc = Jsoup.parse(decoded.text)
        val extracted = extractStructuredDocument(
            doc = doc,
            fallbackTitle = rawFileName.substringBeforeLast('.').ifBlank { "صفحة HTML" },
            maxExtractedTextChars = maxExtractedTextChars
        )
        onProgress(1f)

        val sample = extracted.sections.take(3).joinToString(" ") { it.body }.take(3000)
        val lang = LanguageDetector.detect(sample, extracted.htmlLang)

        return ParsedSourceDocument(
            fileName = rawFileName,
            title = extracted.title,
            author = extracted.author.ifBlank { "HTML (${decoded.charsetLabel})" },
            sourceType = "HTML",
            detectedLanguage = lang.translationSourceLanguage,
            detectedLanguageCode = lang.languageCode,
            sections = extracted.sections,
            diagnostics = ExtractionDiagnostics(
                detectedLanguageCode = lang.languageCode,
                translationSourceLanguage = lang.translationSourceLanguage,
                detectedCharset = decoded.charsetLabel
            )
        )
    }

    internal data class ExtractedHtmlResult(
        val title: String,
        val author: String,
        val htmlLang: String?,
        val sections: List<ExtractedDocumentSection>
    )

    internal fun extractStructuredDocument(
        doc: Document,
        fallbackTitle: String,
        maxExtractedTextChars: Int
    ): ExtractedHtmlResult {
        val docTitle = doc.title().trim()
        val authorMeta = doc.selectFirst("meta[name=author]")?.attr("content")?.trim().orEmpty()
        val htmlLang = doc.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("body")?.attr("lang")?.takeIf { it.isNotBlank() }

        // Remove scripts, styles, hidden elements before reading body
        doc.select(noiseSelector).remove()
        doc.select("[style]").forEach { el ->
            val style = el.attr("style").replace(" ", "").lowercase()
            if (style.contains("display:none") || style.contains("visibility:hidden")) {
                el.remove()
            }
        }

        // Choose main/article root if clearly richer than boilerplate nav/footer
        val mainRoot = doc.selectFirst("main, article")
        val root: Element = if (mainRoot != null && mainRoot.text().trim().length >= 160) {
            mainRoot
        } else {
            val body = doc.body() ?: doc
            // Strip obvious navigation/footer blocks if remaining text stays substantial
            val clone = body.clone()
            clone.select("nav, footer").remove()
            if (clone.text().trim().length >= 80) clone else body
        }

        val sections = mutableListOf<ExtractedDocumentSection>()
        var currentSectionTitle = ""
        val paragraphs = mutableListOf<String>()
        var totalChars = 0L

        fun appendParagraph(text: String) {
            val clean = normalizeWhitespace(text)
            if (clean.isEmpty()) return
            totalChars += clean.length
            if (totalChars > maxExtractedTextChars) {
                error("النص المستخرج من HTML يتجاوز الحد الأقصى المسموح ($maxExtractedTextChars حرفاً).")
            }
            paragraphs.add(clean)
        }

        fun flushSection(nextTitle: String) {
            if (paragraphs.isNotEmpty()) {
                val body = paragraphs.joinToString("\n\n").trim()
                if (body.isNotEmpty()) {
                    sections.add(ExtractedDocumentSection(currentSectionTitle, body))
                }
                paragraphs.clear()
            }
            currentSectionTitle = nextTitle
        }

        fun walk(node: Node) {
            when (node) {
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when (tag) {
                        "h1", "h2" -> {
                            val heading = normalizeWhitespace(node.text())
                            if (heading.isNotEmpty()) {
                                flushSection(heading)
                            }
                        }
                        "h3", "h4", "h5", "h6" -> {
                            val sub = normalizeWhitespace(node.text())
                            if (sub.isNotEmpty()) appendParagraph(sub)
                        }
                        "li" -> {
                            val parentTag = node.parent()?.tagName()?.lowercase()
                            val itemText = normalizeWhitespace(extractDirectOrSubBlockText(node))
                            if (itemText.isNotEmpty()) {
                                val prefix = if (parentTag == "ol") "${node.elementSiblingIndex() + 1}. " else "• "
                                appendParagraph("$prefix$itemText")
                            }
                        }
                        "tr" -> {
                            val cells = node.select("> th, > td")
                                .map { normalizeWhitespace(it.text()) }
                                .filter { it.isNotEmpty() }
                            if (cells.isNotEmpty()) {
                                appendParagraph(cells.joinToString(" — "))
                            }
                        }
                        "p", "blockquote", "pre", "dt", "dd", "figcaption" -> {
                            // If a <p> wraps inner block tags, descend instead of flattening
                            if (node.children().any { it.tagName().lowercase() in blockTags }) {
                                node.childNodes().forEach(::walk)
                            } else {
                                appendParagraph(extractTextWithLineBreaks(node))
                            }
                        }
                        else -> {
                            // Generic container (div, section, span, etc.): capture direct inline text runs around child blocks
                            val inlineRun = StringBuilder()
                            for (child in node.childNodes()) {
                                if (child is Element && containsBlockTag(child)) {
                                    if (inlineRun.isNotBlank()) {
                                        appendParagraph(inlineRun.toString())
                                        inlineRun.clear()
                                    }
                                    walk(child)
                                } else {
                                    inlineRun.append(extractInlineNodeText(child))
                                }
                            }
                            if (inlineRun.isNotBlank()) {
                                appendParagraph(inlineRun.toString())
                            }
                        }
                    }
                }
                is TextNode -> {
                    val txt = normalizeWhitespace(node.text())
                    if (txt.isNotEmpty()) appendParagraph(txt)
                }
            }
        }

        walk(root)
        flushSection("")

        if (sections.isEmpty()) {
            error("لم يتم العثور على محتوى نصي صالح داخل ملف HTML.")
        }

        val firstHeading = sections.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
        val finalTitle = docTitle.ifBlank { firstHeading.ifBlank { fallbackTitle } }

        return ExtractedHtmlResult(
            title = finalTitle,
            author = authorMeta,
            htmlLang = htmlLang,
            sections = sections
        )
    }

    private fun containsBlockTag(el: Element): Boolean {
        if (el.tagName().lowercase() in blockTags) return true
        return el.selectFirst(blockTags.joinToString(",")) != null
    }

    private fun extractDirectOrSubBlockText(el: Element): String {
        val nestedList = el.selectFirst("ul, ol")
        return if (nestedList == null) {
            extractTextWithLineBreaks(el)
        } else {
            val clone = el.clone()
            clone.select("ul, ol").remove()
            extractTextWithLineBreaks(clone)
        }
    }

    private fun extractTextWithLineBreaks(el: Element): String {
        val sb = StringBuilder()
        for (child in el.childNodes()) {
            sb.append(extractInlineNodeText(child))
        }
        return sb.toString()
    }

    private fun extractInlineNodeText(node: Node): String {
        return when (node) {
            is TextNode -> node.text()
            is Element -> when (node.tagName().lowercase()) {
                "br" -> " "
                else -> buildString {
                    for (c in node.childNodes()) {
                        append(extractInlineNodeText(c))
                    }
                }
            }
            else -> ""
        }
    }

    private fun normalizeWhitespace(raw: String): String =
        raw.replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
}
