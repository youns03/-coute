package com.example.translation

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.contracts.DocumentImportGateway
import com.example.contracts.FileTranslationGateway
import com.example.contracts.RecentActivityGateway
import com.example.contracts.TaskControls
import com.example.contracts.TaskCoordinatorGateway
import com.example.contracts.TranslationJobStore
import com.example.domain.DocumentExtractionOptions
import com.example.domain.ExportMode
import com.example.domain.FileInspectionPreview
import com.example.domain.ImportProgressEvent
import com.example.domain.LocalTaskEntry
import com.example.domain.LocalTaskPriority
import com.example.domain.LocalTaskStatus
import com.example.domain.LocalTaskType
import com.example.domain.RecentActivity
import com.example.domain.SourceLanguage
import com.example.domain.TranslationChunk
import com.example.domain.TranslationDirection
import com.example.domain.TranslationJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class FileTranslationRepository(
    private val context: Context,
    private val jobStore: TranslationJobStore,
    private val documentImportGateway: DocumentImportGateway,
    private val recentActivityGateway: RecentActivityGateway? = null,
    private val taskCoordinator: TaskCoordinatorGateway? = null
) : FileTranslationGateway {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val jobMutationMutex = Mutex()

    override fun observeAllJobs(): Flow<List<TranslationJob>> = jobStore.observeAllJobs()

    override fun observeChunks(jobId: String): Flow<List<TranslationChunk>> = jobStore.observeChunks(jobId)

    override suspend fun reconcileOnStartup() {
        jobStore.resetRunningJobsToPaused()
    }

    override suspend fun inspectImportUris(uris: List<Uri>): List<FileInspectionPreview> =
        documentImportGateway.inspectUris(uris)

    override suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?
    ): Result<TranslationJob> = createJobFromUri(
        uri = uri,
        selectedSourceLanguage = selectedSourceLanguage,
        options = DocumentExtractionOptions(),
        onProgress = {}
    )

    override suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?,
        options: DocumentExtractionOptions,
        onProgress: (ImportProgressEvent) -> Unit
    ): Result<TranslationJob> = withContext(Dispatchers.IO) {
        documentImportGateway.parseDocumentFromUri(uri, options, onProgress).mapCatching { doc ->
            if (selectedSourceLanguage == null && doc.detectedLanguageCode.equals("ar", ignoreCase = true)) {
                error("المستند المكتشف باللغة العربية. مترجم الملفات المحلي يترجم من الفرنسية أو الإنجليزية إلى العربية فقط؛ يمكنك استيراد الملفات العربية مباشرة إلى المكتبة للقراءة.")
            }
            onProgress(ImportProgressEvent(doc.fileName, "جاري تقسيم المقاطع للترجمة...", 0.96f))
            val jobId = "tx_${UUID.randomUUID().toString().take(8)}"
            val chunks = DocumentChunker.buildChunks(jobId, doc)
            if (chunks.isEmpty()) error("لا توجد نصوص قابلة للترجمة داخل هذا الملف.")

            val lang = selectedSourceLanguage ?: doc.detectedLanguage
            val job = TranslationJob(
                id = jobId,
                title = doc.title,
                originalFileName = doc.fileName,
                sourceType = doc.sourceType,
                sourceLanguage = lang.code,
                targetLanguage = "ar",
                status = "PENDING",
                totalChunks = chunks.size,
                completedChunks = 0
            )
            jobStore.insertJobWithChunks(job, chunks)
            recentActivityGateway?.upsert(
                RecentActivity(
                    id = "act_tx_$jobId",
                    kind = "TRANSLATION",
                    title = doc.title,
                    subtitle = "مهمة ترجمة (${doc.sourceType}) • ${chunks.size} مقطع",
                    targetRoute = "translator",
                    targetId = jobId,
                    progress = 0f
                )
            )
            job
        }
    }

    override suspend fun updateJobSourceLanguageIfEmpty(jobId: String, language: SourceLanguage): Boolean {
        return jobMutationMutex.withLock {
            val job = jobStore.getJob(jobId) ?: return@withLock false
            if (job.status == "RUNNING") return@withLock false
            val actualDone = jobStore.countCompletedChunks(jobId)
            if (actualDone > 0 || job.completedChunks > 0) return@withLock false
            jobStore.updateJob(
                job.copy(
                    sourceLanguage = language.code,
                    status = "PENDING",
                    jobEpoch = job.jobEpoch + 1,
                    lastError = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    private fun cancelWorkSafely(uniqueWorkName: String) {
        runCatching { workManager.cancelUniqueWork(uniqueWorkName) }
    }

    override suspend fun resetAndChangeJobSourceLanguage(jobId: String, language: SourceLanguage) {
        jobMutationMutex.lock()
        try {
            cancelWorkSafely("file_tx_$jobId")
            val exists = jobStore.getJob(jobId) != null
            if (exists) {
                jobStore.resetJobAtomically(jobId, language.code)
            }
        } finally {
            jobMutationMutex.unlock()
        }
    }

    override suspend fun startOrResumeJob(jobId: String) {
        jobMutationMutex.withLock {
            val job = jobStore.getJob(jobId) ?: return@withLock
            if (job.status == "COMPLETED" || job.status == "RUNNING") return@withLock
            jobStore.updateJobProgress(jobId, job.completedChunks, "RUNNING", null)

            taskCoordinator?.upsertExternalTask(
                entry = LocalTaskEntry(
                    id = "tx_$jobId",
                    type = LocalTaskType.FILE_TRANSLATION,
                    title = job.title,
                    subtitle = "ترجمة ${job.completedChunks}/${job.totalChunks}",
                    progress = if (job.totalChunks > 0) job.completedChunks.toFloat() / job.totalChunks else 0f,
                    status = LocalTaskStatus.RUNNING,
                    priority = LocalTaskPriority.NORMAL,
                    canPause = true,
                    canCancel = true
                ),
                controls = TaskControls(
                    onPause = { pauseJob(jobId) },
                    onResume = { startOrResumeJob(jobId) },
                    onCancel = { deleteJob(jobId) }
                )
            )

            val data = Data.Builder()
                .putString(FileTranslationWorker.KEY_JOB_ID, jobId)
                .build()
            val request = OneTimeWorkRequestBuilder<FileTranslationWorker>()
                .setInputData(data)
                .addTag("file_translation_$jobId")
                .build()

            workManager.enqueueUniqueWork(
                "file_tx_$jobId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun pauseJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        val done = jobStore.countCompletedChunks(jobId)
        jobStore.updateJobProgress(jobId, done, "PAUSED", null)
        taskCoordinator?.updateTaskStatus("tx_$jobId", LocalTaskStatus.PAUSED, subtitle = "متوقف مؤقتاً")
    }

    override suspend fun deleteJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        jobStore.deleteJob(jobId)
        recentActivityGateway?.deleteByTargetId(jobId)
        taskCoordinator?.dismissTask("tx_$jobId")
    }

    override suspend fun exportJobToUri(
        jobId: String,
        targetUri: Uri,
        mode: ExportMode
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val job = jobStore.getJob(jobId) ?: error("المهمة غير موجودة.")
            val chunks = jobStore.getAllChunks(jobId)
            val direction = TranslationDirection.forSource(SourceLanguage.fromCode(job.sourceLanguage))
            val formatted = DocumentChunker.formatForExport(
                title = job.title,
                direction = direction,
                chunks = chunks,
                mode = mode
            )
            DocumentChunker.exportToUri(context, targetUri, formatted).getOrThrow()
            job.title
        }
    }
}
