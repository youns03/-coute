package com.example.domain

import java.text.Normalizer

/**
 * Converts TTS character ranges (which refer to spokenText) into ranges in the
 * unchanged originalText shown by the reader.
 *
 * TTS preprocessing may expand one source token into several spoken tokens, for
 * example "Dr. 12" into "Doctor twelve". A monotonic token alignment keeps all
 * of those spoken tokens attached to the source token without changing what the
 * reader displays.
 */
object SpeechTimestampAligner {

    private data class Token(val start: Int, val end: Int, val key: String)

    private val tokenRegex = Regex("""[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*""")

    fun align(
        originalText: String,
        spokenText: String,
        timestamps: List<WordTimestampRange>
    ): List<WordTimestampRange> {
        if (timestamps.isEmpty()) return emptyList()
        if (originalText.isBlank() || spokenText.isBlank()) return emptyList()

        val originalTokens = tokens(originalText)
        val spokenTokens = tokens(spokenText)
        if (originalTokens.isEmpty() || spokenTokens.isEmpty()) return emptyList()

        val spokenToOriginal = buildTokenAlignment(originalTokens, spokenTokens)
        return timestamps.mapNotNull { timestamp ->
            val spokenStart = timestamp.startChar.coerceIn(0, spokenText.length)
            val spokenEnd = timestamp.endChar.coerceIn(spokenStart, spokenText.length)
            if (spokenEnd <= spokenStart) return@mapNotNull null

            val covered = spokenTokens.indices.filter { index ->
                spokenTokens[index].end > spokenStart && spokenTokens[index].start < spokenEnd
            }
            val tokenIndices = if (covered.isNotEmpty()) {
                covered
            } else {
                // A few engines report a word string but an imprecise/empty range.
                // Use the nearest spoken token rather than dropping the timestamp.
                listOf(nearestTokenIndex(spokenTokens, spokenStart, spokenEnd))
            }
            val originalIndices = tokenIndices.map { spokenToOriginal[it] }.distinct()
            if (originalIndices.isEmpty()) return@mapNotNull null

            val startToken = originalTokens[originalIndices.minOrNull()!!]
            val endToken = originalTokens[originalIndices.maxOrNull()!!]
            timestamp.copy(
                word = originalText.substring(startToken.start, endToken.end),
                startChar = startToken.start,
                endChar = endToken.end
            )
        }.distinctBy { Triple(it.startChar, it.endChar, it.startSec) }
    }

    private fun tokens(text: String): List<Token> = tokenRegex.findAll(text).map { match ->
        Token(match.range.first, match.range.last + 1, normalize(match.value))
    }.toList()

    private fun buildTokenAlignment(
        original: List<Token>,
        spoken: List<Token>
    ): IntArray {
        val result = IntArray(spoken.size)
        var originalCursor = 0
        var foundExactMatch = false

        spoken.forEachIndexed { spokenIndex, spokenToken ->
            val exact = (originalCursor until original.size)
                .take(8)
                .firstOrNull { original[it].key == spokenToken.key }
            if (exact != null) {
                result[spokenIndex] = exact
                originalCursor = (exact + 1).coerceAtMost(original.lastIndex)
                foundExactMatch = true
            } else {
                // Expansion/folding ("2026" -> "two thousand twenty-six") belongs
                // to the current source token. Do not advance until a later exact
                // token gives us a reliable synchronization point.
                result[spokenIndex] = originalCursor.coerceIn(0, original.lastIndex)
            }
        }

        if (!foundExactMatch) {
            // Defensive fallback for an engine that normalizes every token. It is
            // monotonic and still produces stable visual feedback instead of none.
            spoken.indices.forEach { index ->
                result[index] = ((index * original.size) / spoken.size)
                    .coerceIn(0, original.lastIndex)
            }
        }
        return result
    }

    private fun nearestTokenIndex(tokens: List<Token>, start: Int, end: Int): Int {
        val center = (start + end) / 2
        return tokens.indices.minBy { index ->
            when {
                center < tokens[index].start -> tokens[index].start - center
                center > tokens[index].end -> center - tokens[index].end
                else -> 0
            }
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("’", "'")
        .lowercase()
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
