package com.example.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

enum class LocalTaskType(val labelAr: String) {
    TTS_PLAYBACK("استماع صوتي"),
    FILE_TRANSLATION("ترجمة ملف"),
    AUDIO_TRANSCRIPTION("تفريغ صوتي"),
    MODEL_DOWNLOAD("تنزيل نموذج"),
    BENCHMARK("اختبار الأداء")
}

enum class LocalTaskPriority(val rank: Int) {
    INTERACTIVE(0),
    HIGH(1),
    NORMAL(2),
    BACKGROUND(3)
}

enum class LocalTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class LocalTaskEntry(
    val id: String,
    val type: LocalTaskType,
    val title: String,
    val subtitle: String = "",
    val progress: Float = 0f,
    val status: LocalTaskStatus = LocalTaskStatus.QUEUED,
    val priority: LocalTaskPriority = LocalTaskPriority.NORMAL,
    val errorMessage: String? = null,
    val canPause: Boolean = false,
    val canCancel: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = status == LocalTaskStatus.QUEUED || status == LocalTaskStatus.RUNNING

    val isTerminal: Boolean
        get() = status == LocalTaskStatus.COMPLETED ||
            status == LocalTaskStatus.FAILED ||
            status == LocalTaskStatus.CANCELLED
}

data class TaskControls(
    val onPause: (suspend () -> Unit)? = null,
    val onResume: (suspend () -> Unit)? = null,
    val onCancel: (suspend () -> Unit)? = null,
    val onRetry: (suspend () -> Unit)? = null
)

/**
 * Unified local task orchestrator coordinating TTS playback, file translation,
 * offline ASR audio transcription, model downloads, and benchmarks.
 *
 * Enforces priority ordering, prevents simultaneous heavy native inference bursts
 * from exhausting device RAM, and gives UI screens a single observable control surface.
 */
class LocalTaskCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _tasks = MutableStateFlow<List<LocalTaskEntry>>(emptyList())
    val tasks: StateFlow<List<LocalTaskEntry>> = _tasks.asStateFlow()

    private val heavyInferenceMutex = Mutex()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val taskControls = ConcurrentHashMap<String, TaskControls>()
    private val taskBlocks = ConcurrentHashMap<String, suspend (TaskProgressReporter) -> Unit>()

    fun interface TaskProgressReporter {
        fun report(progress: Float, subtitle: String)
    }

    /**
     * Launches a coroutine-backed heavy or background task under centralized orchestration.
     * If [requiresHeavyInferenceLock] is true, it waits politely for other heavy native tasks
     * so Sherpa ASR + TTS benchmarks + heavy processing never double-allocate peak native RAM.
     */
    fun launchManagedTask(
        id: String,
        type: LocalTaskType,
        title: String,
        subtitle: String = "",
        priority: LocalTaskPriority = LocalTaskPriority.NORMAL,
        requiresHeavyInferenceLock: Boolean = true,
        canCancel: Boolean = true,
        controls: TaskControls = TaskControls(),
        block: suspend (TaskProgressReporter) -> Unit
    ) {
        runningJobs[id]?.cancel()
        taskBlocks[id] = block
        taskControls[id] = controls

        upsertEntry(
            LocalTaskEntry(
                id = id,
                type = type,
                title = title,
                subtitle = subtitle,
                progress = 0f,
                status = LocalTaskStatus.QUEUED,
                priority = priority,
                canPause = controls.onPause != null,
                canCancel = canCancel
            )
        )

        val job = scope.launch {
            val reporter = TaskProgressReporter { progress, msg ->
                updateEntry(id) {
                    if (it.status == LocalTaskStatus.CANCELLED) it
                    else it.copy(
                        progress = progress.coerceIn(0f, 1f),
                        subtitle = msg,
                        status = LocalTaskStatus.RUNNING,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }

            try {
                if (requiresHeavyInferenceLock) {
                    heavyInferenceMutex.lock()
                }
                try {
                    updateEntry(id) {
                        it.copy(
                            status = LocalTaskStatus.RUNNING,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    block(reporter)
                    updateEntry(id) {
                        if (it.status == LocalTaskStatus.CANCELLED || it.status == LocalTaskStatus.PAUSED) {
                            it
                        } else {
                            it.copy(
                                status = LocalTaskStatus.COMPLETED,
                                progress = 1f,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                } finally {
                    if (requiresHeavyInferenceLock && heavyInferenceMutex.isLocked) {
                        runCatching { heavyInferenceMutex.unlock() }
                    }
                }
            } catch (_: CancellationException) {
                updateEntry(id) {
                    if (it.status == LocalTaskStatus.PAUSED) it
                    else it.copy(
                        status = LocalTaskStatus.CANCELLED,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } catch (t: Throwable) {
                updateEntry(id) {
                    it.copy(
                        status = LocalTaskStatus.FAILED,
                        errorMessage = t.message ?: "فشلت العملية المحلية",
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } finally {
                runningJobs.remove(id)
            }
        }
        runningJobs[id] = job
    }

    /**
     * Registers or updates an externally driven workflow (such as foreground AudioPlaybackController
     * or WorkManager-driven downloads/file translations) in the unified task ledger.
     */
    fun upsertExternalTask(
        entry: LocalTaskEntry,
        controls: TaskControls? = null
    ) {
        if (controls != null) {
            taskControls[entry.id] = controls
        }
        upsertEntry(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    fun updateTaskStatus(
        id: String,
        status: LocalTaskStatus,
        progress: Float? = null,
        subtitle: String? = null,
        errorMessage: String? = null
    ) {
        updateEntry(id) { existing ->
            existing.copy(
                status = status,
                progress = progress?.coerceIn(0f, 1f) ?: existing.progress,
                subtitle = subtitle ?: existing.subtitle,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun pauseTask(id: String) {
        val ctrl = taskControls[id]
        if (ctrl?.onPause != null) {
            ctrl.onPause.invoke()
            updateEntry(id) {
                it.copy(status = LocalTaskStatus.PAUSED, updatedAt = System.currentTimeMillis())
            }
        }
    }

    suspend fun resumeTask(id: String) {
        val ctrl = taskControls[id]
        if (ctrl?.onResume != null) {
            ctrl.onResume.invoke()
            return
        }
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        val block = taskBlocks[id] ?: return
        launchManagedTask(
            id = current.id,
            type = current.type,
            title = current.title,
            subtitle = current.subtitle,
            priority = current.priority,
            canCancel = current.canCancel,
            controls = ctrl ?: TaskControls(),
            block = block
        )
    }

    suspend fun cancelTask(id: String) {
        runningJobs[id]?.cancel()
        runningJobs.remove(id)
        val ctrl = taskControls[id]
        ctrl?.onCancel?.invoke()
        updateEntry(id) {
            it.copy(status = LocalTaskStatus.CANCELLED, updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun retryTask(id: String) {
        val ctrl = taskControls[id]
        if (ctrl?.onRetry != null) {
            ctrl.onRetry.invoke()
            return
        }
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        val block = taskBlocks[id] ?: return
        launchManagedTask(
            id = current.id,
            type = current.type,
            title = current.title,
            subtitle = "إعادة المحاولة...",
            priority = current.priority,
            canCancel = current.canCancel,
            controls = ctrl ?: TaskControls(),
            block = block
        )
    }

    fun dismissTask(id: String) {
        taskControls.remove(id)
        taskBlocks.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        val currentIdsToDrop = _tasks.value.filter { it.isTerminal }.map { it.id }.toSet()
        currentIdsToDrop.forEach { id ->
            taskControls.remove(id)
            taskBlocks.remove(id)
        }
        _tasks.update { list -> list.filterNot { it.isTerminal } }
    }

    fun hasActiveHeavyInference(): Boolean = heavyInferenceMutex.isLocked

    private fun upsertEntry(entry: LocalTaskEntry) {
        _tasks.update { list ->
            val without = list.filterNot { it.id == entry.id }
            (without + entry).sortedWith(
                compareBy<LocalTaskEntry> { if (it.isActive) 0 else if (it.status == LocalTaskStatus.PAUSED) 1 else 2 }
                    .thenBy { it.priority.rank }
                    .thenByDescending { it.updatedAt }
            )
        }
    }

    private fun updateEntry(id: String, transform: (LocalTaskEntry) -> LocalTaskEntry) {
        _tasks.update { list ->
            list.map { item -> if (item.id == id) transform(item) else item }
                .sortedWith(
                    compareBy<LocalTaskEntry> { if (it.isActive) 0 else if (it.status == LocalTaskStatus.PAUSED) 1 else 2 }
                        .thenBy { it.priority.rank }
                        .thenByDescending { it.updatedAt }
                )
        }
    }
}
