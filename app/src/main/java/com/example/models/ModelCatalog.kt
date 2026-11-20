package com.example.models

import com.example.domain.ArchiveType
import com.example.domain.EngineType
import com.example.domain.LicenseRecord
import com.example.domain.ModelSpec

object ModelCatalog {

    val espeakNgDataSpec = ModelSpec(
        id = "espeak-ng-data",
        displayName = "eSpeak-ng Phonemizer Data",
        language = "fr-FR (Multilingual)",
        voiceId = "phonemizer",
        voiceDescriptionAr = "بيانات التحويل الصوتي المساعدة المطلوبة لتشغيل نماذج Piper ONNX",
        format = "ZIP -> directory",
        engineType = EngineType.PIPER_SHERPA_ONNX,
        version = "2026.02",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.zip",
        expectedSizeBytes = 9_037_020L,
        sha256 = "bc4525eafe31b4e3f5e43aea495f3169e97dd2544f1bbfe95514ce8a61baee39",
        archiveType = ArchiveType.ZIP,
        requiresEspeakData = false,
        licenseName = "GPL-3.0+",
        ownerName = "eSpeak NG Contributors",
        sourceUrl = "https://github.com/espeak-ng/espeak-ng",
        commercialAllowed = false,
        attributionNotes = "Required by Piper/VITS phonemizer when not bundled inside archive; GPL-3.0 copyleft applies if redistributed."
    )

    val ttsModels: List<ModelSpec> = listOf(
        ModelSpec(
            id = "kokoro-82m-gpu-fp16",
            displayName = "Kokoro-82M (GPU FP16 + CPU Fallback)",
            language = "fr-FR",
            voiceId = "ff_siwis",
            voiceDescriptionAr = "المرشح الأساسي: صوت نسائي فرنسي طبيعي جداً (ff_siwis) بسرعة 24 kHz مع دعم التوقيت التقريبي للكلمات",
            format = "LiteRT-LM (.litertlm) INT8/FP16",
            engineType = EngineType.KOKORO_LITERT,
            version = "1.0-litert",
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
            expectedSizeBytes = 294_750_530L,
            sha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            archiveType = ArchiveType.SINGLE_LITERTLM,
            requiresEspeakData = false,
            licenseName = "Apache-2.0",
            ownerName = "hexgrad / Google LiteRT Community",
            sourceUrl = "https://huggingface.co/litert-community/Kokoro-82M",
            commercialAllowed = true,
            attributionNotes = "Weights & LiteRT-LM bundle licensed under Apache 2.0. French voice ff_siwis derived from SIWIS corpus.",
            isPrimaryCandidate = true
        ),
        ModelSpec(
            id = "kokoro-82m-cpu-fp16",
            displayName = "Kokoro-82M (CPU INT8/FP16)",
            language = "fr-FR",
            voiceId = "ff_siwis",
            voiceDescriptionAr = "نسخة Kokoro المخصصة للمعالجات التي لا تدعم OpenCL GPU بشكل مستقر (ff_siwis)",
            format = "LiteRT-LM (.litertlm) CPU",
            engineType = EngineType.KOKORO_LITERT,
            version = "1.0-litert-cpu",
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_fp16.litertlm",
            expectedSizeBytes = 294_783_298L,
            sha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
            archiveType = ArchiveType.SINGLE_LITERTLM,
            requiresEspeakData = false,
            licenseName = "Apache-2.0",
            ownerName = "hexgrad / Google LiteRT Community",
            sourceUrl = "https://huggingface.co/litert-community/Kokoro-82M",
            commercialAllowed = true,
            attributionNotes = "Apache 2.0 license. CPU-tuned Kokoro-82M bundle."
        ),
        ModelSpec(
            id = "piper-fr-siwis-medium",
            displayName = "Piper French SIWIS (Medium)",
            language = "fr-FR",
            voiceId = "siwis",
            voiceDescriptionAr = "المرشح المقارن 1: صوت نسائي فرنسي خفيف وسريع على المعالج (67 MB) عبر Sherpa-ONNX",
            format = "VITS ONNX (.tar.bz2)",
            engineType = EngineType.PIPER_SHERPA_ONNX,
            version = "medium-1.0",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-siwis-medium.tar.bz2",
            expectedSizeBytes = 67_207_459L,
            sha256 = "375909aa30842b3a4efa10b1beb1d761af792960ae6873b4d53889f96c66195b",
            archiveType = ArchiveType.TAR_BZ2,
            requiresEspeakData = true,
            licenseName = "MIT (Model) / CC-BY-4.0 (SIWIS) / GPL-3.0+ (eSpeak-ng)",
            ownerName = "Rhasspy Piper / k2-fsa Sherpa-ONNX",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models",
            commercialAllowed = false,
            attributionNotes = "VITS ONNX weights under MIT; SIWIS French speech dataset CC-BY-4.0; uses eSpeak-ng phonemizer data (GPL-3.0+)."
        ),
        ModelSpec(
            id = "piper-fr-tom-medium",
            displayName = "Piper French Tom (Medium)",
            language = "fr-FR",
            voiceId = "tom",
            voiceDescriptionAr = "المرشح المقارن 2: صوت رجالي فرنسي واضح للنصوص التعليمية (67 MB) عبر Sherpa-ONNX",
            format = "VITS ONNX (.tar.bz2)",
            engineType = EngineType.PIPER_SHERPA_ONNX,
            version = "medium-1.0",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-tom-medium.tar.bz2",
            expectedSizeBytes = 67_202_955L,
            sha256 = "3d8258ef8466b5d5f2cc46c06e1415d3d01c9cbaeed8b6d5b3d152073672472c",
            archiveType = ArchiveType.TAR_BZ2,
            requiresEspeakData = true,
            licenseName = "MIT (Model) / GPL-3.0+ (eSpeak-ng)",
            ownerName = "Rhasspy Piper / k2-fsa Sherpa-ONNX",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models",
            commercialAllowed = false,
            attributionNotes = "Piper French Tom voice packaged for Sherpa-ONNX; verify commercial clearance of eSpeak-ng before commercial release."
        )
    )

