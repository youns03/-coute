package com.example.translation

import android.content.Context
import android.net.Uri
import com.example.data.ParsedSourceDocument
import com.example.data.TranslationChunkEntity
import com.example.domain.FrenchTextPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

enum class ExportMode(val labelAr: String, val fileSuffix: String) {
    ARABIC_COMPLETED_ONLY("الترجمة العربية (الأجزاء المنجزة فقط)", "_ar_completed.txt"),
    BILINGUAL_COMPLETED_ONLY("ثنائي اللغة (الأجزاء المنجزة فقط)", "_bilingual_completed.txt"),
    BILINGUAL_WITH_PLACEHOLDERS("مسودة كاملة (تشمل غير المترجم بوسم توضيحي)", "_bilingual_draft.txt")
}

object DocumentChunker {

    /**
     * Splits a parsed TXT or EPUB document into bounded translation batches while preserving
     * section headings and paragraph structure. Never alters [originalText].
     * Guarantees every body chunk has length <= [maxCharsPerChunk].
     */
    fun buildChunks(
        jobId: String,
        doc: ParsedSourceDocument,
        maxCharsPerChunk: Int = 650
    ): List<TranslationChunkEntity> {
        val effectiveLimit = maxCharsPerChunk.coerceAtLeast(1)
        val chunks = mutableListOf<TranslationChunkEntity>()
        var chunkIdx = 0

        doc.sections.forEach { section ->
            val cleanSectionTitle = section.title.trim()
            if (cleanSectionTitle.isNotBlank()) {
                chunks.add(
                    TranslationChunkEntity(
                        jobId = jobId,
                        chunkIndex = chunkIdx++,
                        chapterTitle = cleanSectionTitle,
                        paragraphIndex = -1,
                        isHeading = true,
                        originalText = cleanSectionTitle
                    )
                )
            }

            val paragraphs = section.body
                .replace("\r\n", "\n")
                .split(Regex("""\n\s*\n+"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            paragraphs.forEachIndexed { pIdx, para ->
                if (para.length <= effectiveLimit) {
                    chunks.add(
                        TranslationChunkEntity(
                            jobId = jobId,
                            chunkIndex = chunkIdx++,
                            chapterTitle = cleanSectionTitle,
                            paragraphIndex = pIdx,
                            isHeading = false,
                            originalText = para
                        )
                    )
                } else {
                    val sentences = FrenchTextPreprocessor.splitIntoSentences(para)
                    val expandedSegments = sentences.flatMap { splitOversizedText(it, effectiveLimit) }
                    val batch = StringBuilder()
                    for (segment in expandedSegments) {
                        if (batch.isNotEmpty() && batch.length + segment.length + 1 > effectiveLimit) {
                            chunks.add(
                                TranslationChunkEntity(
                                    jobId = jobId,
                                    chunkIndex = chunkIdx++,
                                    chapterTitle = cleanSectionTitle,
                                    paragraphIndex = pIdx,
                                    isHeading = false,
                                    originalText = batch.toString().trim()
                                )
                            )
                            batch.clear()
                        }
                        if (batch.isNotEmpty()) batch.append(' ')
                        batch.append(segment)
                    }
                    if (batch.isNotEmpty()) {
                        chunks.add(
                            TranslationChunkEntity(
                                jobId = jobId,
                                chunkIndex = chunkIdx++,
                                chapterTitle = cleanSectionTitle,
                                paragraphIndex = pIdx,
                                isHeading = false,
                                originalText = batch.toString().trim()
                            )
                        )
                    }
                }
            }
        }

        return chunks
    }

    internal fun splitOversizedText(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val parts = mutableListOf<String>()
        var start = 0
        while (start < trimmed.length) {
            val remaining = trimmed.length - start
            if (remaining <= maxChars) {
                val tail = trimmed.substring(start).trim()
                if (tail.isNotEmpty()) parts.add(tail)
                break
            }
            val hardEnd = start + maxChars
            // Prefer breaking at the last whitespace in the upper half of the window
            val minWordBreak = start + (maxChars / 2)
            var splitAt = -1
            for (i in hardEnd downTo minWordBreak) {
                if (trimmed[i].isWhitespace()) {
                    splitAt = i
                    break
                }
            }
            val sliceEnd = if (splitAt > start) splitAt else hardEnd
            val slice = trimmed.substring(start, sliceEnd).trim()
            if (slice.isNotEmpty()) parts.add(slice)
            start = sliceEnd
            while (start < trimmed.length && trimmed[start].isWhitespace()) {
                start++
            }
        }
        return parts
    }

    fun formatForExport(
        title: String,
        direction: TranslationDirection,
        chunks: List<TranslationChunkEntity>,
        mode: ExportMode
    ): String {
        val completedOnly = mode != ExportMode.BILINGUAL_WITH_PLACEHOLDERS
        val exportChunks = if (completedOnly) {
            chunks.filter { it.isCompleted && !it.translatedText.isNullOrBlank() }
        } else {
            chunks
        }
        if (completedOnly && exportChunks.isEmpty()) {
            error("لا توجد أجزاء مترجمة مكتملة للتصدير بعد.")
        }

        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("الاتجاه: ").append(direction.displayNameAr).append('\n')
        if (completedOnly && exportChunks.size < chunks.size) {
            sb.append("نطاق التصدير: الأجزاء المنجزة فقط (${exportChunks.size} من ${chunks.size})\n")
        }
        sb.append("=".repeat(36)).append("\n\n")

        var lastParagraphKey: Pair<String, Int>? = null
        exportChunks.forEach { chunk ->
            val translated = chunk.translatedText?.takeIf { it.isNotBlank() }
                ?: "[لم يُترجم هذا الجزء بعد]"

            if (chunk.isHeading) {
                if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append('\n')
                when (mode) {
                    ExportMode.ARABIC_COMPLETED_ONLY -> sb.append("## ").append(translated).append("\n\n")
                    ExportMode.BILINGUAL_COMPLETED_ONLY,
                    ExportMode.BILINGUAL_WITH_PLACEHOLDERS ->
                        sb.append("## ").append(chunk.originalText)
                            .append('\n').append("## ").append(translated).append("\n\n")
                }
                lastParagraphKey = null
            } else {
                val pKey = chunk.chapterTitle to chunk.paragraphIndex
                val sameParagraphAsPrev = lastParagraphKey == pKey
                if (mode == ExportMode.ARABIC_COMPLETED_ONLY) {
                    if (sameParagraphAsPrev && sb.endsWith("\n\n")) {
                        sb.delete(sb.length - 1, sb.length)
                        sb.append(' ')
                    }
                    sb.append(translated).append("\n\n")
                } else {
                    sb.append(chunk.originalText).append('\n')
                    sb.append("← ").append(translated).append("\n\n")
                }
                lastParagraphKey = pKey
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    suspend fun exportToUri(
        context: Context,
        targetUri: Uri,
        content: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(targetUri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write("\uFEFF") // UTF-8 BOM for universal compatibility with Arabic readers
                    writer.write(content)
                    writer.flush()
                }
            } ?: error("تعذر فتح ملف التصدير للكتابة.")
            Unit
        }
    }
}
