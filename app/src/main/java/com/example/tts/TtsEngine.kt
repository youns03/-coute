package com.example.tts

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelCompatibilityReport
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelSpec
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface TtsEngine {
    val engineType: EngineType
    val modelId: String
    val sampleRateHz: Int
    val isLoaded: Boolean
    val activeAccelerator: String
    val fellBackToCpu: Boolean
    val fallbackReason: String?

    suspend fun initialize(
        spec: ModelSpec,
        modelDir: File,
        acceleratorMode: AcceleratorMode
    ): Result<Long> // Returns model load time in ms

    suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float = 1.0f,
        locale: String = "fr-FR"
    ): Result<TtsSynthesisResult>

    fun synthesizeStream(
        spokenText: String,
        voiceId: String,
        speed: Float = 1.0f,
        locale: String = "fr-FR"
    ): Flow<TtsStreamChunk>

    fun cancel()

    fun release()
}

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
        if (hw.availableRamMb in 1L..700L && spec.engineType == EngineType.KOKORO_LITERT) {
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
}
