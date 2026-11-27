package com.example.domain

import java.text.Normalizer

/**
 * Language-agnostic text preprocessing facade separated cleanly from TTS engines.
 * Keeps `originalText` untouched for visual reading while producing clean TTS-ready `spokenText`
 * routed by the book/text language (`fr` or `en`).
 */
object SpeechTextProcessor {

    fun splitAndPreprocess(rawText: String, languageCode: String): List<ProcessedSentence> {
        return when (normalizeLangCode(languageCode)) {
            "en" -> EnglishTextPreprocessor.splitAndPreprocess(rawText)
            "ar" -> ArabicTextPreprocessor.splitAndPreprocess(rawText)
            else -> FrenchTextPreprocessor.splitAndPreprocess(rawText)
        }
    }

    fun prepareForSpeech(originalText: String, languageCode: String): String {
        return when (normalizeLangCode(languageCode)) {
            "en" -> EnglishTextPreprocessor.prepareForSpeech(originalText)
            "ar" -> ArabicTextPreprocessor.prepareForSpeech(originalText)
            else -> FrenchTextPreprocessor.prepareForSpeech(originalText)
        }
    }

    fun normalizeLangCode(code: String?): String {
        val lower = code.orEmpty().trim().lowercase()
        return when {
            lower.startsWith("en") -> "en"
            lower.startsWith("ar") -> "ar"
            else -> "fr"
        }
    }

    /**
     * Splits an unusually long normalized speech string at natural clause punctuation
     * so TTS synthesis maintains fast first-audio latency and natural breath phrasing.
     */
    fun splitLongSpokenClauses(spokenText: String, maxChars: Int = 220): List<String> {
        val clean = spokenText.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)

        val chunks = mutableListOf<String>()
        var remaining = clean
        while (remaining.length > maxChars) {
            val windowEnd = maxChars.coerceAtMost(remaining.length)
            val minPivot = (maxChars * 0.45f).toInt()
            val punctIdx = remaining.substring(minPivot, windowEnd)
                .indexOfLast { it == ';' || it == ':' || it == ',' }
            val splitAt = if (punctIdx >= 0) {
                minPivot + punctIdx + 1
            } else {
                val spaceIdx = remaining.substring(minPivot, windowEnd).indexOfLast { it.isWhitespace() }
                if (spaceIdx >= 0) minPivot + spaceIdx else windowEnd
            }
            val piece = remaining.substring(0, splitAt).trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            remaining = remaining.substring(splitAt).trimStart()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }
}

object EnglishTextPreprocessor {

