package com.example.tts

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.TtsSynthesisResult
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
        speed: Float = 1.0f
    ): Result<TtsSynthesisResult>

    fun cancel()

    fun release()
}

data class DeviceHardwareInfo(
    val deviceModel: String,
    val primaryAbi: String,
    val totalRamMb: Long,
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
        return DeviceHardwareInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            totalRamMb = totalRamMb,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}