    fun findById(id: String): ModelSpec? = ttsModels.firstOrNull { it.id == id }

    val licenseLedger: List<LicenseRecord> = listOf(
        LicenseRecord(
            componentName = "Kokoro-82M (.litertlm)",
            role = "Primary French Neural TTS Model (ff_siwis)",
            owner = "hexgrad & Google LiteRT Community",
            sourceUrl = "https://huggingface.co/litert-community/Kokoro-82M",
            weightsOrCodeLicense = "Apache 2.0",
            voiceOrDataLicense = "SIWIS French subset (CC-BY-4.0 / Apache 2.0)",
            commercialTerms = "Permitted under Apache 2.0 with attribution",
            attributionRequirement = "Include Apache 2.0 notice and credit hexgrad/Kokoro-82M"
        ),
        LicenseRecord(
            componentName = "MediaPipe Tasks Audio (tasks-audio 1.2.2)",
            role = "LiteRT-LM On-device TTS Runtime",
            owner = "Google LLC",
            sourceUrl = "https://ai.google.dev/edge/mediapipe",
            weightsOrCodeLicense = "Apache 2.0",
            voiceOrDataLicense = "N/A (Runtime library)",
            commercialTerms = "Permitted under Apache 2.0",
            attributionRequirement = "Standard Apache 2.0 license notice"
        ),
        LicenseRecord(
            componentName = "Piper French SIWIS & Tom (VITS)",
            role = "Comparative Offline French TTS Models",
            owner = "Rhasspy / Michael Hansen & k2-fsa",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models",
            weightsOrCodeLicense = "MIT",
            voiceOrDataLicense = "SIWIS (CC-BY-4.0) / Tom (Public/Piper)",
            commercialTerms = "Weights MIT; runtime uses eSpeak-ng data (GPL-3.0+)",
            attributionRequirement = "Credit Piper Voices, Idiap SIWIS dataset, and k2-fsa"
        ),
        LicenseRecord(
            componentName = "Sherpa-ONNX Android AAR (v2.0.2)",
            role = "ONNX VITS TTS Runtime & JNI",
            owner = "Next-gen Kaldi (k2-fsa)",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx",
            weightsOrCodeLicense = "Apache 2.0 (with ONNX Runtime MIT)",
            voiceOrDataLicense = "eSpeak-ng data is GPL-3.0+",
            commercialTerms = "Engine Apache 2.0; commercial builds using eSpeak-ng require GPL-3.0 review",
            attributionRequirement = "Include Apache 2.0, MIT, and GPL-3.0 notices if eSpeak-ng is distributed"
        ),
        LicenseRecord(
            componentName = "ML Kit On-Device Translation (17.0.3)",
            role = "Offline French-to-Arabic Translation Engine",
            owner = "Google LLC",
            sourceUrl = "https://developers.google.com/ml-kit/language/translation",
            weightsOrCodeLicense = "Google APIs Terms of Service / Android SDK",
            voiceOrDataLicense = "On-device neural NMT packs (~30 MB)",
            commercialTerms = "Permitted in Android apps under Google APIs Terms",
            attributionRequirement = "Comply with ML Kit Terms of Service"
        ),
        LicenseRecord(
            componentName = "AndroidX Media3, Room, WorkManager, Commons Compress",
            role = "Background Playback, Local DB, Downloads, Archive Extraction",
            owner = "Android Jetpack / Apache Software Foundation",
            sourceUrl = "https://developer.android.com/jetpack",
            weightsOrCodeLicense = "Apache 2.0",
            voiceOrDataLicense = "N/A",
            commercialTerms = "Permitted under Apache 2.0",
            attributionRequirement = "Standard Apache 2.0 notices"
        )
    )
}
