package com.example.translation

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.BookImporter
import com.example.data.RecentActivityDao
import com.example.data.RecentActivityEntity
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobDao
import com.example.data.TranslationJobEntity
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.FileInspectionPreview
import com.example.data.importengine.ImportProgressEvent
import com.example.runtime.LocalTaskCoordinator
import com.example.runtime.LocalTaskEntry
import com.example.runtime.LocalTaskPriority
import com.example.runtime.LocalTaskStatus
import com.example.runtime.LocalTaskType
import com.example.runtime.TaskControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class FileTranslationRepository(
    private val context: Context,
    private val jobDao: TranslationJobDao,
    private val bookImporter: BookImporter,
    private val recentActivityDao: RecentActivityDao? = null,
    private val taskCoordinator: LocalTaskCoordinator? = null
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val jobMutationMutex = Mutex()

    fun observeAllJobs(): Flow<List<TranslationJobEntity>> = jobDao.observeAllJobs()

    fun observeChunks(jobId: String): Flow<List<TranslationChunkEntity>> = jobDao.observeChunks(jobId)

    suspend fun reconcileOnStartup() {
        jobDao.resetRunningJobsToPaused()
    }

    suspend fun inspectImportUris(uris: List<Uri>): List<FileInspectionPreview> =
        bookImporter.inspectUris(uris)

    suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?
    ): Result<TranslationJobEntity> = createJobFromUri(
        uri = uri,
        selectedSourceLanguage = selectedSourceLanguage,
        options = DocumentExtractionOptions(),
        onProgress = {}
    )

    suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?,
        options: DocumentExtractionOptions,
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<TranslationJobEntity> = withContext(Dispatchers.IO) {
        bookImporter.parseDocumentFromUri(uri, options, onProgress).mapCatching { doc ->
            if (selectedSourceLanguage == null && doc.detectedLanguageCode.equals("ar", ignoreCase = true)) {
                error("المستند المكتشف باللغة العربية. مترجم الملفات المحلي يترجم من الفرنسية أو الإنجليزية إلى العربية فقط؛ يمكنك استيراد الملفات العربية مباشرة إلى المكتبة للقراءة.")
            }
            onProgress(ImportProgressEvent(doc.fileName, "جاري تقسيم المقاطع للترجمة...", 0.96f))
            val jobId = "tx_${UUID.randomUUID().toString().take(8)}"
            val chunks = DocumentChunker.buildChunks(jobId, doc)
            if (chunks.isEmpty()) error("لا توجد نصوص قابلة للترجمة داخل هذا الملف.")

            val lang = selectedSourceLanguage ?: doc.detectedLanguage
            val job = TranslationJobEntity(
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
            jobDao.insertJobWithChunks(job, chunks)
            recentActivityDao?.upsert(
                RecentActivityEntity(
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

    suspend fun updateJobSourceLanguageIfEmpty(jobId: String, language: SourceLanguage): Boolean {
        return jobMutationMutex.withLock {
            val job = jobDao.getJob(jobId) ?: return@withLock false
            if (job.status == "RUNNING") return@withLock false
            val actualDone = jobDao.countCompletedChunks(jobId)
            if (actualDone > 0 || job.completedChunks > 0) return@withLock false
            jobDao.updateJob(
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

    suspend fun resetAndChangeJobSourceLanguage(jobId: String, language: SourceLanguage) {
        jobMutationMutex.lock()
        try {
            cancelWorkSafely("file_tx_$jobId")
            val exists = jobDao.getJob(jobId) != null
            if (exists) {
                jobDao.resetJobAtomically(jobId, language.code)
            }
        } finally {
            jobMutationMutex.unlock()
        }
    }

    suspend fun startOrResumeJob(jobId: String) {
        jobMutationMutex.withLock {
            val job = jobDao.getJob(jobId) ?: return@withLock
            if (job.status == "COMPLETED" || job.status == "RUNNING") return@withLock
            jobDao.updateJobProgress(jobId, job.completedChunks, "RUNNING", null)

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

    suspend fun pauseJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        val done = jobDao.countCompletedChunks(jobId)
        jobDao.updateJobProgress(jobId, done, "PAUSED", null)
        taskCoordinator?.updateTaskStatus("tx_$jobId", LocalTaskStatus.PAUSED, subtitle = "متوقف مؤقتاً")
    }

    suspend fun deleteJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        jobDao.deleteJob(jobId)
        recentActivityDao?.deleteByTargetId(jobId)
        taskCoordinator?.dismissTask("tx_$jobId")
    }

    suspend fun exportJobToUri(
        jobId: String,
        targetUri: Uri,
        mode: ExportMode
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val job = jobDao.getJob(jobId) ?: error("المهمة غير موجودة.")
            val chunks = jobDao.getAllChunks(jobId)
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

