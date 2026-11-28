package com.example.data.importengine

import com.example.domain.ExtractedDocumentSection
import com.example.domain.ExtractionDiagnostics
import com.example.domain.ParsedSourceDocument
import com.example.domain.SubtitleCue
import com.example.domain.SupportedImportFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.parser.Parser
import java.io.File

/**
 * Clean SRT & WebVTT transcript parser.
 * - Handles UTF-8 BOM, UTF-16, Windows-1256 (Arabic), and Windows-1252 (French/English).
 * - Strips WEBVTT header, NOTE/STYLE/REGION blocks, cue coordinates, and HTML/VTT formatting tags.
 * - Sorts cues strictly by chronological start timestamp and groups into readable sections with time ranges.
 */
internal object SubtitleTranscriptParser {

    private data class ParsedCue(
        val originalOrder: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private val timestampLineRegex = Regex(
        """^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{3})(.*)$"""
    )
    private val vttVoiceTagRegex = Regex("""<v(?:\.[^>\s]+)?\s+([^>]+)>""", RegexOption.IGNORE_CASE)
    private val inlineTagRegex = Regex("""</?(?:i|b|u|c|lang|ruby|rt|font)[^>]*>|<\d{2}:\d{2}:\d{2}\.\d{3}>""", RegexOption.IGNORE_CASE)

    suspend fun parseFile(
        file: File,
        rawFileName: String,
        format: SupportedImportFormat,
        maxExtractedTextChars: Int,
        onProgress: (Float) -> Unit = {}
    ): ParsedSourceDocument {
        currentCoroutineContext().ensureActive()
        val decoded = CharsetDetection.decodeBytes(file.readBytes())
        onProgress(0.4f)

        val cues = if (format == SupportedImportFormat.VTT) {
            parseVttCues(decoded.text)
        } else {
            parseSrtCues(decoded.text)
        }

        if (cues.isEmpty()) {
            error("لم يتم العثور على مقاطع زمنية صالحة داخل ملف ${format.code}.")
        }

        // Guarantee strict chronological ordering even if source cues were slightly out of order
        val ordered = cues.sortedWith(compareBy<ParsedCue>({ it.startMs }, { it.endMs }, { it.originalOrder }))

        var totalChars = 0L
        val normalizedCues = ordered.mapIndexed { idx, cue ->
            totalChars += cue.text.length
            if (totalChars > maxExtractedTextChars) {
                error("حجم النص المستخرج من ${format.code} يتجاوز الحد الأقصى المسموح ($maxExtractedTextChars حرفاً).")
            }
            SubtitleCue(
                index = idx + 1,
                startMs = cue.startMs,
                endMs = cue.endMs,
                text = cue.text
            )
        }

        val sections = buildTimedSections(normalizedCues)
        val sample = normalizedCues.take(40).joinToString(" ") { it.text }.take(3000)
        val lang = LanguageDetector.detect(sample, null)
        val title = rawFileName.substringBeforeLast('.').ifBlank { "تفريغ ${format.code}" }
        onProgress(1f)

        return ParsedSourceDocument(
            fileName = rawFileName,
            title = title,
            author = "تفريغ نصي (${format.code} • ${decoded.charsetLabel})",
            sourceType = format.code,
            detectedLanguage = lang.translationSourceLanguage,
            detectedLanguageCode = lang.languageCode,
            sections = sections,
            diagnostics = ExtractionDiagnostics(
                detectedLanguageCode = lang.languageCode,
                translationSourceLanguage = lang.translationSourceLanguage,
                detectedCharset = decoded.charsetLabel,
                subtitleCues = normalizedCues
            )
        )
    }

    private fun parseSrtCues(rawText: String): List<ParsedCue> {
        val blocks = rawText.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("""\n\s*\n+"""))
        val cues = mutableListOf<ParsedCue>()
        var order = 0

        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val timeIdx = lines.indexOfFirst { timestampLineRegex.matches(it.trim()) }
            if (timeIdx < 0) continue

            val match = timestampLineRegex.matchEntire(lines[timeIdx].trim()) ?: continue
            val startMs = parseTimestampMs(match, groupOffset = 1)
            val endMs = parseTimestampMs(match, groupOffset = 5)
            val bodyLines = lines.drop(timeIdx + 1)
            val cleaned = cleanCueLines(bodyLines)
            if (cleaned.isNotEmpty()) {
                cues.add(ParsedCue(order++, startMs, endMs, cleaned))
            }
        }
        return cues
    }

    private fun parseVttCues(rawText: String): List<ParsedCue> {
        val normalized = rawText.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.split(Regex("""\n\s*\n+"""))
        val cues = mutableListOf<ParsedCue>()
        var order = 0

        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val head = lines.first().trim()
            if (head.startsWith("WEBVTT", ignoreCase = true) ||
                head.startsWith("NOTE", ignoreCase = true) ||
                head.startsWith("STYLE", ignoreCase = true) ||
                head.startsWith("REGION", ignoreCase = true)
            ) {
                continue
            }

            val timeIdx = lines.indexOfFirst { timestampLineRegex.matches(it.trim()) }
            if (timeIdx < 0) continue

            val match = timestampLineRegex.matchEntire(lines[timeIdx].trim()) ?: continue
            val startMs = parseTimestampMs(match, groupOffset = 1)
            val endMs = parseTimestampMs(match, groupOffset = 5)
            val bodyLines = lines.drop(timeIdx + 1)
            val cleaned = cleanCueLines(bodyLines)
            if (cleaned.isNotEmpty()) {
                cues.add(ParsedCue(order++, startMs, endMs, cleaned))
            }
        }
        return cues
    }

    private fun parseTimestampMs(match: MatchResult, groupOffset: Int): Long {
        val hours = match.groupValues[groupOffset].toLongOrNull() ?: 0L
        val minutes = match.groupValues[groupOffset + 1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[groupOffset + 2].toLongOrNull() ?: 0L
        val millis = match.groupValues[groupOffset + 3].toLongOrNull() ?: 0L
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun cleanCueLines(lines: List<String>): String {
        val joined = lines.joinToString(" ").trim()
        if (joined.isEmpty()) return ""

        var text = joined
        // Convert <v Speaker>Line</v> -> "Speaker: Line" when a speaker label is present
        val voiceMatch = vttVoiceTagRegex.find(text)
        val speakerPrefix = voiceMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        text = vttVoiceTagRegex.replace(text, "")
        text = text.replace("</v>", "", ignoreCase = true)
        text = inlineTagRegex.replace(text, "")
        // Also strip ASS/SSA brace formatting like {\an8}
        text = text.replace(Regex("""\{\\[^}]+\}"""), "")
        text = Parser.unescapeEntities(text, false)
        text = text.replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return ""

        return if (speakerPrefix != null && !text.startsWith("$speakerPrefix:")) {
            "$speakerPrefix: $text"
        } else {
            text
        }
    }

    private fun buildTimedSections(cues: List<SubtitleCue>): List<ExtractedDocumentSection> {
        val sections = mutableListOf<ExtractedDocumentSection>()
        val bucket = mutableListOf<SubtitleCue>()
        var bucketChars = 0

        fun flushBucket() {
            if (bucket.isEmpty()) return
            val rangeLabel = "${formatMillis(bucket.first().startMs)} → ${formatMillis(bucket.last().endMs)}"
            val sectionIdx = sections.size + 1
            val body = bucket.joinToString("\n\n") { it.text }
            sections.add(
                ExtractedDocumentSection(
                    title = "المقطع $sectionIdx • $rangeLabel",
                    body = body,
                    sourceAnchor = rangeLabel
                )
            )
            bucket.clear()
            bucketChars = 0
        }

        for (cue in cues) {
            if (bucket.isNotEmpty() && (bucket.size >= 24 || bucketChars + cue.text.length > 2400)) {
                flushBucket()
            }
            bucket.add(cue)
            bucketChars += cue.text.length + 2
        }
        flushBucket()
        return sections
    }

    internal fun formatMillis(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) {
            "%02d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }
}
