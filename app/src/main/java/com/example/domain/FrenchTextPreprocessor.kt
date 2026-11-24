package com.example.domain

import java.text.Normalizer

data class ProcessedSentence(
    val index: Int,
    val paragraphIndex: Int,
    val originalText: String,
    val spokenText: String
)

/**
 * Independent French Text Preprocessor.
 * Never alters stored/displayed French text; produces a separate TTS-ready spokenText string.
 */

/**
 * Builds reader-sized paragraphs without losing source text. Explicit blank-line paragraphs
 * remain boundaries; long run-on text is split at natural punctuation, then at word boundaries.
 * Four sentences / roughly 420 characters keeps paragraph translation coherent and readable.
 */
internal object ReadingParagraphSegmenter {
    private const val MAX_PARAGRAPH_CHARS = 420
    private const val MAX_SENTENCES_PER_PARAGRAPH = 4

    fun segment(sentences: List<String>): List<List<String>> {
        if (sentences.isEmpty()) return emptyList()
        val units = sentences.flatMap(::splitLongUnit).filter { it.isNotBlank() }
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentChars = 0

        units.forEach { unit ->
            val projected = currentChars + if (current.isEmpty()) unit.length else unit.length + 1
            if (current.isNotEmpty() &&
                (projected > MAX_PARAGRAPH_CHARS || current.size >= MAX_SENTENCES_PER_PARAGRAPH)
            ) {
                result.add(current.toList())
                current = mutableListOf()
                currentChars = 0
            }
            current.add(unit)
            currentChars += unit.length + if (current.size > 1) 1 else 0
        }
        if (current.isNotEmpty()) result.add(current.toList())
        return result
    }

    private fun splitLongUnit(raw: String): List<String> {
        val text = raw.trim()
        if (text.length <= MAX_PARAGRAPH_CHARS) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > MAX_PARAGRAPH_CHARS) {
            val windowEnd = MAX_PARAGRAPH_CHARS.coerceAtMost(remaining.length)
            val preferredStart = (MAX_PARAGRAPH_CHARS * 0.55f).toInt()
            val punctuation = remaining.substring(preferredStart, windowEnd)
                .indexOfLast { it == ',' || it == ';' || it == ':' }
            val split = if (punctuation >= 0) preferredStart + punctuation + 1 else {
                val whitespace = remaining.substring(preferredStart, windowEnd).indexOfLast { it.isWhitespace() }
                if (whitespace >= 0) preferredStart + whitespace else windowEnd
            }
            val boundary = split.coerceIn(1, windowEnd)
            result.add(remaining.substring(0, boundary).trim())
            remaining = remaining.substring(boundary).trimStart()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }
}

object FrenchTextPreprocessor {

    private val nonBreakingAbbreviations = setOf(
        "m.", "mme.", "mme", "mlle.", "mlle", "dr.", "prof.", "st.", "ste.",
        "etc.", "ex.", "fig.", "p.", "pp.", "vol.", "ch.", "av.", "ap.",
        "env.", "cf.", "art.", "al.", "tél.", "n°", "mm."
    )

    private val abbreviationExpansions = linkedMapOf(
        Regex("""\bM\.\s*""") to "Monsieur ",
        Regex("""\bMM\.\s*""") to "Messieurs ",
        Regex("""\bMme\.?\s*""") to "Madame ",
        Regex("""\bMlles?\.?\s*""") to "Mademoiselle ",
        Regex("""\bDr\.?\s*""") to "Docteur ",
        Regex("""\bProf\.?\s*""") to "Professeur ",
        Regex("""\bSt-""") to "Saint-",
        Regex("""\bSte-""") to "Sainte-",
        Regex("""\betc\.""") to "et cætera",
        Regex("""\benv\.\s*""") to "environ ",
        Regex("""\bex\.\s*""") to "exemple ",
        Regex("""\bn°\s*""") to "numéro ",
        Regex("""\bp\.\s*(?=\d)""") to "page "
    )

