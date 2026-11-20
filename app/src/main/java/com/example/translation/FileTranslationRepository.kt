package com.example.translation

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.BookImporter
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobDao
import com.example.data.TranslationJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class FileTranslationRepository(
    private val context: Context,
    private val jobDao: TranslationJobDao,
    private val bookImporter: BookImporter
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun observeAllJobs(): Flow<List<TranslationJobEntity>> = jobDao.observeAllJobs()

    fun observeChunks(jobId: String): Flow<List<TranslationChunkEntity>> = jobDao.observeChunks(jobId)

    suspend fun reconcileOnStartup() {
        jobDao.resetRunningJobsToPaused()
    }

    suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?
    ): Result<TranslationJobEntity> = withContext(Dispatchers.IO) {
        bookImporter.parseDocumentFromUri(uri).mapCatching { doc ->
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
            job
        }
    }

    suspend fun updateJobSourceLanguageIfEmpty(jobId: String, language: SourceLanguage): Boolean {
        val job = jobDao.getJob(jobId) ?: return false
        if (job.status == "RUNNING") return false
        val actualDone = jobDao.countCompletedChunks(jobId)
        if (actualDone > 0 || job.completedChunks > 0) return false
        jobDao.updateJob(
            job.copy(
                sourceLanguage = language.code,
                status = "PENDING",
                lastError = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    private fun cancelWorkSafely(uniqueWorkName: String) {
        runCatching { workManager.cancelUniqueWork(uniqueWorkName) }
    }

    suspend fun resetAndChangeJobSourceLanguage(jobId: String, language: SourceLanguage) {
        cancelWorkSafely("file_tx_$jobId")
        val job = jobDao.getJob(jobId) ?: return
        jobDao.resetChunksForJob(jobId)
        jobDao.updateJob(
            job.copy(
                sourceLanguage = language.code,
                status = "PENDING",
                completedChunks = 0,
                lastError = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun startOrResumeJob(jobId: String) {
        val job = jobDao.getJob(jobId) ?: return
        if (job.status == "COMPLETED") return
        jobDao.updateJobProgress(jobId, job.completedChunks, "RUNNING", null)

        val data = Data.Builder()
            .putString(FileTranslationWorker.KEY_JOB_ID, jobId)
            .build()
        val request = OneTimeWorkRequestBuilder<FileTranslationWorker>()
            .setInputData(data)
            .addTag("file_translation_$jobId")
            .build()

        workManager.enqueueUniqueWork(
            "file_tx_$jobId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun pauseJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        val done = jobDao.countCompletedChunks(jobId)
        jobDao.updateJobProgress(jobId, done, "PAUSED", null)
    }

    suspend fun deleteJob(jobId: String) {
        cancelWorkSafely("file_tx_$jobId")
        jobDao.deleteJob(jobId)
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
