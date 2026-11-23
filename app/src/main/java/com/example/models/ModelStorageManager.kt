package com.example.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.example.domain.ArchiveType
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.StorageBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class ModelStorageManager(private val context: Context) {

    private val installLocks = ConcurrentHashMap<String, Any>()
    private val sharedEspeakInstallMutex = Mutex()

    /**
     * Serializes installation of the shared eSpeak-ng payload across model workers.
     * The lock is suspend-aware so a waiting worker does not block a WorkManager thread.
     */
    suspend fun <T> withSharedEspeakInstallLock(block: suspend () -> T): T {
        sharedEspeakInstallMutex.lock()
        return try {
            block()
        } finally {
            sharedEspeakInstallMutex.unlock()
        }
    }

    val permanentModelsRoot: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    val tempDownloadsRoot: File
        get() = File(context.cacheDir, "model_downloads").apply { mkdirs() }

    val audioCacheRoot: File
        get() = File(context.cacheDir, "audio_cache").apply { mkdirs() }

    val savedSamplesRoot: File
        get() = File(context.filesDir, "benchmark_samples").apply { mkdirs() }

    private fun requireValidModelId(modelId: String) {
        require(modelId.isNotBlank() && modelId != "." && modelId != "..") {
            "معرّف نموذج غير صالح."
        }
        require('/' !in modelId && '\\' !in modelId) {
            "معرّف النموذج لا يجوز أن يحتوي على فاصل مسار."
        }
    }

    fun getInstalledModelDir(modelId: String): File {
        requireValidModelId(modelId)
        return File(permanentModelsRoot, modelId)
    }

    fun getTempPartFile(modelId: String): File {
        requireValidModelId(modelId)
        return File(tempDownloadsRoot, "$modelId.part")
    }

    fun getSharedEspeakDir(): File = File(permanentModelsRoot, "espeak-ng-data")

    fun hasNetworkConnection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun getFreeInternalBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBytes
    }

    fun requiredPeakBytes(
        spec: ModelSpec,
        includeEspeakDependency: Boolean,
        alreadyDownloadedBytes: Long = 0L
    ): Long {
        val depBytes = if (includeEspeakDependency) ModelCatalog.espeakNgDataSpec.expectedSizeBytes * 3L else 0L
        val safetyMargin = 100L * 1024L * 1024L
        val remainingDownload = (spec.expectedSizeBytes - alreadyDownloadedBytes).coerceAtLeast(0L)
        val installStagePeak = when (spec.archiveType) {
            // Even though renameTo avoids copying on same filesystem, reserve full copy fallback peak safely
            ArchiveType.SINGLE_LITERTLM -> spec.expectedSizeBytes
            ArchiveType.TAR_BZ2, ArchiveType.ZIP -> (spec.expectedSizeBytes * 1.6).toLong()
        }
        return remainingDownload + installStagePeak + depBytes + safetyMargin
    }

    fun hasEnoughDiskSpace(spec: ModelSpec, includeEspeakDependency: Boolean = false): Boolean {
        val partBytes = getTempPartFile(spec.id).takeIf { it.exists() }?.length() ?: 0L
        return getFreeInternalBytes() >= requiredPeakBytes(spec, includeEspeakDependency, partBytes)
    }

    fun hasEnoughDiskSpace(requiredBytes: Long): Boolean {
        val needed = (requiredBytes * 1.5).toLong() + 100L * 1024L * 1024L
        return getFreeInternalBytes() >= needed
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun installVerifiedPackage(
        spec: ModelSpec,
        downloadedFile: File,
        deferCommitConfirmation: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        requireValidModelId(spec.id)
        synchronized(installLocks.getOrPut(spec.id) { Any() }) {
            runCatching {
                if (!downloadedFile.exists()) error("الملف المؤقت غير موجود.")
                if (downloadedFile.length() != spec.expectedSizeBytes) {
                    val actualLen = downloadedFile.length()
                    downloadedFile.delete()
                    error("عدم تطابق الحجم: المتوقع ${spec.expectedSizeBytes} بايت، الفعلي ${actualLen} بايت. تم حذف الملف المؤقت.")
                }
                val actualHash = computeSha256(downloadedFile)
                if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
                    downloadedFile.delete()
                    error("فشل فحص SHA-256! البصمة الفعلية: ${actualHash.take(12)}... تم حذف الملف التالف.")
                }

                recoverPendingInstall(spec.id)
                val stagingDir = File(context.cacheDir, "staging_${spec.id}")
                if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
                    error("تعذر تنظيف مجلد التجهيز السابق.")
                }
                if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                    error("تعذر إنشاء مجلد التجهيز.")
                }

                when (spec.archiveType) {
                    ArchiveType.SINGLE_LITERTLM -> {
                        val outFile = File(stagingDir, spec.downloadUrl.substringAfterLast('/'))
                        downloadedFile.copyTo(outFile, overwrite = true)
                        if (outFile.length() != spec.expectedSizeBytes) {
                            error("فشل نسخ ملف LiteRT-LM إلى مجلد التجهيز.")
                        }
                    }
                    ArchiveType.TAR_BZ2 -> {
                        extractTarBz2(downloadedFile, stagingDir)
                        flattenSingleSubdirIfNeeded(stagingDir)
                    }
                    ArchiveType.ZIP -> {
                        extractZip(downloadedFile, stagingDir)
                        flattenSingleSubdirIfNeeded(stagingDir)
                    }
                }
                verifyExtractedStructure(spec, stagingDir)
                writeIntegrityManifest(spec, stagingDir)

                val finalDir = getInstalledModelDir(spec.id)
                val backupDir = File(context.cacheDir, "backup_${spec.id}")
                val commitMarker = File(context.cacheDir, "commit_${spec.id}")
                if (backupDir.exists() && !backupDir.deleteRecursively()) {
                    error("تعذر تنظيف النسخة الاحتياطية السابقة.")
                }

                // Record the backup phase before moving anything. A process may be killed
                // during the copy fallback, so startup must validate both copies before cleanup.
                commitMarker.writeText("backing_up", Charsets.UTF_8)
                if (finalDir.exists() && !moveOrCopyDirectory(finalDir, backupDir)) {
                    error("تعذر حفظ النسخة السابقة قبل اعتماد النموذج الجديد.")
                }

                try {
                    commitMarker.writeText("prepared", Charsets.UTF_8)
                    if (!moveOrCopyDirectory(stagingDir, finalDir)) {
                        error("تعذر اعتماد مجلد النموذج الجديد.")
                    }
                    if (!verifyInstalledPackage(spec, verifyIntegrity = true)) {
                        error("فشل التحقق من النموذج بعد الاعتماد.")
                    }
                } catch (failure: Throwable) {
                    if (!rollbackPendingInstall(spec.id)) {
                        failure.addSuppressed(IllegalStateException(
                            "تعذرت استعادة النسخة السابقة؛ تم الإبقاء على ملفات backup/marker لمحاولة الاستعادة عند بدء التشغيل."
                        ))
                    }
                    throw failure
                }

                if (!deferCommitConfirmation) {
                    confirmInstalledPackage(spec).getOrThrow()
                }
                if (downloadedFile.exists()) downloadedFile.delete()
                finalDir
            }
        }
    }

    /**
     * Confirms an installed model only after the caller's engine-load check succeeds.
     * Until confirmation, startup recovery rolls the transaction back to the previous model.
     */
    fun confirmInstalledPackage(spec: ModelSpec): Result<Unit> = runCatching {
        val finalDir = getInstalledModelDir(spec.id)
        check(verifyInstalledPackage(spec, verifyIntegrity = true)) {
            "لا يمكن اعتماد نموذج لا يجتاز التحقق التشفيري."
        }
        val marker = File(context.cacheDir, "commit_${spec.id}")
        val backup = File(context.cacheDir, "backup_${spec.id}")
        val staging = File(context.cacheDir, "staging_${spec.id}")
        if (marker.exists()) {
            marker.writeText("committed", Charsets.UTF_8)
            val backupCleaned = !backup.exists() || backup.deleteRecursively()
            staging.deleteRecursively()
            if (backupCleaned) marker.delete()
        }
        check(finalDir.isDirectory)
    }

    /** Roll back a pending install when the native engine cannot load the verified files. */
    fun rollbackInstalledPackage(modelId: String): Boolean = rollbackPendingInstall(modelId)

    /** Repairs interrupted commits before normal database reconciliation. */
    fun recoverPendingInstalls() {
        val modelIds = context.cacheDir.listFiles()
            ?.filter {
                it.name.startsWith("backup_") || it.name.startsWith("staging_") ||
                    it.name.startsWith("commit_")
            }
            ?.map { artifact ->
                val prefix = artifact.name.substringBefore('_')
                artifact.name.removePrefix("${prefix}_")
            }
            ?.distinct()
            .orEmpty()

        modelIds.forEach { modelId ->
            if (modelId.isNotBlank() && modelId != "." && modelId != ".." &&
                '/' !in modelId && '\\' !in modelId
            ) {
                synchronized(installLocks.getOrPut(modelId) { Any() }) {
                    recoverPendingInstall(modelId)
                }
            }
        }
    }

    private fun recoverPendingInstall(modelId: String) {
        val finalDir = getInstalledModelDir(modelId)
        val backupDir = File(context.cacheDir, "backup_${modelId}")
        val stagingDir = File(context.cacheDir, "staging_${modelId}")
        val commitMarker = File(context.cacheDir, "commit_${modelId}")
        val markerState = if (commitMarker.exists()) {
            runCatching { commitMarker.readText(Charsets.UTF_8).trim() }.getOrNull()
        } else {
            null
        }

        when {
            markerState == "committed" -> {
                // Verify the final payload before discarding the last known-good backup.
                if (isValidInstalledPackageForRecovery(modelId, finalDir)) {
                    if (!backupDir.exists() || backupDir.deleteRecursively()) {
                        stagingDir.deleteRecursively()
                        commitMarker.delete()
                    }
                } else {
                    rollbackPendingInstall(modelId)
                }
            }
            markerState == "backing_up" -> {
                recoverInterruptedBackup(modelId, finalDir, backupDir, stagingDir, commitMarker)
            }
            markerState == "restoring" -> {
                // If the backup is already gone, it was restored before interruption.
                val restored = if (backupDir.exists()) {
                    if (finalDir.exists() && !finalDir.deleteRecursively()) {
                        false
                    } else {
                        moveOrCopyDirectory(backupDir, finalDir)
                    }
                } else {
                    finalDir.isDirectory
                }
                if (restored) {
                    stagingDir.deleteRecursively()
                    commitMarker.delete()
                }
            }
            markerState == "discarding" -> {
                // This state is used only when no prior installation existed.
                if (!finalDir.exists() || finalDir.deleteRecursively()) {
                    stagingDir.deleteRecursively()
                    commitMarker.delete()
                }
            }
            commitMarker.exists() -> rollbackPendingInstall(modelId)
            backupDir.exists() -> {
                // Legacy/orphan backup: never discard the only verified copy. Validate both
                // directories before deciding which one is safe to retain.
                recoverInterruptedBackup(modelId, finalDir, backupDir, stagingDir, commitMarker)
            }
            else -> stagingDir.deleteRecursively()
        }
    }

    private fun recoverySpec(modelId: String): ModelSpec? =
        ModelCatalog.findById(modelId)
            ?: ModelCatalog.espeakNgDataSpec.takeIf { it.id == modelId }

    private fun isValidInstalledPackageForRecovery(modelId: String, dir: File): Boolean {
        val spec = recoverySpec(modelId) ?: return false
        if (!dir.isDirectory) return false
        return runCatching {
            // The installed model may legitimately predate the current catalog checksum.
            // Trust only a well-formed manifest and then re-hash every recorded payload file.
            val manifest = File(dir, INTEGRITY_MANIFEST_FILE)
            val lines = manifest.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
            val recordedVersion = lines.firstOrNull { it.startsWith("version=") }
                ?.removePrefix("version=") ?: error("Missing recovery manifest version")
            val recordedPackageSha = lines.firstOrNull { it.startsWith("packageSha256=") }
                ?.removePrefix("packageSha256=") ?: error("Missing recovery package checksum")
            require(recordedPackageSha.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Invalid recovery package checksum"
            }
            verifyInstalledPackageAt(
                spec.copy(version = recordedVersion, sha256 = recordedPackageSha),
                dir,
                verifyIntegrity = true
            )
        }.getOrDefault(false)
    }

    private fun recoverInterruptedBackup(
        modelId: String,
        finalDir: File,
        backupDir: File,
        stagingDir: File,
        commitMarker: File
    ) {
        val finalValid = isValidInstalledPackageForRecovery(modelId, finalDir)
        val backupValid = isValidInstalledPackageForRecovery(modelId, backupDir)

        when {
            finalValid -> {
                // The original installation survived. It is safe to discard a duplicate or
                // incomplete backup only after the final directory passes its integrity check.
                if (!backupDir.exists() || backupDir.deleteRecursively()) {
                    stagingDir.deleteRecursively()
                    commitMarker.delete()
                }
            }
            backupValid -> {
                // The source may have been partially deleted during copy fallback. Restore the
                // independently verified backup rather than trusting directory existence.
                val removedFinal = !finalDir.exists() || finalDir.deleteRecursively()
                if (removedFinal && moveOrCopyDirectory(backupDir, finalDir) &&
                    isValidInstalledPackageForRecovery(modelId, finalDir)
                ) {
                    stagingDir.deleteRecursively()
                    commitMarker.delete()
                }
            }
            !backupDir.exists() -> {
                // No move/copy reached the backup destination; keep any original final data.
                stagingDir.deleteRecursively()
                commitMarker.delete()
            }
            else -> {
                // Unknown model IDs or two invalid copies: preserve all artifacts for recovery.
            }
        }
    }

    private fun rollbackPendingInstall(modelId: String): Boolean {
        val finalDir = getInstalledModelDir(modelId)
        val backupDir = File(context.cacheDir, "backup_${modelId}")
        val stagingDir = File(context.cacheDir, "staging_${modelId}")
        val commitMarker = File(context.cacheDir, "commit_${modelId}")

        // Persist recovery intent before destructive operations. If marker deletion fails
        // after restoration, startup will preserve the restored final directory.
        val recoveryState = if (backupDir.exists()) "restoring" else "discarding"
        if (runCatching { commitMarker.writeText(recoveryState, Charsets.UTF_8) }.isFailure) {
            return false
        }

        val restored = if (backupDir.exists()) {
            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                false
            } else {
                moveOrCopyDirectory(backupDir, finalDir)
            }
        } else {
            !finalDir.exists() || finalDir.deleteRecursively()
        }
        if (restored) {
            stagingDir.deleteRecursively()
            commitMarker.delete()
        }
        return restored
    }

    private fun moveOrCopyDirectory(source: File, target: File): Boolean {
        if (!source.exists()) return false
        if (target.exists() && !target.deleteRecursively()) return false
        if (source.renameTo(target)) return true
        val copied = runCatching {
            source.copyRecursively(target, overwrite = false)
            target.exists() && target.isDirectory
        }.getOrDefault(false)
        if (!copied) {
            // Never leave a partial backup that could be mistaken for a valid prior install.
            target.deleteRecursively()
            return false
        }
        source.deleteRecursively()
        return true
    }

    fun verifyInstalledPackage(spec: ModelSpec, verifyIntegrity: Boolean = false): Boolean =
        verifyInstalledPackageAt(spec, getInstalledModelDir(spec.id), verifyIntegrity)

    private fun verifyInstalledPackageAt(
        spec: ModelSpec,
        dir: File,
        verifyIntegrity: Boolean
    ): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return runCatching {
            verifyExtractedStructure(spec, dir)
            if (verifyIntegrity) verifyIntegrityManifest(spec, dir)
            true
        }.getOrDefault(false)
    }

    /**
     * Build a canonical, root-relative path without java.nio.file.Path (API 26+).
     * The app supports API 24, so integrity checks must remain compatible with API 24/25.
     */
    private fun relativePathUnderRoot(root: File, child: File): String {
        val rootPrefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val childPath = child.canonicalPath
        require(childPath.startsWith(rootPrefix)) { "File escapes model directory: $childPath" }
        return childPath.substring(rootPrefix.length).replace(File.separatorChar, '/')
    }

    private fun writeIntegrityManifest(spec: ModelSpec, dir: File) {
        val manifestFile = File(dir, INTEGRITY_MANIFEST_FILE)
        val files = dir.walkTopDown()
            .filter { it.isFile && it.name != INTEGRITY_MANIFEST_FILE }
            .sortedBy { relativePathUnderRoot(dir, it) }
            .toList()
        if (files.isEmpty()) error("لا توجد ملفات لإنشاء سجل التحقق التشفيري.")

        val content = buildString {
            appendLine("version=${spec.version}")
            appendLine("packageSha256=${spec.sha256.lowercase()}")
            for (file in files) {
                val rel = relativePathUnderRoot(dir, file)
                val sha = computeSha256(file)
                appendLine("file|$rel|${file.length()}|$sha")
            }
        }
        manifestFile.writeText(content, Charsets.UTF_8)
    }

    private fun verifyIntegrityManifest(spec: ModelSpec, dir: File) {
        if (spec.archiveType == ArchiveType.SINGLE_LITERTLM) {
            val litertFile = resolveKokoroModelFile(dir)
                ?: error("ملف .litertlm مفقود.")
            val actualPkgSha = computeSha256(litertFile)
            if (!actualPkgSha.equals(spec.sha256, ignoreCase = true)) {
                error("بصمة SHA-256 لملف .litertlm لا تطابق المرجع الموثّق.")
            }
        }

        val manifestFile = File(dir, INTEGRITY_MANIFEST_FILE)
        if (!manifestFile.exists() || !manifestFile.isFile) {
            error("ملف التحقق $INTEGRITY_MANIFEST_FILE مفقود.")
        }
        val lines = manifestFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val recordedVersion = lines.firstOrNull { it.startsWith("version=") }?.removePrefix("version=")
        val recordedPkgSha = lines.firstOrNull { it.startsWith("packageSha256=") }?.removePrefix("packageSha256=")
        if (recordedVersion != spec.version) {
            error("عدم تطابق إصدار النموذج: المسجل=$recordedVersion والمطلوب=${spec.version}.")
        }
        if (!recordedPkgSha.equals(spec.sha256, ignoreCase = true)) {
            error("بصمة الحزمة في سجل التحقق لا تطابق SHA-256 المعتمد.")
        }

        val fileLines = lines.filter { it.startsWith("file|") }
        if (fileLines.isEmpty()) error("سجل التحقق فارغ من الملفات.")

        val recordedPaths = HashSet<String>(fileLines.size)
        for (line in fileLines) {
            val parts = line.split('|')
            if (parts.size != 4) error("تنسيق تالف في سجل التحقق.")
            val relPath = parts[1]
            val expectedLen = parts[2].toLongOrNull() ?: error("طول غير صالح في سجل التحقق.")
            val expectedSha = parts[3]
            if (relPath.startsWith("/") || relPath.contains('\\') ||
                relPath.split('/').any { it == ".." || it.isEmpty() }) {
                error("مسار غير آمن في سجل التحقق.")
            }
            val target = File(dir, relPath)
            if (relativePathUnderRoot(dir, target) != relPath) {
                error("مسار خارج جذر النموذج في سجل التحقق.")
            }
            if (!target.exists() || !target.isFile) {
                error("ملف مفقود من النموذج: $relPath")
            }
            if (target.length() != expectedLen) {
                error("تغيّر حجم الملف $relPath عن المسجل.")
            }
            val actualSha = computeSha256(target)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                error("تلف أو تعديل محتوى الملف $relPath (SHA-256 غير مطابق).")
            }
            recordedPaths.add(relPath)
        }

        // Reject tampered/unrecorded payload files inside the model directory
        val unexpectedFiles = dir.walkTopDown()
            .filter { it.isFile && it.name != INTEGRITY_MANIFEST_FILE }
            .map { relativePathUnderRoot(dir, it) }
            .filter { it !in recordedPaths }
            .toList()
        if (unexpectedFiles.isNotEmpty()) {
            error("وجود ملفات غير موثّقة داخل مجلد النموذج: ${unexpectedFiles.first()}")
        }
    }

    private fun verifyExtractedStructure(spec: ModelSpec, dir: File) {
        when (spec.engineType) {
            EngineType.KOKORO_LITERT -> {
                val litertFile = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "litertlm" }
                    ?: error("ملف .litertlm مفقود داخل الحزمة.")
                if (litertFile.length() != spec.expectedSizeBytes) {
                    error("ملف .litertlm تالف أو غير مكتمل (${litertFile.length()} بايت).")
                }
            }
            EngineType.PIPER_SHERPA_ONNX -> {
                if (spec.id == ModelCatalog.espeakNgDataSpec.id) {
                    val phontab = File(dir, "phontab")
                    if (!phontab.exists()) error("بيانات eSpeak-ng تالفة (phontab مفقود).")
                    return
                }
                val onnx = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" }
                    ?: error("ملف النموذج .onnx مفقود.")
                val tokens = dir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" }
                    ?: error("ملف tokens.txt مفقود.")
                if (onnx.length() < 1_000_000L || tokens.length() == 0L) {
                    error("ملفات Piper ONNX غير صالحة.")
                }
            }
        }
    }

    companion object {
        const val INTEGRITY_MANIFEST_FILE = ".integrity.sha256"
    }

    fun resolveKokoroModelFile(modelDir: File): File? =
        modelDir.walkTopDown().firstOrNull { it.isFile && it.extension == "litertlm" }

    fun resolvePiperPaths(modelDir: File): Triple<File, File, File>? {
        val onnx = modelDir.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" } ?: return null
        val tokens = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null
        val localEspeak = modelDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        val sharedEspeak = getSharedEspeakDir().takeIf { File(it, "phontab").exists() }
        val espeak = localEspeak ?: sharedEspeak ?: return null
        return Triple(onnx, tokens, espeak)
    }

    fun deleteModelFiles(modelId: String): Boolean =
        synchronized(installLocks.getOrPut(modelId) { Any() }) {
            val dir = getInstalledModelDir(modelId)
            val artifacts = listOf(
                getTempPartFile(modelId),
                File(context.cacheDir, "staging_$modelId"),
                File(context.cacheDir, "backup_$modelId"),
                File(context.cacheDir, "commit_$modelId")
            )
            var success = true
            for (target in artifacts + dir) {
                if (target.exists() && !target.deleteRecursively()) success = false
            }
            success
        }

    fun clearAudioCache() {
        audioCacheRoot.deleteRecursively()
        audioCacheRoot.mkdirs()
    }

    fun getStorageBreakdown(): StorageBreakdown {
        val appInfo = context.applicationInfo
        val apkBytes = runCatching { File(appInfo.sourceDir).length() }.getOrDefault(0L)
        val nativeLibsBytes = runCatching {
            val libDir = File(appInfo.nativeLibraryDir)
            folderSize(libDir)
        }.getOrDefault(0L)

        return StorageBreakdown(
            apkBytes = apkBytes,
            nativeLibsEstimateBytes = nativeLibsBytes,
            installedModelsBytes = folderSize(permanentModelsRoot),
            tempDownloadsBytes = folderSize(tempDownloadsRoot),
            audioCacheBytes = folderSize(audioCacheRoot),
            savedSamplesBytes = folderSize(savedSamplesRoot),
            freeDeviceBytes = getFreeInternalBytes()
        )
    }

    private fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun flattenSingleSubdirIfNeeded(dir: File) {
        val children = dir.listFiles()?.toList().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val sub = children[0]
            sub.listFiles()?.forEach { child ->
                child.renameTo(File(dir, child.name))
            }
            sub.delete()
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis, 64 * 1024).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        val buffer = ByteArray(32 * 1024)
                        while (entry != null) {
                            val outFile = File(destDir, entry.name)
                            val outCanonical = outFile.canonicalPath
                            if (!outCanonical.startsWith(destCanonical)) {
                                throw SecurityException("Blocked unsafe tar path: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        val read = tarIn.read(buffer)
                                        if (read <= 0) break
                                        fos.write(buffer, 0, read)
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun extractZip(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(32 * 1024)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val outCanonical = outFile.canonicalPath
                if (!outCanonical.startsWith(destCanonical)) {
                    throw SecurityException("Blocked unsafe zip path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
