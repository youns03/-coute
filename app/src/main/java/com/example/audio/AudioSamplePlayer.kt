package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.contracts.AudioSamplePlayerGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSamplePlayer : AudioSamplePlayerGateway {
    override suspend fun playWavFile(path: String) = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            error("ملف العينة الصوتية غير موجود.")
        }
        val activeContext = currentCoroutineContext()
        FileInputStream(file).use { fis ->
            val headerBytes = ByteArray(44)
            var headerRead = 0
            while (headerRead < 44) {
                val r = fis.read(headerBytes, headerRead, 44 - headerRead)
                if (r <= 0) break
                headerRead += r
            }
            if (headerRead < 44) error("ترويسة ملف WAV غير مكتملة.")
            val headerStr = String(headerBytes, 0, 4, Charsets.US_ASCII)
            val waveStr = String(headerBytes, 8, 4, Charsets.US_ASCII)
            if (headerStr != "RIFF" || waveStr != "WAVE") {
                error("تنسيق WAV غير صالح.")
            }
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val audioFormat = headerBuf.getShort(20).toInt()
            val channels = headerBuf.getShort(22).toInt()
            val sampleRate = headerBuf.getInt(24)
            val bitsPerSample = headerBuf.getShort(34).toInt()
            if (audioFormat != 1 || channels !in 1..2 || bitsPerSample != 16 || sampleRate !in 8000..96000) {
                error("مواصفات PCM غير مدعومة ($sampleRate Hz / $channels ch / ${bitsPerSample}bit).")
            }
            val channelMask = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    error("تعذر تهيئة مخرج الصوت للعينة.")
                }
                track.play()
                val chunk = ByteArray(16 * 1024)
                while (activeContext.isActive) {
                    val read = fis.read(chunk)
                    if (read <= 0) break
                    var off = 0
                    while (off < read && activeContext.isActive) {
                        val written = track.write(chunk, off, read - off, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        off += written
                    }
                }
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            } finally {
                runCatching { track.release() }
            }
        }
    }
}
