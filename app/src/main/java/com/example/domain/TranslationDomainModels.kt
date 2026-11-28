package com.example.domain

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
    val opusReferenceMatrix: List<OpusReferenceSpec> = listOf(
        OpusReferenceSpec("opus-mt-fr-ar", "الفرنسية ← العربية", 307, includedInPhase1 = true),
        OpusReferenceSpec("opus-mt-en-ar", "الإنجليزية ← العربية", 308, includedInPhase1 = true),
        OpusReferenceSpec("opus-mt-ar-fr", "العربية ← الفرنسية", 311, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-fr-en", "الفرنسية ← الإنجليزية", 301, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-en-fr", "الإنجليزية ← الفرنسية", 301, includedInPhase1 = false),
        OpusReferenceSpec("opus-mt-ar-en", "العربية ← الإنجليزية", 308, includedInPhase1 = false)
    )
}

enum class ExportMode(val labelAr: String, val fileSuffix: String) {
    ARABIC_COMPLETED_ONLY("الترجمة العربية (الأجزاء المنجزة فقط)", "_ar_completed.txt"),
    BILINGUAL_COMPLETED_ONLY("ثنائي اللغة (الأجزاء المنجزة فقط)", "_bilingual_completed.txt"),
    BILINGUAL_WITH_PLACEHOLDERS("مسودة كاملة (تشمل غير المترجم بوسم توضيحي)", "_bilingual_draft.txt")
}
