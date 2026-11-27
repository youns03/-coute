package com.example.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DecodedMonoAudio(
    val samples16k: FloatArray,
    val sampleRateHz: Int = 16_000,
    val originalSampleRateHz: Int,
    val originalChannels: Int,
    val durationMs: Long
)

/**
 * Decodes WAV files directly and compressed audio containers (MP3, M4A, AAC, OGG, FLAC)
 * via Android MediaExtractor + MediaCodec into mono 16kHz Float32 waveforms suitable
 * for Sherpa-ONNX offline speech recognition.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000
    private const val MAX_AUDIO_DURATION_MS = 45L * 60L * 1000L // 45 minutes safety cap

    fun decodeFileToMono16k(file: File): DecodedMonoAudio {
        require(file.exists() && file.isFile && file.length() > 0L) {
            "الملف الصوتي غير موجود أو فارغ."
        }
        val wavAttempt = runCatching { tryDecodeWav(file) }.getOrNull()
        if (wavAttempt != null) return wavAttempt
        return decodeWithMediaCodec(file)
    }

    internal fun tryDecodeWav(file: File): DecodedMonoAudio? {
        if (file.length() < 44L) return null
        FileInputStream(file).use { fis ->
            val header = ByteArray(12)
            if (fis.read(header) != 12) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return null
            }
        }

        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)

        var audioFormat = 1
        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4)
            buf.get(chunkIdBytes)
            val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
            val chunkSize = buf.int.coerceAtLeast(0)
            val chunkPos = buf.position()
            if (chunkId == "fmt " && chunkSize >= 16 && buf.remaining() >= 16) {
                audioFormat = buf.short.toInt() and 0xFFFF
                channels = (buf.short.toInt() and 0xFFFF).coerceAtLeast(1)
                sampleRate = buf.int.coerceAtLeast(8000)
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = buf.short.toInt() and 0xFFFF
            } else if (chunkId == "data") {
                dataOffset = chunkPos
                dataSize = minOf(chunkSize, bytes.size - chunkPos)
                break
            }
            val nextPos = chunkPos + chunkSize + (chunkSize and 1)
            if (nextPos > bytes.size || nextPos <= chunkPos) break
            buf.position(nextPos)
        }

        if (dataOffset < 0 || dataSize <= 0) {
            error("ملف WAV لا يحتوي على بيانات صوتية صالحة.")
        }

        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        val totalFrames = dataSize / (bytesPerSample * channels)
        val durationMs = ((totalFrames.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(1L)
        if (durationMs > MAX_AUDIO_DURATION_MS) {
            error("مدة الملف الصوتي (${durationMs / 60_000L} دقيقة) تتجاوز الحد الأقصى للمعالجة المحلية (45 دقيقة).")
        }

        val mono = FloatArray(totalFrames)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until channels) {
                val sample = when {
                    audioFormat == 3 && bitsPerSample == 32 && dataBuf.remaining() >= 4 ->
                        dataBuf.float.coerceIn(-1f, 1f)
                    audioFormat == 1 && bitsPerSample == 16 && dataBuf.remaining() >= 2 ->
                        (dataBuf.short.toFloat() / 32768f).coerceIn(-1f, 1f)
                    audioFormat == 1 && bitsPerSample == 8 && dataBuf.remaining() >= 1 ->
                        (((dataBuf.get().toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
                    else -> {
                        if (dataBuf.remaining() >= bytesPerSample) {
                            dataBuf.position(dataBuf.position() + bytesPerSample)
                        }
                        0f
                    }
                }
                sum += sample
            }
            mono[frame] = sum / channels.toFloat()
        }

        val resampled = resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE)
        return DecodedMonoAudio(
            samples16k = resampled,
            sampleRateHz = TARGET_SAMPLE_RATE,
            originalSampleRateHz = sampleRate,
            originalChannels = channels,
            durationMs = durationMs
        )
    }

    private fun decodeWithMediaCodec(file: File): DecodedMonoAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var selectedFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    selectedFormat = fmt
                    break
                }
            }
            val trackFormat = selectedFormat ?: error("لم يتم العثور على مسار صوتي داخل هذا الملف.")
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: error("صيغة الصوت غير معروفة.")

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            if (durationUs > MAX_AUDIO_DURATION_MS * 1000L) {
                error("مدة الملف الصوتي تتجاوز الحد الأقصى المسموح للمعالجة (45 دقيقة).")
            }

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_SAMPLE_RATE
            var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

            val pcmOut = ByteArrayOutputStream(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                            outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val rawBytes = pcmOut.toByteArray()
            if (rawBytes.isEmpty()) {
                error("تعذر استخراج بيانات صوتية من هذا الملف.")
            }

            val mono = rawBytesToMonoFloat(rawBytes, channels, pcmEncoding)
            val actualDurationMs = ((mono.size.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(1L)
            val resampled = resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE)

            return DecodedMonoAudio(
                samples16k = resampled,
                sampleRateHz = TARGET_SAMPLE_RATE,
                originalSampleRateHz = sampleRate,
                originalChannels = channels,
                durationMs = if (durationUs > 0L) (durationUs / 1000L) else actualDurationMs
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    internal fun rawBytesToMonoFloat(
        rawBytes: ByteArray,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val chCount = channels.coerceAtLeast(1)
        val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        return if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            val frames = rawBytes.size / (4 * chCount)
            FloatArray(frames) {
                var sum = 0f
                for (c in 0 until chCount) sum += bb.float.coerceIn(-1f, 1f)
                sum / chCount.toFloat()
            }
        } else {
            val frames = rawBytes.size / (2 * chCount)
            FloatArray(frames) {
                var sum = 0f
                for (c in 0 until chCount) sum += (bb.short.toFloat() / 32768f).coerceIn(-1f, 1f)
                sum / chCount.toFloat()
            }
        }
    }

    internal fun resampleLinear(
        input: FloatArray,
        fromSampleRate: Int,
        toSampleRate: Int
    ): FloatArray {
        if (input.isEmpty() || fromSampleRate <= 0 || fromSampleRate == toSampleRate) {
            return input
        }
        val ratio = fromSampleRate.toDouble() / toSampleRate.toDouble()
        val targetLength = (input.size.toDouble() / ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(targetLength)
        for (i in 0 until targetLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceAtMost(input.lastIndex)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }
}
