package com.example.models

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.contracts.ModelCatalogProvider
import com.example.contracts.ModelLoadVerifier
import com.example.contracts.ModelRecordStore
import com.example.contracts.ModelRepositoryGateway
import com.example.contracts.ModelStorageGateway
import com.example.contracts.TaskControls
import com.example.contracts.TaskCoordinatorGateway
import com.example.domain.LocalTaskEntry
import com.example.domain.LocalTaskPriority
import com.example.domain.LocalTaskStatus
import com.example.domain.LocalTaskType
import com.example.domain.MemoryDiagnostics
import com.example.domain.ModelInstallRecord
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelRole
import com.example.domain.ModelSpec
import com.example.domain.ModelUiEntry
import com.example.domain.StorageBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

class ModelRepository(
    private val context: Context,
    private val modelRecordStore: ModelRecordStore,
    private val storageManager: ModelStorageGateway,
    private val modelCatalog: ModelCatalogProvider = ModelCatalog,
    private val verifiers: List<ModelLoadVerifier> = emptyList(),
    private val isModelActiveInRuntime: (String) -> Boolean = { false },
    private val taskCoordinator: TaskCoordinatorGateway? = null
) : ModelRepositoryGateway {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun observeModels(activeModelIdFlow: Flow<String>): Flow<List<ModelUiEntry>> =
        observeCatalogModels(
            specs = modelCatalog.ttsModels,
            activeTtsIdFlow = activeModelIdFlow,
            activeAsrIdFlow = flowOf("")
        )

    override fun observeAllModels(
        activeTtsIdFlow: Flow<String>,
        activeAsrIdFlow: Flow<String>
    ): Flow<List<ModelUiEntry>> =
        observeCatalogModels(
            specs = modelCatalog.allModels,
            activeTtsIdFlow = activeTtsIdFlow,
            activeAsrIdFlow = activeAsrIdFlow
        )

    private fun observeCatalogModels(
        specs: List<ModelSpec>,
        activeTtsIdFlow: Flow<String>,
        activeAsrIdFlow: Flow<String>
    ): Flow<List<ModelUiEntry>> {
        return combine(modelRecordStore.observeAll(), activeTtsIdFlow, activeAsrIdFlow) { records, activeTtsId, activeAsrId ->
            val byId = records.associateBy { it.modelId }
            val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
            specs.map { spec ->
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
                val activeId = if (spec.role == ModelRole.ASR) activeAsrId else activeTtsId
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

    private suspend fun verifySpecLoadable(spec: ModelSpec, modelDir: File): Result<Unit> {
        val verifier = verifiers.firstOrNull { it.supportsRole(spec.role) } ?: return Result.success(Unit)
        return verifier.verifyModelLoadable(spec, modelDir)
    }

    /**
     * Reconciles database status against actual files in permanent filesDir after app restart/reboot.
     * Enforces cryptographic SHA-256 manifest checks, hardware compatibility, and genuine engine load verification.
     */
    override suspend fun reconcileStorageOnStartup() {
        storageManager.recoverPendingInstalls()
        val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
        for (spec in modelCatalog.allModels) {
            val rec = modelRecordStore.getRecord(spec.id)
            val modelDir = storageManager.getInstalledModelDir(spec.id)
            val cryptoValid = storageManager.verifyInstalledPackage(spec, verifyIntegrity = true)
            val espeakValid = !spec.requiresEspeakData ||
                storageManager.verifyInstalledPackage(modelCatalog.espeakNgDataSpec, verifyIntegrity = true)
            val compat = MemoryDiagnostics.evaluateModelCompatibility(spec, hw)

            if (rec?.status == ModelInstallStatus.INSTALLED.name) {
                if (!cryptoValid || !espeakValid) {
                    modelRecordStore.upsert(
                        rec.copy(
                            status = ModelInstallStatus.CORRUPTED.name,
                            verifiedSha256 = false,
                            verifiedLoadTest = false,
                            lastError = "فشل التحقق التشفيري (SHA-256) لملفات النموذج أو بيانات eSpeak. أعد التنزيل."
                        )
                    )
                } else if (!compat.isSupported) {
                    modelRecordStore.upsert(
                        rec.copy(
                            status = ModelInstallStatus.FAILED.name,
                            verifiedLoadTest = false,
                            lastError = compat.detailsAr ?: compat.summaryAr
                        )
                    )
                } else if (!rec.verifiedLoadTest) {
                    val loadRes = verifySpecLoadable(spec, modelDir)
                    if (loadRes.isSuccess) {
                        modelRecordStore.upsert(
                            rec.copy(
                                status = ModelInstallStatus.INSTALLED.name,
                                verifiedSha256 = true,
                                verifiedLoadTest = true,
                                lastError = null
                            )
                        )
                    } else {
                        modelRecordStore.upsert(
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
                    modelRecordStore.upsert(
                        ModelInstallRecord(
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
                    modelRecordStore.upsert(
                        ModelInstallRecord(
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
                    val loadRes = verifySpecLoadable(spec, modelDir)
                    modelRecordStore.upsert(
                        ModelInstallRecord(
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
                modelRecordStore.upsert(
                    rec.copy(
                        status = ModelInstallStatus.PAUSED.name,
                        downloadedBytes = storageManager.getTempPartFile(spec.id).length()
                    )
                )
            }
        }
    }

    override fun startOrResumeDownload(modelId: String) {
        val spec = modelCatalog.findById(modelId)
        taskCoordinator?.upsertExternalTask(
            entry = LocalTaskEntry(
                id = "dl_$modelId",
                type = LocalTaskType.MODEL_DOWNLOAD,
                title = spec?.displayName ?: modelId,
                subtitle = "جاري تجهيز التنزيل...",
                progress = 0f,
                status = LocalTaskStatus.QUEUED,
                priority = LocalTaskPriority.BACKGROUND,
                canPause = true,
                canCancel = true
            ),
            controls = TaskControls(
                onPause = { pauseDownload(modelId) },
                onResume = { startOrResumeDownload(modelId) },
                onCancel = { cancelAndDiscardDownload(modelId) },
                onRetry = { startOrResumeDownload(modelId) }
            )
        )
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

    override fun pauseDownload(modelId: String) {
        workManager.cancelUniqueWork("model_dl_$modelId")
        taskCoordinator?.updateTaskStatus("dl_$modelId", LocalTaskStatus.PAUSED, subtitle = "متوقف مؤقتاً")
    }

    override suspend fun cancelAndDiscardDownload(modelId: String) = withContext(Dispatchers.IO) {
        runCatching { workManager.cancelUniqueWork("model_dl_$modelId").result.get() }
        storageManager.getTempPartFile(modelId).delete()
        modelRecordStore.delete(modelId)
        taskCoordinator?.dismissTask("dl_$modelId")
        Unit
    }

    override suspend fun deleteInstalledModel(modelId: String, activeModelId: String): Result<Unit> {
        if (modelId == activeModelId && isModelActiveInRuntime(modelId)) {
            return Result.failure(IllegalStateException("لا يمكن حذف نموذج قيد الاستخدام حالياً. أوقف المحرك أو بدّل النموذج أولاً."))
        }
        if (modelId == activeModelId) {
            return Result.failure(IllegalStateException("لا يمكن حذف النموذج المحدد كنشط. اختر نموذجاً آخر أولاً."))
        }
        workManager.cancelUniqueWork("model_dl_$modelId")
        storageManager.deleteModelFiles(modelId)
        modelRecordStore.delete(modelId)
        return Result.success(Unit)
    }

    override fun getStorageBreakdown(): StorageBreakdown = storageManager.getStorageBreakdown()

    override fun clearAudioCache() = storageManager.clearAudioCache()
}
