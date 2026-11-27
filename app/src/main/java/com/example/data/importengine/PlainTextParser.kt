package com.example.data.importengine

import com.example.data.ExtractedDocumentSection
import com.example.data.ParsedSourceDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

internal object PlainTextParser {

    suspend fun parseFile(
        file: File,
        rawFileName: String,
        maxExtractedTextChars: Int,
        maxLineChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        val title = rawFileName.substringBeforeLast('.').ifBlank { "مستند نصي" }
        val sections = mutableListOf<ExtractedDocumentSection>()
        val sectionBuf = StringBuilder()
        val paragraphBuf = StringBuilder()
        val languageSampleBuf = StringBuilder()
        var sectionNum = 1
        var totalChars = 0L

        fun flushParagraph() {
            if (paragraphBuf.isNotBlank()) {
                sectionBuf.append(paragraphBuf.toString().trim()).append("\n\n")
                paragraphBuf.clear()
                if (sectionBuf.length >= 3500) {
                    sections.add(ExtractedDocumentSection("Section $sectionNum", sectionBuf.toString().trim()))
                    sectionBuf.clear()
                    sectionNum++
                }
            }
        }

        val (selection, bis) = CharsetDetection.openStreamSelection(file)
        val estimatedTotalChars = file.length().coerceAtLeast(1L).toFloat()

        BufferedReader(InputStreamReader(bis, selection.charset)).use { reader ->
            val lineReader = BoundedLineReader(reader, maxLineChars)
            var isFirstLine = true
            while (true) {
                currentCoroutineContext().ensureActive()
                val rawLine = lineReader.readNextSegment() ?: break
                val line = if (isFirstLine) {
                    isFirstLine = false
                    rawLine.removePrefix("\uFEFF")
                } else {
                    rawLine
                }

                totalChars += line.length + 1
                if (totalChars > maxExtractedTextChars) {
                    error("النص يتجاوز الحد الأقصى المسموح للمعالجة ($maxExtractedTextChars حرفاً).")
                }

                if (languageSampleBuf.length < 3000 && line.isNotBlank()) {
                    languageSampleBuf.append(line).append(' ')
                }

                if (line.isBlank()) {
                    flushParagraph()
                } else {
                    if (paragraphBuf.isNotEmpty()) paragraphBuf.append('\n')
                    paragraphBuf.append(line.trimEnd())
                }

                if (totalChars % 8192L < 256L) {
                    onProgress((totalChars.toFloat() / estimatedTotalChars).coerceIn(0.05f, 0.95f))
                }
            }
        }
        flushParagraph()

        if (sectionBuf.isNotBlank()) {
            val label = if (sectionNum == 1) "" else "Section $sectionNum"
            sections.add(ExtractedDocumentSection(label, sectionBuf.toString().trim()))
        }

        if (sections.isEmpty()) error("الملف النصي فارغ.")

        val lang = LanguageDetector.detect(languageSampleBuf.toString(), null)
        onProgress(1f)
        return ParsedSourceDocument(
            fileName = rawFileName,
            title = title,
            author = "ملف نصي (${selection.label})",
            sourceType = "TXT",
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
}

internal class BoundedLineReader(
    private val reader: BufferedReader,
    private val maxCharsPerSegment: Int
) {
    private val pending = StringBuilder()

    fun readNextSegment(): String? {
        while (true) {
            if (pending.length >= maxCharsPerSegment) {
                val slice = pending.substring(0, maxCharsPerSegment)
                val searchStart = (maxCharsPerSegment - 512).coerceAtLeast(0)
                val wsIdx = slice.lastIndexOf(' ', maxCharsPerSegment - 1)
                if (wsIdx >= searchStart && wsIdx > 0) {
                    val segment = pending.substring(0, wsIdx)
                    pending.delete(0, wsIdx + 1)
                    return segment
                }
                pending.delete(0, maxCharsPerSegment)
                return slice
            }

            val chInt = reader.read()
            if (chInt == -1) {
                return if (pending.isNotEmpty()) {
                    val out = pending.toString()
                    pending.clear()
                    out
                } else {
                    null
                }
            }
            val ch = chInt.toChar()
            if (ch == '\r') continue
            if (ch == '\n') {
                val out = pending.toString()
                pending.clear()
                return out
            }
            pending.append(ch)
        }
    }
}
