package com.example.domain

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug

data class DeviceHardwareInfo(
    val deviceModel: String,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val is64BitSupported: Boolean,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val androidVersion: String,
    val sdkInt: Int
)

object MemoryDiagnostics {
    private const val MIN_SAFE_AVAILABLE_RAM_KOKORO_MB = 360L
    private const val WARN_AVAILABLE_RAM_KOKORO_MB = 700L
    private const val MIN_SAFE_AVAILABLE_RAM_PIPER_MB = 140L
    private const val WARN_AVAILABLE_RAM_PIPER_MB = 280L

    fun getProcessPssMb(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = android.os.Process.myPid()
        val memInfos = am?.getProcessMemoryInfo(intArrayOf(pid))
        val pssKb = memInfos?.firstOrNull()?.totalPss ?: (Debug.getNativeHeapAllocatedSize() / 1024L).toInt()
        return pssKb / 1024f
    }

    fun getDeviceHardwareInfo(context: Context): DeviceHardwareInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val availableRamMb = memInfo.availMem / (1024L * 1024L)
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val is64Bit = supportedAbis.any { it.contains("64") }
        return DeviceHardwareInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            primaryAbi = supportedAbis.firstOrNull() ?: "unknown",
            supportedAbis = supportedAbis,
            is64BitSupported = is64Bit,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    fun evaluateModelCompatibility(spec: ModelSpec, context: Context): ModelCompatibilityReport =
        evaluateModelCompatibility(spec, getDeviceHardwareInfo(context))

    fun evaluateModelCompatibility(spec: ModelSpec, hw: DeviceHardwareInfo): ModelCompatibilityReport {
        if (hw.sdkInt < spec.minSdkRequired) {
            return ModelCompatibilityReport(
                status = ModelCompatibilityStatus.INCOMPATIBLE_API,
                isSupported = false,
                summaryAr = "غير متوافق مع إصدار النظام (يتطلب Android API ${spec.minSdkRequired}+)",
                detailsAr = "جهازك يعمل على API ${hw.sdkInt}، بينما يتطلب ${spec.displayName} إصدار API ${spec.minSdkRequired} على الأقل. استخدم نماذج Piper VITS الخفيفة بدلاً منه."
            )
        }
        if (spec.requires64BitAbi && hw.supportedAbis.isNotEmpty() && !hw.is64BitSupported) {
            return ModelCompatibilityReport(
                status = ModelCompatibilityStatus.INCOMPATIBLE_ABI,
                isSupported = false,
                summaryAr = "يتطلب معمارية 64-bit (ARM64)",
                detailsAr = "المعالج المبلغ عنه (${hw.primaryAbi}) لا يدعم 64-bit المطلوب لتشغيل Kokoro-82M باستقرار؛ اختر Piper ONNX."
            )
        }
        if (hw.totalRamMb > 0L && spec.minTotalRamMb > 0L && hw.totalRamMb < spec.minTotalRamMb) {
            return ModelCompatibilityReport(
                status = ModelCompatibilityStatus.INCOMPATIBLE_RAM,
                isSupported = false,
                summaryAr = "ذاكرة الجهاز (${hw.totalRamMb} MB) أقل من الحد الأدنى (${spec.minTotalRamMb} MB)",
                detailsAr = "قد يستهلك ${spec.displayName} حتى ~950 MB أثناء التوليد؛ يُنصح على هذا الجهاز باستخدام نماذج Piper (67 MB)."
            )
        }
        if (hw.totalRamMb > 0L && spec.recommendedRamMb > 0L && hw.totalRamMb < spec.recommendedRamMb) {
            return ModelCompatibilityReport(
                status = ModelCompatibilityStatus.WARNING_LOW_RAM,
                isSupported = true,
                summaryAr = "تحذير ذاكرة منخفضة (${hw.totalRamMb} MB • الموصى به ${spec.recommendedRamMb} MB)",
                detailsAr = "يمكن تشغيل النموذج، لكن يُنصح بإغلاق التطبيقات الأخرى أو التبديل إلى Piper عند البطء."
            )
        }
        if (hw.availableRamMb in 1L..WARN_AVAILABLE_RAM_KOKORO_MB && spec.engineType == EngineType.KOKORO_LITERT) {
            return ModelCompatibilityReport(
                status = ModelCompatibilityStatus.WARNING_LOW_RAM,
                isSupported = true,
                summaryAr = "الذاكرة المتاحة حالياً منخفضة (${hw.availableRamMb} MB)",
                detailsAr = "قد يرتفع استهلاك Kokoro إلى ~950 MB؛ أغلق التطبيقات الخلفية أو استخدم وضع CPU/Piper عند الحاجة."
            )
        }
        return ModelCompatibilityReport(
            status = ModelCompatibilityStatus.COMPATIBLE,
            isSupported = true,
            summaryAr = "متوافق مع جهازك (${hw.primaryAbi} • API ${hw.sdkInt})"
        )
    }

