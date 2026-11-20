package com.example.translation

import android.content.Context
import android.net.Uri
import com.example.data.ParsedSourceDocument
import com.example.data.TranslationChunkEntity
import com.example.domain.FrenchTextPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

enum class ExportMode(val labelAr: String) {
    ARABIC_ONLY("الترجمة العربية فقط (ملف مستقل)"),
    BILINGUAL("النص الأصلي + الترجمة العربية")
}

object DocumentChunker {

    /**
     * Splits a parsed TXT or EPUB document into bounded translation batches while preserving
     * section headings and paragraph structure. Never alters [originalText].
     */
    fun buildChunks(
        jobId: String,
        doc: ParsedSourceDocument,
        maxCharsPerChunk: Int = 650
    ): List<TranslationChunkEntity> {
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
                if (para.length <= maxCharsPerChunk) {
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
                    // Long paragraph: group sentences into batches <= maxCharsPerChunk
                    val sentences = FrenchTextPreprocessor.splitIntoSentences(para)
                    val batch = StringBuilder()
                    for (sentence in sentences) {
                        if (batch.isNotEmpty() && batch.length + sentence.length + 1 > maxCharsPerChunk) {
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
                        batch.append(sentence)
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

    fun formatForExport(
        title: String,
        direction: TranslationDirection,
        chunks: List<TranslationChunkEntity>,
        mode: ExportMode
    ): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("الاتجاه: ").append(direction.displayNameAr).append('\n')
        sb.append("=".repeat(36)).append("\n\n")

        var lastParagraphKey: Pair<String, Int>? = null
        chunks.forEach { chunk ->
            val translated = chunk.translatedText?.takeIf { it.isNotBlank() }
                ?: "[لم يُترجم هذا الجزء بعد]"

            if (chunk.isHeading) {
                if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append('\n')
                when (mode) {
                    ExportMode.ARABIC_ONLY -> sb.append("## ").append(translated).append("\n\n")
                    ExportMode.BILINGUAL -> sb.append("## ").append(chunk.originalText)
                        .append('\n').append("## ").append(translated).append("\n\n")
                }
                lastParagraphKey = null
            } else {
                val pKey = chunk.chapterTitle to chunk.paragraphIndex
                val sameParagraphAsPrev = lastParagraphKey == pKey
                if (mode == ExportMode.ARABIC_ONLY) {
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
