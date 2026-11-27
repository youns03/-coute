package com.example.data.importengine

import java.io.File
import java.util.zip.ZipFile

data class FormatDetectionResult(
    val format: SupportedImportFormat?,
    val rejectionReasonAr: String? = null
)

/**
 * Inspects file header magic bytes, ZIP internal structure, MIME type, and file extension
 * to reliably classify supported formats and reject corrupted/unsupported files cleanly.
 */
object FormatDetector {

    private val LEGACY_OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    fun detectFromMetadata(
        fileName: String,
        mimeType: String
    ): FormatDetectionResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val extFormat = SupportedImportFormat.fromExtension(ext)
        if (extFormat != null) return FormatDetectionResult(extFormat)

        val mimeFormat = SupportedImportFormat.fromMimeType(mimeType)
        if (mimeFormat != null) return FormatDetectionResult(mimeFormat)

        return if (ext.isNotEmpty()) {
            FormatDetectionResult(
                format = null,
                rejectionReasonAr = unsupportedExtensionMessage(ext)
            )
        } else {
            FormatDetectionResult(
                format = null,
                rejectionReasonAr = "صيغة الملف غير معروفة أو غير مدعومة."
            )
        }
    }

    fun detectFromFile(
        file: File,
        fileName: String,
        mimeType: String
    ): FormatDetectionResult {
        if (!file.exists() || file.length() == 0L) {
            return FormatDetectionResult(null, "الملف المحدد فارغ.")
        }

        val header = ByteArray(4096)
        val headerLen = file.inputStream().use { fis ->
            var total = 0
            while (total < header.size) {
                val n = fis.read(header, total, header.size - total)
                if (n <= 0) break
                total += n
            }
            total
        }
        if (headerLen == 0) {
            return FormatDetectionResult(null, "الملف المحدد فارغ.")
        }

        val ext = fileName.substringAfterLast('.', "").lowercase()

        // 1. %PDF- magic
        val headerAscii = String(header, 0, headerLen.coerceAtMost(1024), Charsets.ISO_8859_1)
        if (headerAscii.trimStart().startsWith("%PDF-")) {
            return FormatDetectionResult(SupportedImportFormat.PDF)
        }

        // 2. Legacy binary .doc (OLE2 Compound File)
        if (headerLen >= LEGACY_OLE2_MAGIC.size &&
            LEGACY_OLE2_MAGIC.indices.all { header[it] == LEGACY_OLE2_MAGIC[it] }
        ) {
            return FormatDetectionResult(
                null,
                "ملفات Word القديمة (.doc) الثنائية غير مدعومة — يرجى حفظ الملف بصيغة DOCX أو PDF."
            )
        }

        // 3. Common binary image/audio/video/executable signatures -> reject explicitly
        binarySignatureRejectionReason(header, headerLen, ext)?.let { reason ->
            return FormatDetectionResult(null, reason)
        }

        // 4. ZIP container -> disambiguate EPUB vs DOCX vs generic ZIP
        if (headerLen >= 4 &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        ) {
            val zipKind = inspectZipContainer(file, ext)
            return zipKind
        }

        // 5. Text-based signatures (handle BOM/UTF-16 properly)
        val sampleText = CharsetDetection.decodeBytes(header.copyOf(headerLen)).text
        val trimmedSample = sampleText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')

        if (trimmedSample.startsWith("WEBVTT", ignoreCase = true)) {
            return FormatDetectionResult(SupportedImportFormat.VTT)
        }

        if (ext == "srt" || looksLikeSrt(trimmedSample)) {
            return FormatDetectionResult(SupportedImportFormat.SRT)
        }
        if (ext == "vtt") {
            return FormatDetectionResult(SupportedImportFormat.VTT)
        }

        if (ext in setOf("html", "htm", "xhtml") || looksLikeHtml(trimmedSample)) {
            return FormatDetectionResult(SupportedImportFormat.HTML)
        }
        if (ext in setOf("md", "markdown", "mdown")) {
            return FormatDetectionResult(SupportedImportFormat.MD)
        }
        if (ext in setOf("txt", "text", "log")) {
            return FormatDetectionResult(SupportedImportFormat.TXT)
        }

        // Reject declared binary extensions even if disguised
        if (ext in KNOWN_UNSUPPORTED_EXTENSIONS) {
            return FormatDetectionResult(null, unsupportedExtensionMessage(ext))
        }

        // Extensionless MIME fallback
        val mimeFmt = SupportedImportFormat.fromMimeType(mimeType)
        if (mimeFmt != null) {
            return FormatDetectionResult(mimeFmt)
        }

        // Last check: ensure content is readable text rather than arbitrary binary bytes
        if (looksLikeBinaryNoise(header, headerLen)) {
            return FormatDetectionResult(
                null,
                "تعذر التعرف على الملف كمستند نصي أو كتاب مدعوم."
            )
        }
        return FormatDetectionResult(SupportedImportFormat.TXT)
    }

    private fun inspectZipContainer(file: File, ext: String): FormatDetectionResult {
        return runCatching {
            ZipFile(file).use { zip ->
                var hasOpfOrContainer = false
                var hasWordDoc = false
                val entries = zip.entries()
                var checked = 0
                while (entries.hasMoreElements() && checked < 500) {
                    val name = entries.nextElement().name.lowercase()
                    checked++
                    if (name == "meta-inf/container.xml" || name.endsWith(".opf")) {
                        hasOpfOrContainer = true
                    }
                    if (name == "word/document.xml") {
                        hasWordDoc = true
                    }
                }
                when {
                    hasOpfOrContainer -> FormatDetectionResult(SupportedImportFormat.EPUB)
                    hasWordDoc -> FormatDetectionResult(SupportedImportFormat.DOCX)
                    ext == "epub" -> FormatDetectionResult(
                        null,
                        "ملف EPUB غير صالح أو تالف (لا يحتوي على بنية الكتاب الإلكتروني القياسية)."
                    )
                    ext == "docx" -> FormatDetectionResult(
                        null,
                        "ملف DOCX غير صالح أو تالف (word/document.xml مفقود)."
                    )
                    else -> FormatDetectionResult(
                        null,
                        "ملفات الأرشيف المضغوطة (.zip) غير مدعومة مباشرة — يُرجى استيراد EPUB أو DOCX."
                    )
                }
            }
        }.getOrElse {
            FormatDetectionResult(
                null,
                when (ext) {
                    "epub" -> "ملف EPUB تالف أو محمي ولا يمكن فتحه."
                    "docx" -> "ملف DOCX تالف أو محمي ولا يمكن فتحه."
                    else -> "تعذر قراءة الأرشيف المضغوط — الملف تالف أو غير مدعوم."
                }
            )
        }
    }

    private fun looksLikeSrt(sample: String): Boolean {
        val srtPattern = Regex("""^\s*\d+\s*\R\s*\d{1,2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{3}""")
        return srtPattern.containsMatchIn(sample.take(600))
    }

    private fun looksLikeHtml(sample: String): Boolean {
        val lower = sample.take(600).lowercase()
        return lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            (lower.contains("<body") && lower.contains("</")) ||
            (lower.contains("<head") && lower.contains("<title"))
    }

    private fun looksLikeBinaryNoise(bytes: ByteArray, len: Int): Boolean {
        val selection = CharsetDetection.detectFromSample(bytes, len)
        if (selection.label.startsWith("UTF-16") || selection.label.startsWith("UTF-32")) return false
        var controlCount = 0
        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C) {
                controlCount++
            }
        }
        return controlCount > len * 0.05
    }

    private fun binarySignatureRejectionReason(bytes: ByteArray, len: Int, ext: String): String? {
        if (len >= 4) {
            // PNG
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "ملفات الصور (PNG) غير مدعومة في مستورد المستندات."
            // JPEG
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "ملفات الصور (JPG) غير مدعومة في مستورد المستندات."
            }
            // GIF
            val head4 = String(bytes, 0, 4, Charsets.ISO_8859_1)
            if (head4 == "GIF8" || head4 == "RIFF" || head4 == "ID3\u0003" || head4 == "ID3\u0004" || head4 == "fLaC" || head4 == "OggS") {
                return "الملفات الصوتية أو المصورة غير مدعومة هنا — استخدم خيار «استيراد ملف صوتي» للصوتيات."
            }
            // RAR / 7z / ELF / EXE
            if (head4 == "Rar!" || (bytes[0] == 0x7F.toByte() && head4.endsWith("ELF")) ||
                (bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte())
            ) {
                return unsupportedExtensionMessage(ext.ifBlank { "binary" })
            }
        }
        return null
    }

    private fun unsupportedExtensionMessage(ext: String): String = when (ext.lowercase()) {
        "doc" -> "ملفات Word القديمة (.doc) غير مدعومة — يرجى تحويلها إلى DOCX أو PDF."
        "zip", "rar", "7z" -> "الملفات المضغوطة (.$ext) غير مدعومة مباشرة — استخرج المستند أولاً."
        "mp3", "wav", "m4a", "ogg", "flac", "aac" -> "الملفات الصوتية (.$ext) تُستورد عبر خيار «استيراد ملف صوتي وتفريغه»."
        "png", "jpg", "jpeg", "webp", "gif" -> "ملفات الصور (.$ext) غير مدعومة مباشرة — يمكن استيرادها كملف PDF."
        else -> "صيغة الملف (.$ext) غير مدعومة. الصيغ المدعومة: PDF, EPUB, DOCX, TXT, MD, HTML, SRT, VTT."
    }

    private val KNOWN_UNSUPPORTED_EXTENSIONS = setOf(
        "doc", "ppt", "pptx", "xls", "xlsx", "odt", "rtf",
        "zip", "rar", "7z", "tar", "gz",
        "png", "jpg", "jpeg", "gif", "webp", "bmp",
        "mp3", "wav", "m4a", "ogg", "flac", "aac", "mp4", "mkv", "mov",
        "apk", "exe", "bin", "so", "dll"
    )
}