    private val nonBreakingAbbreviations = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.",
        "etc.", "e.g.", "i.e.", "vs.", "fig.", "p.", "pp.", "vol.", "ch.",
        "u.s.", "u.k.", "no.", "inc.", "ltd."
    )

    private val abbreviationExpansions = linkedMapOf(
        Regex("""\bMr\.\s*""") to "Mister ",
        Regex("""\bMrs\.\s*""") to "Missus ",
        Regex("""\bMs\.\s*""") to "Miss ",
        Regex("""\bDr\.\s*""") to "Doctor ",
        Regex("""\bProf\.\s*""") to "Professor ",
        Regex("""\bvs\.\s*""", RegexOption.IGNORE_CASE) to "versus ",
        Regex("""\be\.g\.,?\s*""", RegexOption.IGNORE_CASE) to "for example ",
        Regex("""\bi\.e\.,?\s*""", RegexOption.IGNORE_CASE) to "that is ",
        Regex("""\betc\.""", RegexOption.IGNORE_CASE) to "et cetera",
        Regex("""\bNo\.\s*(?=\d)""") to "number "
    )

    fun splitAndPreprocess(rawText: String): List<ProcessedSentence> {
        val normalizedNewlines = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalizedNewlines
            .split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val output = mutableListOf<ProcessedSentence>()
        var sentenceCounter = 0
        var readingParagraphIndex = 0

        paragraphs.forEach { paragraph ->
            val cleanParagraph = paragraph
                .replace(Regex("""\n+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val readingParagraphs = ReadingParagraphSegmenter.segment(splitIntoSentences(cleanParagraph))
            readingParagraphs.forEach { readingParagraph ->
                readingParagraph.forEach { original ->
                    if (original.isNotBlank()) {
                        output.add(
                            ProcessedSentence(
                                index = sentenceCounter++,
                                paragraphIndex = readingParagraphIndex,
                                originalText = original,
                                spokenText = prepareForSpeech(original)
                            )
                        )
                    }
                }
                readingParagraphIndex++
            }
        }
        return output
    }

    fun splitIntoSentences(paragraph: String): List<String> {
        if (paragraph.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val ch = paragraph[i]
            current.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                while (i + 1 < paragraph.length && paragraph[i + 1] in charArrayOf('"', '”', '\'', ')', ']')) {
                    i++
                    current.append(paragraph[i])
                }
                val chunk = current.toString().trim()
                val nextIsSpaceOrEnd = i + 1 >= paragraph.length || paragraph[i + 1].isWhitespace()
                val isDecimalDot = ch == '.' && i > 0 && i + 1 < paragraph.length &&
                    paragraph[i - 1].isDigit() && paragraph[i + 1].isDigit()
                val isEllipsisWait = ch == '.' && i + 1 < paragraph.length && paragraph[i + 1] == '.'
                val lastToken = chunk.substringAfterLast(' ').lowercase().trimEnd('"', '”', '\'', ')')
                val isAbbrev = ch == '.' && (lastToken in nonBreakingAbbreviations ||
                    (lastToken.length == 2 && lastToken[0].isLetter() && lastToken[1] == '.'))

                if (nextIsSpaceOrEnd && !isDecimalDot && !isEllipsisWait && !isAbbrev) {
                    results.add(chunk)
                    current.clear()
                }
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            results.add(tail)
        }
        return results
    }

    fun prepareForSpeech(original: String): String {
        var text = Normalizer.normalize(original, Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace("’", "'")
            .replace("‘", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("...", ", ")
            .replace("…", ", ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("(", ", ")
            .replace(")", ", ")

        for ((pattern, replacement) in abbreviationExpansions) {
            text = pattern.replace(text, replacement)
        }

        // Currency prefix: $12.50, €1,250, £40
        text = Regex("""([$€£])\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?""").replace(text) { m ->
            val symbol = m.groupValues[1]
            val intStr = m.groupValues[2].replace(",", "")
            val decStr = m.groupValues[3]
            val intVal = intStr.toLongOrNull()
            val currency = when (symbol) {
                "$" -> if (intVal == 1L && decStr.isEmpty()) "dollar" else "dollars"
                "€" -> if (intVal == 1L && decStr.isEmpty()) "euro" else "euros"
                "£" -> if (intVal == 1L && decStr.isEmpty()) "pound" else "pounds"
                else -> ""
            }
            "${formatDecimalOrInt(intStr, decStr)} $currency"
        }

        // Percentages
        text = Regex("""(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?\s*%""").replace(text) { m ->
            val intStr = m.groupValues[1].replace(",", "")
            val decStr = m.groupValues[2]
            "${formatDecimalOrInt(intStr, decStr)} percent"
        }

        // Numbers with optional decimals
        text = Regex("""\b(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?\b""").replace(text) { m ->
            val intStr = m.groupValues[1].replace(",", "")
            val decStr = m.groupValues[2]
            formatDecimalOrInt(intStr, decStr)
        }

        return text
            .replace(Regex("""\s*,\s*(?=,|$)"""), "")
            .replace(Regex("""\s+([?!;:,])"""), "$1")
            .replace(Regex("""([?!;:,])([^\s?!;:,."'])"""), "$1 $2")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun formatDecimalOrInt(intStr: String, decStr: String): String {
        val intNum = intStr.toLongOrNull() ?: return intStr
        val intSpoken = numberToEnglish(intNum)
        if (decStr.isEmpty()) return intSpoken
        val decSpoken = decStr.map { numberToEnglish((it - '0').toLong()) }.joinToString(" ")
        return "$intSpoken point $decSpoken"
    }

    fun numberToEnglish(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0L) return "minus ${numberToEnglish(-n)}"
        if (n >= 1_000_000_000L) return n.toString()

        val parts = mutableListOf<String>()
        var rem = n
        if (rem >= 1_000_000L) {
            parts.add("${underThousand(rem / 1_000_000L)} million")
            rem %= 1_000_000L
        }
        if (rem >= 1_000L) {
            parts.add("${underThousand(rem / 1_000L)} thousand")
            rem %= 1_000L
        }
        if (rem > 0L) {
            parts.add(underThousand(rem))
        }
        return parts.joinToString(" ")
    }

    private fun underThousand(n: Long): String {
        val units = arrayOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        val tens = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
        val parts = mutableListOf<String>()
        val h = (n / 100).toInt()
        val rem = (n % 100).toInt()
        if (h > 0) parts.add("${units[h]} hundred")
        if (rem > 0) {
            when {
                rem < 20 -> parts.add(units[rem])
                rem % 10 == 0 -> parts.add(tens[rem / 10])
                else -> parts.add("${tens[rem / 10]}-${units[rem % 10]}")
            }
        }
        return parts.joinToString(" ")
    }
}

object ArabicTextPreprocessor {

    fun splitAndPreprocess(rawText: String): List<ProcessedSentence> {
        val normalizedNewlines = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalizedNewlines
            .split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val output = mutableListOf<ProcessedSentence>()
        var sentenceCounter = 0
        var readingParagraphIndex = 0

        paragraphs.forEach { paragraph ->
            val cleanParagraph = paragraph
                .replace(Regex("""\n+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val readingParagraphs = ReadingParagraphSegmenter.segment(splitIntoSentences(cleanParagraph))
            readingParagraphs.forEach { readingParagraph ->
                readingParagraph.forEach { original ->
                    if (original.isNotBlank()) {
                        output.add(
                            ProcessedSentence(
                                index = sentenceCounter++,
                                paragraphIndex = readingParagraphIndex,
                                originalText = original,
                                spokenText = prepareForSpeech(original)
                            )
                        )
                    }
                }
                readingParagraphIndex++
            }
        }
        return output
    }

    fun splitIntoSentences(paragraph: String): List<String> {
        if (paragraph.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val ch = paragraph[i]
            current.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '؟' || ch == '…') {
                while (i + 1 < paragraph.length && paragraph[i + 1] in charArrayOf('"', '”', '»', ')', ']', '\'')) {
                    i++
                    current.append(paragraph[i])
                }
                val chunk = current.toString().trim()
                val nextIsSpaceOrEnd = i + 1 >= paragraph.length || paragraph[i + 1].isWhitespace()
                val isDecimalDot = ch == '.' && i > 0 && i + 1 < paragraph.length &&
                    paragraph[i - 1].isDigit() && paragraph[i + 1].isDigit()
                val isEllipsisWait = ch == '.' && i + 1 < paragraph.length && paragraph[i + 1] == '.'

                if (nextIsSpaceOrEnd && !isDecimalDot && !isEllipsisWait) {
                    results.add(chunk)
                    current.clear()
                }
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            results.add(tail)
        }
        return results
    }

    fun prepareForSpeech(original: String): String {
        return Normalizer.normalize(original, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace("\u0640", "") // remove decorative tatweel for speech
            .replace("«", "")
            .replace("»", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
