package com.example.models

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.ModelRecordDao
import com.example.data.ModelRecordEntity
import com.example.domain.ModelCompatibilityReport
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelSpec
import com.example.domain.StorageBreakdown
import com.example.tts.MemoryDiagnostics
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class ModelUiEntry(
    val spec: ModelSpec,
    val status: ModelInstallStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val verifiedSha256: Boolean,
    val verifiedLoadTest: Boolean,
    val lastError: String?,
    val isActive: Boolean,
    val compatibility: ModelCompatibilityReport = ModelCompatibilityReport(
        status = ModelCompatibilityStatus.COMPATIBLE,
        isSupported = true,
        summaryAr = "متوافق"
    )
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelRepository(
    private val context: Context,
    private val modelRecordDao: ModelRecordDao,
    private val storageManager: ModelStorageManager,
    private val ttsEngineManager: TtsEngineManager
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun observeModels(activeModelIdFlow: Flow<String>): Flow<List<ModelUiEntry>> {
        return combine(modelRecordDao.observeAll(), activeModelIdFlow) { records, activeId ->
            val byId = records.associateBy { it.modelId }
            val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
            ModelCatalog.ttsModels.map { spec ->
                val rec = byId[spec.id]
                val status = rec?.let {
                    ModelInstallStatus.entries.firstOrNull { e -> e.name == it.status }
                } ?: ModelInstallStatus.NOT_INSTALLED
                val partBytes = if (status == ModelInstallStatus.PAUSED || status == ModelInstallStatus.DOWNLOADING) {
                    maxOf(rec?.downloadedBytes ?: 0L, storageManager.getTempPartFile(spec.id).length())
                } else {
                    rec?.downloadedBytes ?: 0L
                }
                val compat = MemoryDiagnostics.evaluateModelCompatibility(spec, hw)
                ModelUiEntry(
                    spec = spec,
                    status = status,
                    downloadedBytes = partBytes,
                    totalBytes = rec?.totalBytes?.takeIf { it > 0L } ?: spec.expectedSizeBytes,
                    verifiedSha256 = rec?.verifiedSha256 ?: false,
                    verifiedLoadTest = rec?.verifiedLoadTest ?: false,
                    lastError = rec?.lastError,
                    isActive = spec.id == activeId,
                    compatibility = compat
                )
            }
        }
    }

    /**
     * Reconciles database status against actual files in permanent filesDir after app restart/reboot.
     * Enforces cryptographic SHA-256 manifest checks, hardware compatibility, and genuine engine load verification.
     */
    suspend fun reconcileStorageOnStartup() {
        storageManager.recoverPendingInstalls()
        val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
        for (spec in ModelCatalog.ttsModels) {
            val rec = modelRecordDao.getRecord(spec.id)
            val modelDir = storageManager.getInstalledModelDir(spec.id)
            val cryptoValid = storageManager.verifyInstalledPackage(spec, verifyIntegrity = true)
            val espeakValid = !spec.requiresEspeakData ||
                storageManager.verifyInstalledPackage(ModelCatalog.espeakNgDataSpec, verifyIntegrity = true)
            val compat = MemoryDiagnostics.evaluateModelCompatibility(spec, hw)

            if (rec?.status == ModelInstallStatus.INSTALLED.name) {
                if (!cryptoValid || !espeakValid) {
                    modelRecordDao.upsert(
                        rec.copy(
                            status = ModelInstallStatus.CORRUPTED.name,
                            verifiedSha256 = false,
                            verifiedLoadTest = false,
                            lastError = "فشل التحقق التشفيري (SHA-256) لملفات النموذج أو بيانات eSpeak. أعد التنزيل."
                        )
                    )
                } else if (!compat.isSupported) {
                    modelRecordDao.upsert(
                        rec.copy(
                            status = ModelInstallStatus.FAILED.name,
                            verifiedLoadTest = false,
                            lastError = compat.detailsAr ?: compat.summaryAr
                        )
                    )
                } else if (!rec.verifiedLoadTest) {
                    val loadRes = ttsEngineManager.verifyModelLoadable(spec, modelDir)
                    if (loadRes.isSuccess) {
                        modelRecordDao.upsert(
                            rec.copy(
                                status = ModelInstallStatus.INSTALLED.name,
                                verifiedSha256 = true,
                                verifiedLoadTest = true,
                                lastError = null
                            )
                        )
                    } else {
                        modelRecordDao.upsert(
                            rec.copy(
                                status = ModelInstallStatus.FAILED.name,
                                verifiedLoadTest = false,
                                lastError = loadRes.exceptionOrNull()?.message ?: "فشل اختبار تحميل النموذج."
                            )
                        )
                    }
                }
            } else if (rec == null && modelDir.exists() && !modelDir.listFiles().isNullOrEmpty()) {
                if (!cryptoValid || !espeakValid) {
                    modelRecordDao.upsert(
                        ModelRecordEntity(
                            modelId = spec.id,
                            status = ModelInstallStatus.CORRUPTED.name,
                            downloadedBytes = 0L,
                            totalBytes = spec.expectedSizeBytes,
                            installedDirPath = modelDir.absolutePath,
                            verifiedSha256 = false,
                            verifiedLoadTest = false,
                            lastError = "ملفات غير موثّقة أو معدّلة بدون سجل SHA-256 صالح."
                        )
                    )
                } else if (!compat.isSupported) {
                    modelRecordDao.upsert(
                        ModelRecordEntity(
                            modelId = spec.id,
                            status = ModelInstallStatus.FAILED.name,
                            downloadedBytes = spec.expectedSizeBytes,
                            totalBytes = spec.expectedSizeBytes,
                            installedDirPath = modelDir.absolutePath,
                            verifiedSha256 = true,
                            verifiedLoadTest = false,
                            lastError = compat.detailsAr ?: compat.summaryAr
                        )
                    )
                } else {
                    val loadRes = ttsEngineManager.verifyModelLoadable(spec, modelDir)
                    modelRecordDao.upsert(
                        ModelRecordEntity(
                            modelId = spec.id,
                            status = if (loadRes.isSuccess) ModelInstallStatus.INSTALLED.name else ModelInstallStatus.FAILED.name,
                            downloadedBytes = spec.expectedSizeBytes,
                            totalBytes = spec.expectedSizeBytes,
                            installedDirPath = modelDir.absolutePath,
                            verifiedSha256 = true,
                            verifiedLoadTest = loadRes.isSuccess,
                            lastError = loadRes.exceptionOrNull()?.message
                        )
                    )
                }
            } else if (rec?.status == ModelInstallStatus.DOWNLOADING.name ||
                rec?.status == ModelInstallStatus.VERIFYING.name
            ) {
                modelRecordDao.upsert(
                    rec.copy(
                        status = ModelInstallStatus.PAUSED.name,
                        downloadedBytes = storageManager.getTempPartFile(spec.id).length()
                    )
                )
            }
        }
    }

    fun startOrResumeDownload(modelId: String) {
        val data = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ID, modelId)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(data)
            .addTag("download_$modelId")
            .build()
        workManager.enqueueUniqueWork(
            "model_dl_$modelId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun pauseDownload(modelId: String) {
        workManager.cancelUniqueWork("model_dl_$modelId")
    }

    suspend fun cancelAndDiscardDownload(modelId: String) = withContext(Dispatchers.IO) {
        runCatching { workManager.cancelUniqueWork("model_dl_$modelId").result.get() }
        storageManager.getTempPartFile(modelId).delete()
        modelRecordDao.delete(modelId)
    }

    suspend fun deleteInstalledModel(modelId: String, activeModelId: String): Result<Unit> {
        if (modelId == activeModelId && ttsEngineManager.runtimeState.value.loadedModelId == modelId) {
            return Result.failure(IllegalStateException("لا يمكن حذف نموذج قيد الاستخدام حالياً. أوقف المحرك أو بدّل النموذج أولاً."))
        }
        if (modelId == activeModelId) {
            return Result.failure(IllegalStateException("لا يمكن حذف النموذج المحدد كنشط. اختر نموذجاً آخر أولاً."))
        }
        workManager.cancelUniqueWork("model_dl_$modelId")
        storageManager.deleteModelFiles(modelId)
        modelRecordDao.delete(modelId)
        return Result.success(Unit)
    }

    fun getStorageBreakdown(): StorageBreakdown = storageManager.getStorageBreakdown()

    fun clearAudioCache() = storageManager.clearAudioCache()
}
