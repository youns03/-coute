package com.example.audio

import com.example.domain.WordTimestampRange
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class CachedAudioPayload(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>
)

/**
 * Collision-resistant SHA-256 content-addressed disk cache for synthesized PCM sentences,
 * with striped per-key synchronization, atomic temp-file writes, strict header/EOF validation
 * prior to allocating float arrays, and bounded disk usage.
 */
object AudioChunkCache {

    internal const val MAGIC_HEADER = 0x45435432 // "ECT2"
    internal const val MIN_SAMPLE_RATE = 8_000
    internal const val MAX_SAMPLE_RATE = 96_000
    internal const val MAX_CACHED_SAMPLES = 2_400_000 // ~100s at 24kHz
    internal const val MAX_CACHED_WORDS = 2_048
    internal const val MAX_CACHE_FILE_BYTES = 16L * 1024L * 1024L
    internal const val HEADER_BYTES_WITH_WT_COUNT = 16L // magic(4) + sr(4) + count(4) + wtCount(4)
    private const val STALE_TMP_AGE_MS = 5L * 60L * 1000L

    private val tmpFileSequence = AtomicLong(0L)
    private val stripedLocks = Array(16) { Any() }

    private fun lockFor(file: File): Any = stripedLocks[(file.name.hashCode() and 0x7FFFFFFF) % stripedLocks.size]

    fun buildCacheKey(
        modelId: String,
        languageCode: String,
        voiceId: String,
        speed: Float,
        spokenText: String
    ): String {
        val raw = "v2|$modelId|${languageCode.lowercase(Locale.US)}|$voiceId|${"%.3f".format(Locale.US, speed)}|$spokenText"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cacheFileFor(cacheRoot: File, key: String): File = File(cacheRoot, "$key.pcmbin")

    fun sanitizeSamples(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var needsCopy = false
        for (s in samples) {
            if (!s.isFinite() || s < -1.0f || s > 1.0f) {
                needsCopy = true
                break
            }
        }
        if (!needsCopy) return samples
        return FloatArray(samples.size) { i ->
            val v = samples[i]
            if (v.isFinite()) v.coerceIn(-1.0f, 1.0f) else 0.0f
        }
    }

    private fun sanitizeTimestamps(timestamps: List<WordTimestampRange>): List<WordTimestampRange> {
        if (timestamps.isEmpty()) return emptyList()
        return timestamps.asSequence()
            .filter { wt ->
                wt.word.isNotBlank() &&
                    wt.word.length <= 256 &&
                    wt.startChar >= 0 &&
                    wt.endChar > wt.startChar &&
                    wt.startSec.isFinite() &&
                    wt.endSec.isFinite() &&
                    wt.startSec >= 0f &&
                    wt.endSec >= wt.startSec
            }
            .take(MAX_CACHED_WORDS)
            .toList()
    }

    fun write(targetFile: File, payload: CachedAudioPayload): Boolean {
        if (payload.samples.isEmpty() ||
            payload.samples.size > MAX_CACHED_SAMPLES ||
            payload.sampleRateHz !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE
        ) {
            return false
        }
        val cleanSamples = sanitizeSamples(payload.samples)
        val cleanTimestamps = sanitizeTimestamps(payload.wordTimestamps)
        val tmpId = tmpFileSequence.incrementAndGet()
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.$tmpId.tmp")

        val wroteTmp = runCatching {
            targetFile.parentFile?.mkdirs()
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 32 * 1024)).use { out ->
                out.writeInt(MAGIC_HEADER)
                out.writeInt(payload.sampleRateHz)
                out.writeInt(cleanSamples.size)
                for (s in cleanSamples) {
                    out.writeFloat(s)
                }
                out.writeInt(cleanTimestamps.size)
                for (wt in cleanTimestamps) {
                    out.writeUTF(wt.word.take(128))
                    out.writeInt(wt.startChar)
                    out.writeInt(wt.endChar)
                    out.writeFloat(wt.startSec)
                    out.writeFloat(wt.endSec)
                }
            }
            true
        }.getOrElse {
            tmpFile.delete()
            false
        }

        if (!wroteTmp) return false

