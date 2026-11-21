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

data class CachedAudioPayload(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>
)

/**
 * Collision-resistant SHA-256 content-addressed disk cache for synthesized PCM sentences,
 * with strict header & file-length validation prior to allocating float arrays.
 */
object AudioChunkCache {

    internal const val MAGIC_HEADER = 0x45435432 // "ECT2"
    internal const val MIN_SAMPLE_RATE = 8_000
    internal const val MAX_SAMPLE_RATE = 96_000
    internal const val MAX_CACHED_SAMPLES = 2_400_000 // ~100s at 24kHz
    internal const val MAX_CACHED_WORDS = 2_048
    internal const val MAX_CACHE_FILE_BYTES = 16L * 1024L * 1024L
    internal const val HEADER_BYTES_WITH_WT_COUNT = 16L // magic(4) + sr(4) + count(4) + wtCount(4)

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

    fun write(targetFile: File, payload: CachedAudioPayload): Boolean {
        if (payload.samples.isEmpty() ||
            payload.samples.size > MAX_CACHED_SAMPLES ||
            payload.sampleRateHz !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE
        ) {
            return false
        }
        val cleanSamples = sanitizeSamples(payload.samples)
        val cleanTimestamps = payload.wordTimestamps.take(MAX_CACHED_WORDS)
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${Thread.currentThread().id}.tmp")
        return runCatching {
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

    fun readValidated(file: File): CachedAudioPayload? {
        if (!file.exists() || !file.isFile) return null
        val fileLen = file.length()
        if (fileLen !in HEADER_BYTES_WITH_WT_COUNT..MAX_CACHE_FILE_BYTES) {
            file.delete()
            return null
        }

        val parsed = runCatching {
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
                    if (w.length <= 256 && sc >= 0 && ec >= sc && ss.isFinite() && es.isFinite()) {
                        wts.add(WordTimestampRange(w, sc, ec, ss, es))
                    }
                }
                CachedAudioPayload(
                    samples = samples,
                    sampleRateHz = sr,
                    wordTimestamps = wts
                )
            }
        }.getOrNull()

        if (parsed == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return parsed
    }

    fun pruneIfOversized(cacheRoot: File, maxBytes: Long = 64L * 1024L * 1024L) {
        val files = cacheRoot.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= maxBytes * 3 / 4) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
