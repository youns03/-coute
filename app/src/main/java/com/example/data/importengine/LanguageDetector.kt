package com.example.data.importengine

import com.example.translation.SourceLanguage

data class DetectedLanguageResult(
    val languageCode: String, // "ar", "fr", "en"
    val translationSourceLanguage: SourceLanguage,
    val isRtl: Boolean
)

/**
 * Detects Arabic ("ar"), French ("fr"), and English ("en") accurately from metadata hints and text samples.
 * Keeps [translationSourceLanguage] mapped to supported offline translation packs (FR/EN -> AR)
 * while preserving exact `"ar"` code for Reader typography/RTL and Arabic speech preprocessing.
 */
object LanguageDetector {

    private val frenchMarkers = listOf(
        " le ", " la ", " les ", " des ", " une ", " un ", " est ", " sont ",
        " pour ", " dans ", " avec ", " qui ", " que ", " nous ", " vous ", " pas ", " sur "
    )

    private val englishMarkers = listOf(
        " the ", " and ", " of ", " to ", " is ", " in ", " that ", " with ",
        " for ", " are ", " was ", " were ", " this ", " from ", " have ", " you "
    )

    fun detect(sampleText: String, metadataLangHint: String? = null): DetectedLanguageResult {
        val hint = metadataLangHint.orEmpty().trim().lowercase()

        var arabicLetters = 0
        var latinLetters = 0
        for (ch in sampleText) {
            when {
                isArabicChar(ch) -> arabicLetters++
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in 'À'..'ÿ' -> latinLetters++
            }
        }

        // Script dominance overrides generic/missing metadata when sample clearly proves Arabic
        val totalLetters = arabicLetters + latinLetters
        if (hint.startsWith("ar") || (totalLetters > 0 && arabicLetters >= 8 && arabicLetters.toFloat() / totalLetters >= 0.35f)) {
            return DetectedLanguageResult(
                languageCode = "ar",
                translationSourceLanguage = SourceLanguage.FRENCH,
                isRtl = true
            )
        }

        if (hint.startsWith("en")) {
            return DetectedLanguageResult("en", SourceLanguage.ENGLISH, isRtl = false)
        }
        if (hint.startsWith("fr")) {
            return DetectedLanguageResult("fr", SourceLanguage.FRENCH, isRtl = false)
        }

        val lower = " ${sampleText.lowercase()} "
        val frenchDiacritics = lower.count { it in "éèêëàâùûüôîïçœ" }
        val frenchScore = frenchMarkers.sumOf { countOccurrences(lower, it) } * 2 + frenchDiacritics
        val englishScore = englishMarkers.sumOf { countOccurrences(lower, it) } * 2

        return if (englishScore > frenchScore) {
            DetectedLanguageResult("en", SourceLanguage.ENGLISH, isRtl = false)
        } else {
            DetectedLanguageResult("fr", SourceLanguage.FRENCH, isRtl = false)
        }
    }

    fun isArabicText(text: String): Boolean {
        var arabic = 0
        var latin = 0
        for (ch in text) {
            if (isArabicChar(ch)) arabic++
            else if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in 'À'..'ÿ') latin++
        }
        return arabic > 0 && arabic >= latin
    }

    fun isArabicChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x0600..0x06FF ||
            code in 0x0750..0x077F ||
            code in 0x08A0..0x08FF ||
            code in 0xFB50..0xFDFF ||
            code in 0xFE70..0xFEFF
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val found = haystack.indexOf(needle, index)
            if (found < 0) break
            count++
            index = found + needle.length
        }
        return count
    }
}
