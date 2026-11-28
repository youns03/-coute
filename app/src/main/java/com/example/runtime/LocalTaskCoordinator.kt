package com.example.runtime

import com.example.contracts.TaskControls
import com.example.contracts.TaskCoordinatorGateway
import com.example.contracts.TaskProgressReporter
import com.example.domain.LocalTaskEntry
import com.example.domain.LocalTaskPriority
import com.example.domain.LocalTaskStatus
import com.example.domain.LocalTaskType
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

/**
 * Unified local task orchestrator coordinating TTS playback, file translation,
 * offline ASR audio transcription, model downloads, and benchmarks.
 *
 * Enforces priority ordering, prevents simultaneous heavy native inference bursts
 * from exhausting device RAM, and gives UI screens a single observable control surface.
 */
class LocalTaskCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TaskCoordinatorGateway {
    private val _tasks = MutableStateFlow<List<LocalTaskEntry>>(emptyList())
    override val tasks: StateFlow<List<LocalTaskEntry>> = _tasks.asStateFlow()

    private val heavyInferenceMutex = Mutex()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val taskControls = ConcurrentHashMap<String, TaskControls>()
    private val taskBlocks = ConcurrentHashMap<String, suspend (TaskProgressReporter) -> Unit>()

    /**
     * Launches a coroutine-backed heavy or background task under centralized orchestration.
     * If [requiresHeavyInferenceLock] is true, it waits politely for other heavy native tasks
     * so Sherpa ASR + TTS benchmarks + heavy processing never double-allocate peak native RAM.
     */
    override fun launchManagedTask(
        id: String,
        type: LocalTaskType,
        title: String,
        subtitle: String,
        priority: LocalTaskPriority,
        requiresHeavyInferenceLock: Boolean,
        canCancel: Boolean,
        controls: TaskControls,
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
    override fun upsertExternalTask(
        entry: LocalTaskEntry,
        controls: TaskControls?
    ) {
        if (controls != null) {
            taskControls[entry.id] = controls
        }
        upsertEntry(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    override fun updateTaskStatus(
        id: String,
        status: LocalTaskStatus,
        progress: Float?,
        subtitle: String?,
        errorMessage: String?
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

    override suspend fun pauseTask(id: String) {
        val ctrl = taskControls[id]
        if (ctrl?.onPause != null) {
            ctrl.onPause.invoke()
            updateEntry(id) {
                it.copy(status = LocalTaskStatus.PAUSED, updatedAt = System.currentTimeMillis())
            }
        }
    }

    override suspend fun resumeTask(id: String) {
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

    override suspend fun cancelTask(id: String) {
        runningJobs[id]?.cancel()
        runningJobs.remove(id)
        val ctrl = taskControls[id]
        ctrl?.onCancel?.invoke()
        updateEntry(id) {
            it.copy(status = LocalTaskStatus.CANCELLED, updatedAt = System.currentTimeMillis())
        }
    }

    override suspend fun retryTask(id: String) {
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

    override fun dismissTask(id: String) {
        taskControls.remove(id)
        taskBlocks.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    override fun clearFinished() {
        val currentIdsToDrop = _tasks.value.filter { it.isTerminal }.map { it.id }.toSet()
        currentIdsToDrop.forEach { id ->
            taskControls.remove(id)
            taskBlocks.remove(id)
        }
        _tasks.update { list -> list.filterNot { it.isTerminal } }
    }

    override fun hasActiveHeavyInference(): Boolean = heavyInferenceMutex.isLocked

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
