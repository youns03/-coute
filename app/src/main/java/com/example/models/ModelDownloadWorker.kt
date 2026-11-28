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
import com.example.R
import com.example.contracts.AppContainer
import com.example.contracts.AppContainerProvider
import com.example.contracts.ModelRecordStore
import com.example.domain.EngineType
import com.example.domain.LocalTaskStatus
import com.example.domain.MemoryDiagnostics
import com.example.domain.ModelInstallRecord
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelSpec
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

        internal fun isValidContentRangeHeader(
            header: String?,
            expectedStart: Long,
            expectedTotalBytes: Long
        ): Boolean {
            if (header.isNullOrBlank()) return false
            val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            val totalStr = match.groupValues[3]
            val totalOk = if (totalStr == "*") {
                true
            } else {
                totalStr.toLongOrNull() == expectedTotalBytes
            }
            return start == expectedStart &&
                end >= start &&
                end == expectedTotalBytes - 1L &&
                totalOk
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val container = (applicationContext as AppContainerProvider).container
        val catalog = container.modelCatalog
        val spec = catalog.findById(modelId) ?: return Result.failure()
        val storage = container.storageManager
        val recordStore = container.modelRecordStore

        val compat = MemoryDiagnostics.evaluateModelCompatibility(spec, applicationContext)
        if (!compat.isSupported) {
            recordStore.upsert(
                ModelInstallRecord(
                    modelId = spec.id,
                    status = ModelInstallStatus.FAILED.name,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = compat.detailsAr ?: compat.summaryAr
                )
            )
            return Result.failure()
        }

        if (!storage.hasNetworkConnection()) {
            recordStore.upsert(
                ModelInstallRecord(
                    modelId = spec.id,
                    status = ModelInstallStatus.PAUSED.name,
                    downloadedBytes = storage.getTempPartFile(spec.id).length(),
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "لا يوجد اتصال بالإنترنت حالياً."
                )
            )
            return Result.retry()
        }

        val needsEspeak = spec.requiresEspeakData &&
            !storage.verifyInstalledPackage(catalog.espeakNgDataSpec, verifyIntegrity = true)
        if (!storage.hasEnoughDiskSpace(spec, includeEspeakDependency = needsEspeak)) {
            recordStore.upsert(
                ModelInstallRecord(
                    modelId = spec.id,
                    status = ModelInstallStatus.FAILED.name,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "المساحة الحرة غير كافية للتنزيل والتثبيت بأمان."
                )
            )
            return Result.failure()
        }

        setForeground(createForegroundInfo(spec.displayName, 0))

        return try {
            // Download shared eSpeak-ng data first if required by Piper and not yet installed
            if (spec.requiresEspeakData) {
                storage.withSharedEspeakInstallLock {
                    if (!storage.verifyInstalledPackage(catalog.espeakNgDataSpec, verifyIntegrity = true)) {
                        downloadAndVerifySpec(
                            container = container,
                            spec = catalog.espeakNgDataSpec,
                            recordStore = recordStore,
                            isDependency = true
                        )
                    }
                }
            }
            downloadAndVerifySpec(
                container = container,
                spec = spec,
                recordStore = recordStore,
                isDependency = false
            )
            Result.success()
        } catch (ce: CancellationException) {
            val partLen = storage.getTempPartFile(spec.id).length()
            recordStore.upsert(
                ModelInstallRecord(
                    modelId = spec.id,
                    status = ModelInstallStatus.PAUSED.name,
                    downloadedBytes = partLen,
                    totalBytes = spec.expectedSizeBytes,
                    lastError = "تم إيقاف التنزيل مؤقتاً."
                )
            )
            throw ce
        } catch (t: Throwable) {
            val isCorrupted = t.message?.contains("SHA-256") == true ||
                t.message?.contains("تالف") == true ||
                t.message?.contains("عدم تطابق الحجم") == true
            if (isCorrupted) {
                storage.getTempPartFile(spec.id).delete()
            }
            val partLen = storage.getTempPartFile(spec.id).length()
            val status = if (isCorrupted) ModelInstallStatus.CORRUPTED else ModelInstallStatus.FAILED
            recordStore.upsert(
                ModelInstallRecord(
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
        container: AppContainer,
        spec: ModelSpec,
        recordStore: ModelRecordStore,
        isDependency: Boolean
    ) {
        val storage = container.storageManager
        val taskCoordinator = container.taskCoordinator
        val partFile = storage.getTempPartFile(spec.id)
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        val previousRecord = if (!isDependency) recordStore.getRecord(spec.id) else null
        if (existingBytes > spec.expectedSizeBytes ||
            (existingBytes == spec.expectedSizeBytes && previousRecord?.status == ModelInstallStatus.CORRUPTED.name)
        ) {
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
                val contentRange = response.header("Content-Range")
                val validRangeResume = response.code == 206 &&
                    isValidContentRangeHeader(
                        header = contentRange,
                        expectedStart = existingBytes,
                        expectedTotalBytes = spec.expectedSizeBytes
                    )
                if (existingBytes > 0L && !validRangeResume) {
                    if (response.code == 206) {
                        partFile.delete()
                        error("استجاب الخادم بـ HTTP 206 لكن رأس Content-Range غير متطابق ($contentRange). أعد المحاولة.")
                    }
                    // Server ignored Range and returned 200 full stream from byte 0
                    partFile.delete()
                    existingBytes = 0L
                }
                val body = response.body ?: error("استجابة فارغة من الخادم.")
                val totalBytes = if (validRangeResume) {
                    spec.expectedSizeBytes
                } else {
                    body.contentLength().takeIf { it > 0L } ?: spec.expectedSizeBytes
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, validRangeResume).use { fos ->
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
                                    recordStore.upsert(
                                        ModelInstallRecord(
                                            modelId = spec.id,
                                            status = ModelInstallStatus.DOWNLOADING.name,
                                            downloadedBytes = transferred,
                                            totalBytes = totalBytes
                                        )
                                    )
                                    taskCoordinator.updateTaskStatus(
                                        id = "dl_${spec.id}",
                                        status = LocalTaskStatus.RUNNING,
                                        progress = (pct / 100f),
                                        subtitle = "جاري التنزيل ($pct%)"
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
            recordStore.upsert(
                ModelInstallRecord(
                    modelId = spec.id,
                    status = ModelInstallStatus.VERIFYING.name,
                    downloadedBytes = spec.expectedSizeBytes,
                    totalBytes = spec.expectedSizeBytes
                )
            )
            taskCoordinator.updateTaskStatus(
                id = "dl_${spec.id}",
                status = LocalTaskStatus.RUNNING,
                progress = 0.98f,
                subtitle = "التحقق من السلامة والتحميل..."
            )
            setForeground(createForegroundInfo("${spec.displayName} (التحقق...)", 100))
        }

        val finalDir = storage.installVerifiedPackage(
            spec = spec,
            downloadedFile = partFile,
            deferCommitConfirmation = true
        ).getOrThrow()

        if (isDependency) {
            // eSpeak data has a structural + cryptographic contract; it is not a TTS/ASR model.
            storage.confirmInstalledPackage(spec).getOrThrow()
        } else {
            // Keep the previous installation recoverable until the native engine accepts the new files.
            try {
                if (spec.engineType == EngineType.SHERPA_ASR) {
                    container.speechTranscriptionManager.verifyModelLoadable(spec, finalDir).getOrThrow()
                } else {
                    container.ttsEngineManager.verifyModelLoadable(spec, finalDir).getOrThrow()
                }
            } catch (t: Throwable) {
                taskCoordinator.updateTaskStatus(
                    id = "dl_${spec.id}",
                    status = LocalTaskStatus.FAILED,
                    errorMessage = t.message
                )
                if (!storage.rollbackInstalledPackage(spec.id)) {
                    t.addSuppressed(
                        IllegalStateException(
                            "تعذرت استعادة النسخة السابقة فوراً؛ ستبقى ملفات الاستعادة لمحاولة الإصلاح عند بدء التشغيل."
                        )
                    )
                }
                throw t
            }
            storage.confirmInstalledPackage(spec).getOrThrow()
            recordStore.upsert(
                ModelInstallRecord(
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
            taskCoordinator.updateTaskStatus(
                id = "dl_${spec.id}",
                status = LocalTaskStatus.COMPLETED,
                progress = 1f,
                subtitle = "مثبّت وموثّق"
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
