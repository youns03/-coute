package com.example.data.importengine

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class CharsetSelection(
    val charset: Charset,
    val bomSkipBytes: Int,
    val label: String
)

data class DecodedTextResult(
    val text: String,
    val charsetLabel: String
)

/**
 * Deterministic multi-encoding detector supporting UTF-8, UTF-16 (LE/BE with or without BOM),
 * UTF-32, Windows-1256 (Arabic legacy files), and Windows-1252 / ISO-8859-1 / ISO-8859-15 (French/English).
 */
object CharsetDetection {

    private val WINDOWS_1256: Charset = Charset.forName("windows-1256")
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun detectFromSample(sample: ByteArray, length: Int = sample.size): CharsetSelection {
        val len = length.coerceAtMost(sample.size).coerceAtLeast(0)
        if (len >= 4) {
            if (sample[0] == 0x00.toByte() && sample[1] == 0x00.toByte() &&
                sample[2] == 0xFE.toByte() && sample[3] == 0xFF.toByte()
            ) {
                return CharsetSelection(Charset.forName("UTF-32BE"), 4, "UTF-32BE")
            }
            if (sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() &&
                sample[2] == 0x00.toByte() && sample[3] == 0x00.toByte()
            ) {
                return CharsetSelection(Charset.forName("UTF-32LE"), 4, "UTF-32LE")
            }
        }
        if (len >= 3 &&
            sample[0] == 0xEF.toByte() &&
            sample[1] == 0xBB.toByte() &&
            sample[2] == 0xBF.toByte()
        ) {
            return CharsetSelection(Charsets.UTF_8, 3, "UTF-8")
        }
        if (len >= 2) {
            if (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
                return CharsetSelection(Charsets.UTF_16BE, 2, "UTF-16BE")
            }
            if (sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
                return CharsetSelection(Charsets.UTF_16LE, 2, "UTF-16LE")
            }
        }

        // Heuristic null-byte cadence check for BOM-less UTF-16LE / UTF-16BE
        if (len >= 8) {
            var evenNulls = 0
            var oddNulls = 0
            val pairs = (len / 2).coerceAtMost(2048)
            for (i in 0 until pairs) {
                if (sample[i * 2] == 0.toByte()) evenNulls++
                if (sample[i * 2 + 1] == 0.toByte()) oddNulls++
            }
            if (oddNulls > pairs * 0.35 && evenNulls < pairs * 0.08) {
                return CharsetSelection(Charsets.UTF_16LE, 0, "UTF-16LE")
            }
            if (evenNulls > pairs * 0.35 && oddNulls < pairs * 0.08) {
                return CharsetSelection(Charsets.UTF_16BE, 0, "UTF-16BE")
            }
        }

        if (isValidUtf8Sample(sample, len)) {
            return CharsetSelection(Charsets.UTF_8, 0, "UTF-8")
        }

        val chosen8Bit = chooseSingleByteCharset(sample, len)
        return CharsetSelection(chosen8Bit.first, 0, chosen8Bit.second)
    }

    fun decodeBytes(bytes: ByteArray): DecodedTextResult {
        if (bytes.isEmpty()) return DecodedTextResult("", "UTF-8")
        val selection = detectFromSample(bytes, bytes.size)
        val text = String(
            bytes,
            selection.bomSkipBytes,
            bytes.size - selection.bomSkipBytes,
            selection.charset
        ).removePrefix("\uFEFF")
        return DecodedTextResult(text = text, charsetLabel = selection.label)
    }

    fun openStreamSelection(file: File): Pair<CharsetSelection, BufferedInputStream> {
        val peekBuf = ByteArray(64 * 1024)
        val peekLen = file.inputStream().use { fis ->
            var readTotal = 0
            while (readTotal < peekBuf.size) {
                val n = fis.read(peekBuf, readTotal, peekBuf.size - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            readTotal
        }
        val selection = detectFromSample(peekBuf, peekLen)
        val bis = BufferedInputStream(file.inputStream(), 64 * 1024)
        if (selection.bomSkipBytes > 0) {
            var remaining = selection.bomSkipBytes.toLong()
            while (remaining > 0) {
                val skipped = bis.skip(remaining)
                if (skipped <= 0) {
                    if (bis.read() == -1) break
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
        return selection to bis
    }

    fun decodeBoundedStream(input: InputStream, maxBytes: Long): DecodedTextResult {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                error("حجم الملف النصي يتجاوز الحد الأقصى المسموح للمعالجة.")
            }
            baos.write(buf, 0, n)
        }
        return decodeBytes(baos.toByteArray())
    }

    private fun isValidUtf8Sample(bytes: ByteArray, len: Int): Boolean {
        val effectiveLen = trimIncompleteTrailingUtf8Sequence(bytes, len)
        if (effectiveLen <= 0) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes, 0, effectiveLen))
            true
        }.getOrDefault(false)
    }

