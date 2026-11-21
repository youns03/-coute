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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class ModelStorageManager(private val context: Context) {

    val permanentModelsRoot: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    val tempDownloadsRoot: File
        get() = File(context.cacheDir, "model_downloads").apply { mkdirs() }

    val audioCacheRoot: File
        get() = File(context.cacheDir, "audio_cache").apply { mkdirs() }

    val savedSamplesRoot: File
        get() = File(context.filesDir, "benchmark_samples").apply { mkdirs() }

    fun getInstalledModelDir(modelId: String): File = File(permanentModelsRoot, modelId)

    fun getTempPartFile(modelId: String): File = File(tempDownloadsRoot, "$modelId.part")

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
        downloadedFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!downloadedFile.exists()) {
                error("الملف المؤقت غير موجود.")
            }
            if (downloadedFile.length() != spec.expectedSizeBytes) {
                val actualLen = downloadedFile.length()
                downloadedFile.delete()
                error("عدم تطابق الحجم: المتوقع ${spec.expectedSizeBytes} بايت، الفعلي $actualLen بايت. تم حذف الملف المؤقت.")
            }
            val actualHash = computeSha256(downloadedFile)
            if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
                downloadedFile.delete()
                error("فشل فحص SHA-256! البصمة الفعلية: ${actualHash.take(12)}... تم حذف الملف التالف.")
            }

            val stagingDir = File(context.cacheDir, "staging_${spec.id}")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            when (spec.archiveType) {
                ArchiveType.SINGLE_LITERTLM -> {
                    val fileName = spec.downloadUrl.substringAfterLast('/')
                    val outFile = File(stagingDir, fileName)
                    if (!downloadedFile.renameTo(outFile)) {
                        downloadedFile.copyTo(outFile, overwrite = true)
                        downloadedFile.delete()
                    }
                    if (outFile.length() != spec.expectedSizeBytes) {
                        outFile.delete()
                        error("فشل نقل ملف LiteRT-LM إلى مجلد التجهيز.")
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

            val finalDir = getInstalledModelDir(spec.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!stagingDir.renameTo(finalDir)) {
                stagingDir.copyRecursively(finalDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            // Remove temp part file only after permanent commit succeeds (no-op if already moved)
            if (downloadedFile.exists()) downloadedFile.delete()
            finalDir
        }
    }

    fun verifyInstalledPackage(spec: ModelSpec): Boolean {
        val dir = getInstalledModelDir(spec.id)
        if (!dir.exists() || !dir.isDirectory) return false
        return runCatching {
            verifyExtractedStructure(spec, dir)
            true
        }.getOrDefault(false)
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

    fun deleteModelFiles(modelId: String): Boolean {
        getTempPartFile(modelId).delete()
        val dir = getInstalledModelDir(modelId)
        return if (dir.exists()) dir.deleteRecursively() else true
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