        return synchronized(lockFor(targetFile)) {
            runCatching {
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                true
            }.getOrElse {
                tmpFile.delete()
                false
            }
        }
    }

    fun readValidated(file: File): CachedAudioPayload? {
        return synchronized(lockFor(file)) {
            if (!file.exists() || !file.isFile) return@synchronized null
            val fileLen = file.length()
            if (fileLen !in HEADER_BYTES_WITH_WT_COUNT..MAX_CACHE_FILE_BYTES) {
                file.delete()
                return@synchronized null
            }

            val parsed = try {
                DataInputStream(BufferedInputStream(FileInputStream(file), 32 * 1024)).use { inp ->
                    val magic = inp.readInt()
                    if (magic != MAGIC_HEADER) {
                        error("Invalid audio cache magic header")
                    }
                    val sr = inp.readInt()
                    if (sr !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
                        error("Invalid cached sample rate: $sr")
                    }
                    val count = inp.readInt()
                    if (count !in 1..MAX_CACHED_SAMPLES) {
                        error("Invalid cached sample count: $count")
                    }
                    val requiredSampleBytes = count.toLong() * 4L
                    if (HEADER_BYTES_WITH_WT_COUNT + requiredSampleBytes > fileLen) {
                        error("Truncated cache payload for $count samples")
                    }
                    val samples = FloatArray(count) {
                        val v = inp.readFloat()
                        if (v.isFinite()) v.coerceIn(-1.0f, 1.0f) else 0.0f
                    }
                    val wtCount = inp.readInt()
                    if (wtCount !in 0..MAX_CACHED_WORDS) {
                        error("Invalid cached word timestamp count: $wtCount")
                    }
                    val wts = ArrayList<WordTimestampRange>(wtCount)
                    repeat(wtCount) {
                        val w = inp.readUTF()
                        val sc = inp.readInt()
                        val ec = inp.readInt()
                        val ss = inp.readFloat()
                        val es = inp.readFloat()
                        if (w.isNotBlank() &&
                            w.length <= 256 &&
                            sc >= 0 &&
                            ec > sc &&
                            ss.isFinite() &&
                            es.isFinite() &&
                            ss >= 0f &&
                            es >= ss
                        ) {
                            wts.add(WordTimestampRange(w, sc, ec, ss, es))
                        }
                    }
                    CachedAudioPayload(
                        samples = samples,
                        sampleRateHz = sr,
                        wordTimestamps = wts
                    )
                }
            } catch (_: Throwable) {
                null
            }

            if (parsed == null) {
                file.delete()
                return@synchronized null
            }
            file.setLastModified(System.currentTimeMillis())
            parsed
        }
    }

    fun pruneIfOversized(cacheRoot: File, maxBytes: Long = 64L * 1024L * 1024L) {
        val now = System.currentTimeMillis()
        val allEntries = cacheRoot.listFiles()?.toMutableList() ?: return
        val cacheFiles = mutableListOf<File>()
        for (entry in allEntries) {
            if (entry.name.endsWith(".tmp")) {
                if (now - entry.lastModified() > STALE_TMP_AGE_MS) {
                    entry.delete()
                }
            } else if (entry.isFile) {
                cacheFiles.add(entry)
            }
        }
        var total = cacheFiles.sumOf { it.length() }
        if (total <= maxBytes) return
        cacheFiles.sortBy { it.lastModified() }
        for (f in cacheFiles) {
            if (total <= maxBytes * 3 / 4) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}

class DiskAudioCacheGateway(
    private val cacheRootProvider: () -> File
) : com.example.contracts.AudioCacheGateway {

    override fun buildCacheKey(
        modelId: String,
        languageCode: String,
        voiceId: String,
        speed: Float,
        spokenText: String
    ): String = AudioChunkCache.buildCacheKey(modelId, languageCode, voiceId, speed, spokenText)

    override fun readValidated(cacheKey: String): com.example.contracts.CachedAudioEntry? {
        val file = AudioChunkCache.cacheFileFor(cacheRootProvider(), cacheKey)
        val payload = AudioChunkCache.readValidated(file) ?: return null
        return com.example.contracts.CachedAudioEntry(
            samples = payload.samples,
            sampleRateHz = payload.sampleRateHz,
            wordTimestamps = payload.wordTimestamps
        )
    }

    override fun write(cacheKey: String, entry: com.example.contracts.CachedAudioEntry): Boolean {
        val file = AudioChunkCache.cacheFileFor(cacheRootProvider(), cacheKey)
        return AudioChunkCache.write(
            file,
            CachedAudioPayload(
                samples = entry.samples,
                sampleRateHz = entry.sampleRateHz,
                wordTimestamps = entry.wordTimestamps
            )
        )
    }

    override fun pruneIfOversized() {
        AudioChunkCache.pruneIfOversized(cacheRootProvider())
    }
}

