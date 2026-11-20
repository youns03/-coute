package com.example.models

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.ModelRecordDao
import com.example.data.ModelRecordEntity
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelSpec
import com.example.domain.StorageBreakdown
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ModelUiEntry(
    val spec: ModelSpec,
    val status: ModelInstallStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val verifiedSha256: Boolean,
    val verifiedLoadTest: Boolean,
    val lastError: String?,
    val isActive: Boolean
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
                ModelUiEntry(
                    spec = spec,
                    status = status,
                    downloadedBytes = partBytes,
                    totalBytes = rec?.totalBytes?.takeIf { it > 0L } ?: spec.expectedSizeBytes,
                    verifiedSha256 = rec?.verifiedSha256 ?: false,
                    verifiedLoadTest = rec?.verifiedLoadTest ?: false,
                    lastError = rec?.lastError,
                    isActive = spec.id == activeId
                )
            }
        }
    }

    /**
     * Reconciles database status against actual files in permanent filesDir after app restart/reboot.
     */
    suspend fun reconcileStorageOnStartup() {
        for (spec in ModelCatalog.ttsModels) {
            val rec = modelRecordDao.getRecord(spec.id)
            val validOnDisk = storageManager.verifyInstalledPackage(spec)
            if (rec?.status == ModelInstallStatus.INSTALLED.name && !validOnDisk) {
                modelRecordDao.upsert(
                    rec.copy(
                        status = ModelInstallStatus.CORRUPTED.name,
                        verifiedLoadTest = false,
                        lastError = "ملفات النموذج مفقودة أو تالفة على القرص."
                    )
                )
            } else if (rec == null && validOnDisk) {
                modelRecordDao.upsert(
                    ModelRecordEntity(
                        modelId = spec.id,
                        status = ModelInstallStatus.INSTALLED.name,
                        downloadedBytes = spec.expectedSizeBytes,
                        totalBytes = spec.expectedSizeBytes,
                        installedDirPath = storageManager.getInstalledModelDir(spec.id).absolutePath,
                        verifiedSha256 = true,
                        verifiedLoadTest = true
                    )
                )
            } else if (rec?.status == ModelInstallStatus.DOWNLOADING.name) {
                // App was killed mid-download; mark paused so user can resume cleanly
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
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun pauseDownload(modelId: String) {
        workManager.cancelUniqueWork("model_dl_$modelId")
    }

    fun cancelAndDiscardDownload(modelId: String) {
        workManager.cancelUniqueWork("model_dl_$modelId")
        storageManager.getTempPartFile(modelId).delete()
        CoroutineScope(Dispatchers.IO).launch {
            modelRecordDao.delete(modelId)
        }
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