    fun evaluateRuntimeMemoryForLoad(spec: ModelSpec, context: Context): ModelCompatibilityReport =
        evaluateRuntimeMemoryForLoad(spec, getDeviceHardwareInfo(context))

    fun evaluateRuntimeMemoryForLoad(spec: ModelSpec, hw: DeviceHardwareInfo): ModelCompatibilityReport {
        val staticReport = evaluateModelCompatibility(spec, hw)
        if (!staticReport.isSupported) return staticReport

        val avail = hw.availableRamMb
        if (avail <= 0L) return staticReport

        return when (spec.engineType) {
            EngineType.KOKORO_LITERT -> when {
                avail < MIN_SAFE_AVAILABLE_RAM_KOKORO_MB -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.INCOMPATIBLE_RAM,
                    isSupported = false,
                    summaryAr = "الذاكرة الحرة المتاحة حالياً منخفضة جداً (${avail} MB)",
                    detailsAr = "يتطلب تحميل ${spec.displayName} نحو ${MIN_SAFE_AVAILABLE_RAM_KOKORO_MB} MB حرة على الأقل لتجنب الانهيار (${avail} MB متاحة). أغلق التطبيقات الخلفية أو بدّل إلى Piper."
                )
                avail <= WARN_AVAILABLE_RAM_KOKORO_MB -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.WARNING_LOW_RAM,
                    isSupported = true,
                    summaryAr = "تحذير: الذاكرة الحرة المتاحة محدودة (${avail} MB)",
                    detailsAr = "سيُجرى التحميل مع مراقبة الذاكرة؛ إذا تعذّر التخصيص أغلق التطبيقات أو بدّل إلى Piper."
                )
                else -> staticReport
            }
            EngineType.PIPER_SHERPA_ONNX -> when {
                avail < MIN_SAFE_AVAILABLE_RAM_PIPER_MB -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.INCOMPATIBLE_RAM,
                    isSupported = false,
                    summaryAr = "الذاكرة الحرة المتاحة منخفضة جداً (${avail} MB)",
                    detailsAr = "الذاكرة الحرة غير كافية حالياً لتحميل ${spec.displayName} (${avail} MB متاحة). أغلق التطبيقات الخلفية ثم أعد المحاولة."
                )
                avail <= WARN_AVAILABLE_RAM_PIPER_MB -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.WARNING_LOW_RAM,
                    isSupported = true,
                    summaryAr = "الذاكرة الحرة منخفضة نسبياً (${avail} MB)",
                    detailsAr = "يمكن تشغيل Piper الخفيف، ويُستحسن إغلاق التطبيقات غير المستخدمة."
                )
                else -> staticReport
            }
            EngineType.SHERPA_ASR -> when {
                avail < 200L -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.INCOMPATIBLE_RAM,
                    isSupported = false,
                    summaryAr = "الذاكرة الحرة المتاحة منخفضة جداً للتفريغ الصوتي (${avail} MB)",
                    detailsAr = "يتطلب محرك التفريغ الصوتي 200 MB حرة على الأقل لتشغيل نموذج التعرف بثبات."
                )
                avail <= 380L -> ModelCompatibilityReport(
                    status = ModelCompatibilityStatus.WARNING_LOW_RAM,
                    isSupported = true,
                    summaryAr = "الذاكرة الحرة محدودة (${avail} MB)",
                    detailsAr = "سيعمل التفريغ الصوتي محلياً مع تقسيم المقاطع لتقليل استهلاك الذاكرة."
                )
                else -> staticReport
            }
        }
    }
}
