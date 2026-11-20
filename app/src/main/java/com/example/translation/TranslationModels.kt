package com.example.translation

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

enum class TranslationDirection(
    val source: SourceLanguage,
    val targetCode: String,
    val displayNameAr: String,
    val shortBadge: String
) {
    FR_TO_AR(
        source = SourceLanguage.FRENCH,
        targetCode = "ar",
        displayNameAr = "الفرنسية ← العربية",
        shortBadge = "FR → AR"
    ),
    EN_TO_AR(
        source = SourceLanguage.ENGLISH,
        targetCode = "ar",
        displayNameAr = "الإنجليزية ← العربية",
        shortBadge = "EN → AR"
    );

    companion object {
        fun forSource(source: SourceLanguage): TranslationDirection = when (source) {
            SourceLanguage.FRENCH -> FR_TO_AR
            SourceLanguage.ENGLISH -> EN_TO_AR
        }
    }
}

enum class TranslationPackStatus {
    UNKNOWN,
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    ERROR
}

data class DirectionPackState(
    val direction: TranslationDirection,
    val status: TranslationPackStatus = TranslationPackStatus.UNKNOWN,
    val statusMessage: String = "جاري فحص حزمة الترجمة المحلية...",
    val activeRuntimeLabel: String = "ML Kit On-Device NMT (~30 MB)"
)

data class TranslationManagerUiState(
    val frToAr: DirectionPackState = DirectionPackState(TranslationDirection.FR_TO_AR),
    val enToAr: DirectionPackState = DirectionPackState(TranslationDirection.EN_TO_AR),
    val qualityDisclaimer: String = "تعمل الترجمة محلياً دون إنترنت بعد التنزيل عبر محرك واحد مشترك بين القارئ ومترجم الملفات (FR → AR و EN → AR فقط في المرحلة الأولى). الترجمة الآلية مساعدة للفهم السياقي وقد تحتاج لمراجعة في التراكيب الأدبية."
) {
    fun stateFor(direction: TranslationDirection): DirectionPackState = when (direction) {
        TranslationDirection.FR_TO_AR -> frToAr
        TranslationDirection.EN_TO_AR -> enToAr
    }
}

data class OpusReferenceSpec(
    val modelId: String,
    val directionLabelAr: String,
    val approxSizeMb: Int,
    val includedInPhase1: Boolean
)

object TranslationModelCatalog {
    /**
     * Reference matrix matching the 6 specialized OPUS-MT pairs from the architecture spec.
     * Phase 1 enables ONLY the two -> Arabic directions (FR->AR and EN->AR) and shares them
     * across both the Reader and the Background File Translator.
     */
    val opusReferenceMatrix: List<OpusReferenceSpec> = listOf(
        OpusReferenceSpec("opus-mt-fr-ar", "الفرنسية ← العربية", 307, includedInPhase1 = true),
        OpusReferenceSpec("opus-mt-en-ar", "الإنجليزية ← العربية", 308, includedInPhase1 = true),
        OpusReferenceSpec("opus-mt-ar-fr", "العربية ← الفرنسية", 311, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-fr-en", "الفرنسية ← الإنجليزية", 301, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-en-fr", "الإنجليزية ← الفرنسية", 301, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-ar-en", "العربية ← الإنجليزية", 308, includedInPhase1 = false)
    )
}

interface TranslationEngine {
    val direction: TranslationDirection
    val engineName: String
    suspend fun isModelReady(): Boolean
    suspend fun downloadModel(): Result<Unit>
    suspend fun deleteSourcePack(deleteSharedArabicTarget: Boolean): Result<Unit>
    suspend fun translateText(text: String): Result<String>
    fun close()
}
