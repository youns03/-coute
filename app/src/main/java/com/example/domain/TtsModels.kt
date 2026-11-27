package com.example.domain

enum class ModelRole {
    TTS,
    ASR
}

enum class EngineType(val displayName: String) {
    KOKORO_LITERT("Kokoro-82M (LiteRT / MediaPipe)"),
    PIPER_SHERPA_ONNX("Piper VITS (Sherpa-ONNX)"),
    SHERPA_ASR("Sherpa-ONNX Speech Recognition")
}

enum class AcceleratorMode(val labelAr: String, val labelEn: String) {
    AUTO_GPU_WITH_CPU_FALLBACK("تلقائي (GPU مع تراجع CPU)", "Auto (GPU + CPU fallback)"),
    CPU_ONLY("المعالج فقط (CPU)", "CPU Only"),
    GPU_ONLY("معالج الرسوميات (GPU)", "GPU Only")
}

enum class SpeedControlMode(val labelAr: String, val labelEn: String) {
    AUDIO_TRACK_TIME_STRETCH("تعديل سرعة التشغيل عبر AudioTrack (أكثر ثباتاً)", "AudioTrack PlaybackParams"),
    MODEL_SYNTHESIS_RATE("تعديل سرعة التوليد داخل النموذج", "Model Synthesis Speed")
}

enum class ModelInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED,
    CORRUPTED,
    FAILED
}

enum class ModelCompatibilityStatus {
    COMPATIBLE,
    WARNING_LOW_RAM,
    INCOMPATIBLE_API,
    INCOMPATIBLE_ABI,
    INCOMPATIBLE_RAM
}

data class ModelCompatibilityReport(
    val status: ModelCompatibilityStatus,
    val isSupported: Boolean,
    val summaryAr: String,
    val detailsAr: String? = null
)

data class ModelSpec(
    val id: String,
    val displayName: String,
    val language: String,
    val voiceId: String,
    val voiceDescriptionAr: String,
    val format: String,
    val engineType: EngineType,
    val version: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val sha256: String,
    val archiveType: ArchiveType,
    val requiresEspeakData: Boolean,
    val licenseName: String,
    val ownerName: String,
    val sourceUrl: String,
    val commercialAllowed: Boolean,
    val attributionNotes: String,
    val role: ModelRole = ModelRole.TTS,
    val isPrimaryCandidate: Boolean = false,
    val supportedLanguageCodes: List<String> = listOf("fr"),
    val localesByLanguage: Map<String, String> = mapOf("fr" to "fr-FR"),
    val voicesByLanguage: Map<String, List<String>> = mapOf("fr" to listOf(voiceId)),
    val minSdkRequired: Int = 24,
    val minTotalRamMb: Long = 0L,
    val recommendedRamMb: Long = 0L,
    val requires64BitAbi: Boolean = false,
    val singleSpeakerOnly: Boolean = false,
    val supportsGpuDelegate: Boolean = false,
    val supportsWordTimestamps: Boolean = false
) {
    fun supportsLanguage(langCode: String): Boolean =
        supportedLanguageCodes.any { it.equals(langCode.trim(), ignoreCase = true) }

    fun localeFor(langCode: String): String {
        val norm = langCode.lowercase()
        return localesByLanguage[norm] ?: if (norm == "en") "en-US" else "fr-FR"
    }

    fun defaultVoiceFor(langCode: String): String {
        val norm = langCode.lowercase()
        return voicesByLanguage[norm]?.firstOrNull() ?: voiceId
    }
}

enum class ArchiveType {
    SINGLE_LITERTLM,
    SINGLE_ONNX,
    TAR_BZ2,
    ZIP
}

data class WordTimestampRange(
    val word: String,
    val startChar: Int,
    val endChar: Int,
    val startSec: Float,
    val endSec: Float
)

data class TtsStreamChunk(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange> = emptyList(),
    val isFinal: Boolean = false
)

data class TtsSynthesisResult(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>,
    val synthesisTimeMs: Long,
    val usedAccelerator: String,
    val fellBackToCpu: Boolean,
    val fallbackReason: String? = null
)

data class StorageBreakdown(
    val apkBytes: Long,
    val nativeLibsEstimateBytes: Long,
    val installedModelsBytes: Long,
    val tempDownloadsBytes: Long,
    val audioCacheBytes: Long,
    val savedSamplesBytes: Long,
    val freeDeviceBytes: Long
)

data class LicenseRecord(
    val componentName: String,
    val role: String,
    val owner: String,
    val sourceUrl: String,
    val weightsOrCodeLicense: String,
    val voiceOrDataLicense: String,
    val commercialTerms: String,
    val attributionRequirement: String
)