    private fun trimIncompleteTrailingUtf8Sequence(bytes: ByteArray, len: Int): Int {
        if (len < 4) return len
        // If sample cut off inside a multi-byte UTF-8 char at buffer edge (len == 64KB), exclude incomplete tail
        var idx = len - 1
        var continuations = 0
        while (idx >= len - 4 && idx >= 0 && (bytes[idx].toInt() and 0xC0) == 0x80) {
            continuations++
            idx--
        }
        if (idx >= 0) {
            val lead = bytes[idx].toInt() and 0xFF
            val expectedTotal = when {
                (lead and 0x80) == 0 -> 1
                (lead and 0xE0) == 0xC0 -> 2
                (lead and 0xF0) == 0xE0 -> 3
                (lead and 0xF8) == 0xF0 -> 4
                else -> 1
            }
            val actualPresent = continuations + 1
            if (actualPresent < expectedTotal) {
                return idx
            }
        }
        return len
    }

    private fun chooseSingleByteCharset(bytes: ByteArray, len: Int): Pair<Charset, String> {
        var cp1256Score = 0
        var cp1252Score = 0
        var highBytes = 0

        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) continue
            highBytes++

            val prevAsciiLetter = i > 0 && isAsciiLetter(bytes[i - 1].toInt() and 0xFF)
            val nextAsciiLetter = i + 1 < len && isAsciiLetter(bytes[i + 1].toInt() and 0xFF)
            val prevHigh = i > 0 && (bytes[i - 1].toInt() and 0xFF) >= 0xC0
            val nextHigh = i + 1 < len && (bytes[i + 1].toInt() and 0xFF) >= 0xC0

            // Windows-1256 Arabic letters & Arabic punctuation
            val isCp1256Letter = b in 0xC1..0xDA || b in 0xE1..0xFA
            val isCp1256Specific = b in setOf(0x81, 0x8D, 0x8E, 0x90, 0x98, 0xA1, 0xBA, 0xBF, 0xDC, 0xDD, 0xDF, 0xE1, 0xE3, 0xE4, 0xE5, 0xE6, 0xED)
            // Common French / Western European Windows-1252 chars: é è ê ë à â ç î ï ô ù û ü œ € ’ « »
            val isCp1252French = b in setOf(
                0x80, 0x8C, 0x92, 0x93, 0x94, 0x9C, 0xAB, 0xBB,
                0xC0, 0xC7, 0xC8, 0xC9, 0xCA, 0xCE, 0xD4,
                0xE0, 0xE2, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEE, 0xEF, 0xF4, 0xF9, 0xFB, 0xFC
            )

            if (isCp1256Letter) {
                cp1256Score += 2
                // Arabic runs are contiguous high-byte letters rather than isolated inside ASCII words
                if (prevHigh || nextHigh) cp1256Score += 3
            }
            if (isCp1256Specific && !prevAsciiLetter && !nextAsciiLetter) {
                cp1256Score += 2
            }

            if (isCp1252French) {
                cp1252Score += 2
                // French accented chars frequently sit immediately next to ASCII Latin letters (e.g., "l'été", "café", "français")
                if (prevAsciiLetter || nextAsciiLetter) cp1252Score += 5
            }
            // Windows-1256 lacks 0x8D..0x8E? No, 0xE7/0xE9 overlap (`ع`/`é`, `ح`/`è`),
            // so adjacency to ASCII letters is the key signal! If adjacent to ASCII letter -> French, not Arabic!
            if (prevAsciiLetter && nextAsciiLetter) {
                cp1252Score += 4
                cp1256Score -= 2
            }
        }

        if (highBytes == 0) return Charsets.UTF_8 to "UTF-8"
        return if (cp1256Score > cp1252Score) {
            WINDOWS_1256 to "Windows-1256"
        } else {
            WINDOWS_1252 to "Windows-1252"
        }
    }

    private fun isAsciiLetter(b: Int): Boolean =
        (b in 'a'.code..'z'.code) || (b in 'A'.code..'Z'.code)
}