    private val frenchMonths = arrayOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre"
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
                // Consume trailing closing quotes or parentheses attached to sentence end
                while (i + 1 < paragraph.length && paragraph[i + 1] in charArrayOf('»', '"', '”', ')', ']')) {
                    i++
                    current.append(paragraph[i])
                }
                val chunk = current.toString().trim()
                val nextIsSpaceOrEnd = i + 1 >= paragraph.length || paragraph[i + 1].isWhitespace()
                val isDecimalDot = ch == '.' && i > 0 && i + 1 < paragraph.length &&
                    paragraph[i - 1].isDigit() && paragraph[i + 1].isDigit()
                val isEllipsisWait = ch == '.' && i + 1 < paragraph.length && paragraph[i + 1] == '.'
                val lastToken = chunk.substringAfterLast(' ').lowercase().trimEnd('»', '"', '”', ')')
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

    /**
     * Produces a clean French TTS string without altering the original displayed sentence.
     */
    fun prepareForSpeech(original: String): String {
        var text = Normalizer.normalize(original, Normalizer.Form.NFC)
            .replace('\u00A0', ' ') // NBSP -> space
            .replace('\u202F', ' ') // narrow NBSP -> space
            .replace("’", "'")
            .replace("‘", "'")
            .replace("«", "")
            .replace("»", "")
            .replace("“", "")
            .replace("”", "")
            .replace("—", ", ")
            .replace("–", ", ")

        // Expand abbreviations first
        for ((pattern, replacement) in abbreviationExpansions) {
            text = pattern.replace(text, replacement)
        }

        // Dates: dd/mm/yyyy
        text = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""").replace(text) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: 0
            val month = m.groupValues[2].toIntOrNull() ?: 0
            val year = m.groupValues[3].toIntOrNull() ?: 0
            if (day in 1..31 && month in 1..12) {
                val dayWord = if (day == 1) "premier" else numberToFrench(day.toLong())
                "$dayWord ${frenchMonths[month - 1]} ${numberToFrench(year.toLong())}"
            } else m.value
        }

        // Currencies with space-separated thousands e.g. "1 250 €", "3,75 €", "12$"
        text = Regex("""(\d{1,3}(?: \d{3})+|\d+)(?:,(\d+))?\s*([€$£])""").replace(text) { m ->
            val intStr = m.groupValues[1].replace(" ", "")
            val decStr = m.groupValues[2]
            val symbol = m.groupValues[3]
            val amountVal = intStr.toLongOrNull()
            val currencyWord = when (symbol) {
                "€" -> if (amountVal == 1L && decStr.isEmpty()) "euro" else "euros"
                "$" -> if (amountVal == 1L && decStr.isEmpty()) "dollar" else "dollars"
                "£" -> if (amountVal == 1L && decStr.isEmpty()) "livre" else "livres"
                else -> ""
            }
            val spokenNum = formatDecimalOrInt(intStr, decStr)
            "$spokenNum $currencyWord"
        }

        // Percentages e.g. "45 %" or "3,5%"
        text = Regex("""(\d{1,3}(?: \d{3})+|\d+)(?:,(\d+))?\s*%""").replace(text) { m ->
            val intStr = m.groupValues[1].replace(" ", "")
            val decStr = m.groupValues[2]
            "${formatDecimalOrInt(intStr, decStr)} pour cent"
        }

        // Ordinals: 1er, 1re, 2e, 3ème
        text = Regex("""\b(\d+)(er|re|ère|ème|e)\b""").replace(text) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val suffix = m.groupValues[2]
            when {
                n == 1L && (suffix == "er") -> "premier"
                n == 1L -> "première"
                else -> ordinalToFrench(n)
            }
        }

        // Standalone space-grouped numbers and decimals: "1 250", "3,75", "2026"
        text = Regex("""\b(\d{1,3}(?: \d{3})+|\d+)(?:,(\d+))?\b""").replace(text) { m ->
            val intStr = m.groupValues[1].replace(" ", "")
            val decStr = m.groupValues[2]
            formatDecimalOrInt(intStr, decStr)
        }

        // Clean French spacing around punctuation (? ! ; :) without dropping question/exclamation marks
        text = text
            .replace(Regex("""\s+([?!;:,])"""), "$1")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        return text
    }

    private fun formatDecimalOrInt(intStr: String, decStr: String): String {
        val intNum = intStr.toLongOrNull() ?: return intStr
        val intSpoken = numberToFrench(intNum)
        if (decStr.isEmpty()) return intSpoken
        // Read decimal part naturally if <= 2 digits (e.g. 3,75 -> trois virgule soixante-quinze), else digit-by-digit
        val decSpoken = if (decStr.length <= 2 && !decStr.startsWith("0")) {
            numberToFrench(decStr.toLong())
        } else {
            decStr.map { numberToFrench((it - '0').toLong()) }.joinToString(" ")
        }
        return "$intSpoken virgule $decSpoken"
    }

    private fun ordinalToFrench(n: Long): String {
        val base = numberToFrench(n)
        return when {
            base.endsWith("e") -> base.dropLast(1) + "ième"
            base.endsWith("f") -> base.dropLast(1) + "vième"
            base.endsWith("q") -> base + "uième"
            else -> base + "ième"
        }
    }

    fun numberToFrench(n: Long): String {
        if (n == 0L) return "zéro"
        if (n < 0L) return "moins ${numberToFrench(-n)}"
        if (n >= 1_000_000_000L) return n.toString()

        val parts = mutableListOf<String>()
        var rem = n

        if (rem >= 1_000_000L) {
            val millions = rem / 1_000_000L
            parts.add(if (millions == 1L) "un million" else "${numberToFrench(millions)} millions")
            rem %= 1_000_000L
        }
        if (rem >= 1_000L) {
            val thousands = rem / 1_000L
            parts.add(if (thousands == 1L) "mille" else "${underThousandToFrench(thousands, pluralAllowed = false)} mille")
            rem %= 1_000L
        }
        if (rem > 0L) {
            parts.add(underThousandToFrench(rem, pluralAllowed = true))
        }
        return parts.joinToString(" ")
    }

    private fun underThousandToFrench(n: Long, pluralAllowed: Boolean): String {
        val units = arrayOf(
            "", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
            "dix-sept", "dix-huit", "dix-neuf"
        )
        val tens = arrayOf(
            "", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante", "quatre-vingt", "quatre-vingt"
        )

        val parts = mutableListOf<String>()
        val hundreds = (n / 100).toInt()
        val rem = (n % 100).toInt()

        if (hundreds > 0) {
            when {
                hundreds == 1 -> parts.add("cent")
                rem == 0 && pluralAllowed -> parts.add("${units[hundreds]} cents")
                else -> parts.add("${units[hundreds]} cent")
            }
        }

        if (rem > 0) {
            when {
                rem < 20 -> parts.add(units[rem])
                rem < 70 -> {
                    val t = rem / 10
                    val u = rem % 10
                    when (u) {
                        0 -> parts.add(tens[t])
                        1 -> parts.add("${tens[t]}-et-un")
                        else -> parts.add("${tens[t]}-${units[u]}")
                    }
                }
                rem < 80 -> {
                    val u = rem - 60
                    if (u == 11) parts.add("soixante-et-onze")
                    else parts.add("soixante-${units[u]}")
                }
                else -> {
                    val u = rem - 80
                    when (u) {
                        0 -> parts.add(if (pluralAllowed) "quatre-vingts" else "quatre-vingt")
                        else -> parts.add("quatre-vingt-${units[u]}")
                    }
                }
            }
        }
        return parts.joinToString(" ")
    }
}
