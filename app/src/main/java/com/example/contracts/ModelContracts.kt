package com.example.contracts

import com.example.domain.LicenseRecord
import com.example.domain.ModelInstallRecord
import com.example.domain.ModelRole
import com.example.domain.ModelSpec
import com.example.domain.ModelUiEntry
import com.example.domain.StorageBreakdown
import kotlinx.coroutines.flow.Flow
import java.io.File

data class AsrResolvedFiles(
    val tokensFile: File,
    val ctcModelFile: File? = null,
    val encoderFile: File? = null,
    val decoderFile: File? = null
)

interface ModelCatalogProvider {
    val espeakNgDataSpec: ModelSpec
    val ttsModels: List<ModelSpec>
    val asrModels: List<ModelSpec>
    val allModels: List<ModelSpec>
    val licenseLedger: List<LicenseRecord>
    fun findById(id: String): ModelSpec?
}

interface ModelLoadVerifier {
    fun supportsRole(role: ModelRole): Boolean
    suspend fun verifyModelLoadable(spec: ModelSpec, extractedDir: File): Result<Unit>
}

interface ModelRecordStore {
    fun observeAll(): Flow<List<ModelInstallRecord>>
    suspend fun getRecord(modelId: String): ModelInstallRecord?
    suspend fun upsert(record: ModelInstallRecord)
    suspend fun delete(modelId: String)
}

interface ModelStorageGateway {
    val permanentModelsRoot: File
    val tempDownloadsRoot: File
    val audioCacheRoot: File
    val savedSamplesRoot: File

    suspend fun <T> withSharedEspeakInstallLock(block: suspend () -> T): T
    fun getInstalledModelDir(modelId: String): File
    fun getTempPartFile(modelId: String): File
    fun getSharedEspeakDir(): File
    fun hasNetworkConnection(): Boolean
    fun getFreeInternalBytes(): Long
    fun requiredPeakBytes(
        spec: ModelSpec,
        includeEspeakDependency: Boolean,
        alreadyDownloadedBytes: Long = 0L
    ): Long

    fun hasEnoughDiskSpace(spec: ModelSpec, includeEspeakDependency: Boolean = false): Boolean
    fun hasEnoughDiskSpace(requiredBytes: Long): Boolean
    fun computeSha256(file: File): String
    suspend fun installVerifiedPackage(
        spec: ModelSpec,
        downloadedFile: File,
        deferCommitConfirmation: Boolean = false
    ): Result<File>

    fun confirmInstalledPackage(spec: ModelSpec): Result<Unit>
    fun rollbackInstalledPackage(modelId: String): Boolean
    fun recoverPendingInstalls()
    fun verifyInstalledPackage(spec: ModelSpec, verifyIntegrity: Boolean = false): Boolean
    fun resolveKokoroModelFile(modelDir: File): File?
    fun resolvePiperPaths(modelDir: File): Triple<File, File, File>?
    fun resolveAsrPaths(modelDir: File): AsrResolvedFiles?
    fun deleteModelFiles(modelId: String): Boolean
    fun clearAudioCache()
    fun getStorageBreakdown(): StorageBreakdown
}

interface ModelRepositoryGateway {
    fun observeModels(activeModelIdFlow: Flow<String>): Flow<List<ModelUiEntry>>
    fun observeAllModels(
        activeTtsIdFlow: Flow<String>,
        activeAsrIdFlow: Flow<String>
    ): Flow<List<ModelUiEntry>>

    suspend fun reconcileStorageOnStartup()
    fun startOrResumeDownload(modelId: String)
    fun pauseDownload(modelId: String)
    suspend fun cancelAndDiscardDownload(modelId: String)
    suspend fun deleteInstalledModel(modelId: String, activeModelId: String): Result<Unit>
    fun getStorageBreakdown(): StorageBreakdown
    fun clearAudioCache()
}
