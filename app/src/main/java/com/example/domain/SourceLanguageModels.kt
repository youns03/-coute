package com.example.domain

enum class SourceLanguage(
    val code: String,
    val labelAr: String,
    val labelNative: String,
    val opusModelId: String,
    val opusApproxSizeMb: Int
) {
    FRENCH(
        code = "fr",
        labelAr = "الفرنسية",
        labelNative = "Français",
        opusModelId = "opus-mt-fr-ar",
        opusApproxSizeMb = 307
    ),
    ENGLISH(
        code = "en",
        labelAr = "الإنجليزية",
        labelNative = "English",
        opusModelId = "opus-mt-en-ar",
        opusApproxSizeMb = 308
    );

    companion object {
        fun fromCode(code: String?): SourceLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: FRENCH
    }
}

object ScriptDirectionDetector {
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
}

object ByteSizeFormatter {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            "${"%.2f".format(mb / 1024.0)} GB"
        } else {
            "${"%.1f".format(mb)} MB"
        }
    }
}
