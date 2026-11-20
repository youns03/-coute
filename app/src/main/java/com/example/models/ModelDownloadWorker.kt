package com.example.models

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
import com.example.data.ModelRecordEntity
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelSpec
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4102
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val spec = ModelCatalog.findById(modelId) ?: return Result.failure()

        val app = applicationContext as EcouteApplication
        val storage = app.storageManager
        val dao = app.database.modelRecordDao()

        if (!storage.hasNetworkConnection()) {
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = ModelInstallStatus.PAUSED.name,
                    downloadedBytes = storage.getTempPartFile(spec.id).length(),
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "لا يوجد اتصال بالإنترنت حالياً."
                )
            )
            return Result.retry()
        }

        val requiredTotal = spec.expectedSizeBytes +
            if (spec.requiresEspeakData) ModelCatalog.espeakNgDataSpec.expectedSizeBytes else 0L
        if (!storage.hasEnoughDiskSpace(requiredTotal)) {
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = ModelInstallStatus.FAILED.name,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "المساحة الحرة غير كافية للتنزيل وفك الضغط."
                )
            )
            return Result.failure()
        }

        setForeground(createForegroundInfo(spec.displayName, 0))

        return try {
            // Download shared eSpeak-ng data first if required by Piper and not yet installed
            if (spec.requiresEspeakData && !storage.verifyInstalledPackage(ModelCatalog.espeakNgDataSpec)) {
                downloadAndVerifySpec(ModelCatalog.espeakNgDataSpec, dao, isDependency = true)
            }
            downloadAndVerifySpec(spec, dao, isDependency = false)
            Result.success()
        } catch (ce: CancellationException) {
            val partLen = storage.getTempPartFile(spec.id).length()
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = ModelInstallStatus.PAUSED.name,
                    downloadedBytes = partLen,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "تم إيقاف التنزيل مؤقتاً."
                )
            )
            throw ce
        } catch (t: Throwable) {
            val partLen = storage.getTempPartFile(spec.id).length()
            val status = if (t.message?.contains("SHA-256") == true || t.message?.contains("تالف") == true) {
                ModelInstallStatus.CORRUPTED
            } else {
                ModelInstallStatus.FAILED
            }
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = status.name,
                    downloadedBytes = partLen,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = t.message ?: "فشل تنزيل النموذج"
                )
            )
            Result.failure()
        }
    }

    private suspend fun downloadAndVerifySpec(
        spec: ModelSpec,
        dao: com.example.data.ModelRecordDao,
        isDependency: Boolean
    ) {
        val app = applicationContext as EcouteApplication
        val storage = app.storageManager
        val partFile = storage.getTempPartFile(spec.id)
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        if (existingBytes > spec.expectedSizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        if (existingBytes < spec.expectedSizeBytes) {
            val reqBuilder = Request.Builder().url(spec.downloadUrl)
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    error("خطأ من الخادم: HTTP ${response.code}")
                }
                val supportsRange = response.code == 206
                if (existingBytes > 0L && !supportsRange) {
                    partFile.delete()
                    existingBytes = 0L
                }
                val body = response.body ?: error("استجابة فارغة من الخادم.")
                val totalBytes = if (supportsRange) {
                    spec.expectedSizeBytes
                } else {
                    body.contentLength().takeIf { it > 0L } ?: spec.expectedSizeBytes
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, supportsRange).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var transferred = existingBytes
                        var lastNotifyTime = 0L
                        while (true) {
                            if (isStopped) throw CancellationException("Paused or cancelled")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            transferred += read
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime > 450L) {
                                lastNotifyTime = now
                                val pct = ((transferred * 100L) / totalBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                                if (!isDependency) {
                                    dao.upsert(
                                        ModelRecordEntity(
                                            modelId = spec.id,
                                            status = ModelInstallStatus.DOWNLOADING.name,
                                            downloadedBytes = transferred,
                                            totalBytes = totalBytes
                                        )
                                    )
                                    setForeground(createForegroundInfo(spec.displayName, pct))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isDependency) {
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = ModelInstallStatus.VERIFYING.name,
                    downloadedBytes = spec.expectedSizeBytes,
                    totalBytes = spec.expectedSizeBytes
                )
            )
            setForeground(createForegroundInfo("${spec.displayName} (التحقق...)", 100))
        }

        val finalDir = storage.installVerifiedPackage(spec, partFile).getOrThrow()

        if (!isDependency) {
            // Mandatory load & short-synthesis verification before declaring the model ready
            app.ttsEngineManager.verifyModelLoadable(spec, finalDir).getOrThrow()
            dao.upsert(
                ModelRecordEntity(
                    modelId = spec.id,
                    status = ModelInstallStatus.INSTALLED.name,
                    downloadedBytes = spec.expectedSizeBytes,
                    totalBytes = spec.expectedSizeBytes,
                    installedDirPath = finalDir.absolutePath,
                    verifiedSha256 = true,
                    verifiedLoadTest = true,
                    lastError = null
                )
            )
        }
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تنزيل النماذج الصوتية",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (progress < 100) "جاري التنزيل محلياً ($progress%)" else "التحقق من SHA-256 والسلامة...")
            .setProgress(100, progress, progress == 0)
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
