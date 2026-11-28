package com.example.contracts

import com.example.domain.LocalTaskEntry
import com.example.domain.LocalTaskPriority
import com.example.domain.LocalTaskStatus
import com.example.domain.LocalTaskType
import com.example.domain.MemoryGovernorSnapshot
import kotlinx.coroutines.flow.StateFlow

fun interface TaskProgressReporter {
    fun report(progress: Float, subtitle: String)
}

data class TaskControls(
    val onPause: (suspend () -> Unit)? = null,
    val onResume: (suspend () -> Unit)? = null,
    val onCancel: (suspend () -> Unit)? = null,
    val onRetry: (suspend () -> Unit)? = null
)

interface TaskCoordinatorGateway {
    val tasks: StateFlow<List<LocalTaskEntry>>

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
    )

    fun upsertExternalTask(
        entry: LocalTaskEntry,
        controls: TaskControls? = null
    )

    fun updateTaskStatus(
        id: String,
        status: LocalTaskStatus,
        progress: Float? = null,
        subtitle: String? = null,
        errorMessage: String? = null
    )

    suspend fun pauseTask(id: String)
    suspend fun resumeTask(id: String)
    suspend fun cancelTask(id: String)
    suspend fun retryTask(id: String)
    fun dismissTask(id: String)
    fun clearFinished()
    fun hasActiveHeavyInference(): Boolean
}

interface MemoryGovernorGateway {
    val snapshot: StateFlow<MemoryGovernorSnapshot>
    fun startMonitoring()
    fun stopMonitoring()
    fun markTtsActivity()
    fun markAsrActivity()
    suspend fun evaluateAndEnforce()
    suspend fun onTrimMemory(level: Int)
}
