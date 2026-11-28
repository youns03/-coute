package com.example.runtime

import android.content.Context
import com.example.contracts.MemoryGovernorGateway
import com.example.domain.MemoryDiagnostics
import com.example.domain.MemoryGovernorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors local runtime memory footprint and automatically unloads idle TTS/ASR models
 * after inactivity or when device memory headroom becomes tight, without interrupting
 * active playback or active transcription.
 */
class RuntimeMemoryGovernor(
    private val context: Context,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val isTtsBusyProvider: () -> Boolean,
    private val isTtsLoadedProvider: () -> Boolean,
    private val releaseTtsEngine: suspend () -> Unit,
    private val isAsrBusyProvider: () -> Boolean,
    private val isAsrLoadedProvider: () -> Boolean,
    private val releaseAsrEngine: suspend () -> Unit
) : MemoryGovernorGateway {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
        const val DEFAULT_CHECK_INTERVAL_MS = 20_000L
        const val LOW_HEADROOM_THRESHOLD_MB = 300L
    }

    private val _snapshot = MutableStateFlow(MemoryGovernorSnapshot())
    override val snapshot: StateFlow<MemoryGovernorSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var lastTtsActivityAt: Long = nowProvider()

    @Volatile
    private var lastAsrActivityAt: Long = nowProvider()

    private var monitorJob: Job? = null

    override fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(checkIntervalMs)
                evaluateAndEnforce()
            }
        }
    }

    override fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    override fun markTtsActivity() {
        lastTtsActivityAt = nowProvider()
    }

    override fun markAsrActivity() {
        lastAsrActivityAt = nowProvider()
    }

    override suspend fun evaluateAndEnforce() {
        val now = nowProvider()
        val hw = runCatching { MemoryDiagnostics.getDeviceHardwareInfo(context) }.getOrNull()
        val pssMb = runCatching { MemoryDiagnostics.getProcessPssMb(context) }.getOrDefault(0f)
        val availMb = hw?.availableRamMb ?: 0L
        val lowMemoryPressure = availMb in 1L..LOW_HEADROOM_THRESHOLD_MB

        var cleanupNote: String? = _snapshot.value.lastCleanupReason
        var cleanupTime: Long = _snapshot.value.lastCleanupAtMs

        val ttsLoaded = isTtsLoadedProvider()
        val ttsBusy = isTtsBusyProvider()
        if (ttsLoaded && !ttsBusy) {
            val idleFor = now - lastTtsActivityAt
            val shortIdlePressure = lowMemoryPressure && idleFor >= 15_000L
            if (idleFor >= idleTimeoutMs || shortIdlePressure) {
                runCatching { releaseTtsEngine() }
                cleanupNote = if (shortIdlePressure) {
                    "تم تفريغ محرك الصوت تلقائياً بسبب انخفاض الذاكرة المتاحة (${availMb} MB)"
                } else {
                    "تم تفريغ محرك الصوت تلقائياً بعد عدم النشاط لتوفير الذاكرة"
                }
                cleanupTime = now
            }
        }

        val asrLoaded = isAsrLoadedProvider()
        val asrBusy = isAsrBusyProvider()
        if (asrLoaded && !asrBusy) {
            val idleFor = now - lastAsrActivityAt
            val shortIdlePressure = lowMemoryPressure && idleFor >= 10_000L
            if (idleFor >= idleTimeoutMs || shortIdlePressure) {
                runCatching { releaseAsrEngine() }
                cleanupNote = if (shortIdlePressure) {
                    "تم تفريغ محرك التفريغ الصوتي تلقائياً لتحرير الذاكرة"
                } else {
                    "تم تفريغ محرك التفريغ الصوتي تلقائياً بعد انتهاء المهلة"
                }
                cleanupTime = now
            }
        }

        _snapshot.value = MemoryGovernorSnapshot(
            processPssMb = pssMb,
            availableRamMb = availMb,
            isTtsEngineLoaded = isTtsLoadedProvider(),
            isAsrEngineLoaded = isAsrLoadedProvider(),
            lastCleanupReason = cleanupNote,
            lastCleanupAtMs = cleanupTime
        )
    }

    override suspend fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (isAsrLoadedProvider() && !isAsrBusyProvider()) {
                runCatching { releaseAsrEngine() }
            }
            if (isTtsLoadedProvider() && !isTtsBusyProvider()) {
                runCatching { releaseTtsEngine() }
            }
            evaluateAndEnforce()
        }
    }
}
