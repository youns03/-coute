package com.example.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.EcouteApplication
import com.example.R
import kotlinx.coroutines.CancellationException

/**
 * Independent WorkManager foreground worker for translating long documents in resumable chunks.
 * Persists every completed chunk in Room so leaving the screen or backgrounding the app preserves progress.
 */
class FileTranslationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_JOB_ID = "translation_job_id"
        private const val CHANNEL_ID = "file_translation_jobs"
        private const val NOTIFICATION_ID = 4208
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val app = applicationContext as EcouteApplication
        val jobDao = app.database.translationJobDao()
        val engineManager = app.translationEngineManager

        val job = jobDao.getJob(jobId) ?: return Result.failure()
        val sourceLang = SourceLanguage.fromCode(job.sourceLanguage)
        val direction = TranslationDirection.forSource(sourceLang)

        setForeground(createForegroundInfo(job.title, job.completedChunks, job.totalChunks))

        if (!engineManager.isDirectionReady(direction)) {
            val msg = "حزمة الترجمة المحلية (${direction.shortBadge}) غير مثبّتة. نزّلها أولاً ثم استأنف."
            jobDao.updateJobProgress(
                jobId = jobId,
                completedChunks = jobDao.countCompletedChunks(jobId),
                status = "FAILED",
                lastError = msg
            )
            return Result.failure()
        }

        return try {
            var completed = jobDao.countCompletedChunks(jobId)
            jobDao.updateJobProgress(jobId, completed, "RUNNING", null)

            val pending = jobDao.getPendingChunks(jobId)
            for (chunk in pending) {
                if (isStopped) {
                    jobDao.updateJobProgress(jobId, completed, "PAUSED", null)
                    return Result.success()
                }

                val translated = engineManager.translateRaw(direction, chunk.originalText).getOrThrow()
                jobDao.markChunkTranslated(chunk.id, translated)
                completed++

                jobDao.updateJobProgress(jobId, completed, "RUNNING", null)
                setForeground(createForegroundInfo(job.title, completed, job.totalChunks))
            }

            jobDao.updateJobProgress(jobId, completed, "COMPLETED", null)
            Result.success()
        } catch (ce: CancellationException) {
            val done = jobDao.countCompletedChunks(jobId)
            jobDao.updateJobProgress(jobId, done, "PAUSED", null)
            throw ce
        } catch (t: Throwable) {
            val done = jobDao.countCompletedChunks(jobId)
            jobDao.updateJobProgress(
                jobId = jobId,
                completedChunks = done,
                status = "FAILED",
                lastError = t.message ?: "تعذر إكمال الترجمة المحلية"
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(title: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ترجمة الملفات الطويلة",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val safeTotal = total.coerceAtLeast(1)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ترجمة: $title")
            .setContentText("تم إنجاز $completed من $total جزء محلياً")
            .setProgress(safeTotal, completed.coerceIn(0, safeTotal), false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
