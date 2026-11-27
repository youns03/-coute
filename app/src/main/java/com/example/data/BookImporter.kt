package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.importengine.DocxParser
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.EpubParser
import com.example.data.importengine.ExtractionDiagnostics
import com.example.data.importengine.FileInspectionPreview
import com.example.data.importengine.FormatDetector
import com.example.data.importengine.HtmlDocumentParser
import com.example.data.importengine.ImportProgressEvent
import com.example.data.importengine.LanguageDetector
import com.example.data.importengine.MarkdownParser
import com.example.data.importengine.MlKitPdfPageOcrEngine
import com.example.data.importengine.PdfDocumentParser
import com.example.data.importengine.PdfPageOcrEngine
import com.example.data.importengine.PlainTextParser
import com.example.data.importengine.SubtitleTranscriptParser
import com.example.data.importengine.SupportedImportFormat
import com.example.domain.SpeechTextProcessor
import com.example.translation.SourceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ExtractedDocumentSection(
    val title: String,
    val body: String,
    val sourceAnchor: String? = null
)

data class ParsedSourceDocument(
    val fileName: String,
    val title: String,
    val author: String,
    val sourceType: String, // "PDF", "EPUB", "DOCX", "TXT", "MD", "HTML", "SRT", "VTT", "TEXT"
    val detectedLanguage: SourceLanguage,
    val sections: List<ExtractedDocumentSection>,
    val sourceContentHash: String = "",
    val fileSizeBytes: Long = 0L,
    val detectedLanguageCode: String = detectedLanguage.code,
    val diagnostics: ExtractionDiagnostics? = null
)

data class ImportedBookPayload(
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val sentences: List<SentenceEntity>,
    val diagnostics: ExtractionDiagnostics? = null
)

class BookImporter internal constructor(
    private val context: Context,
    private val maxImportFileBytes: Long = MAX_IMPORT_FILE_BYTES,
    private val maxExtractedTextChars: Int = MAX_EXTRACTED_TEXT_CHARS,
    private val maxLineChars: Int = MAX_TXT_LINE_CHARS,
    private val maxEpubTotalUncompressedBytes: Long = MAX_EPUB_TOTAL_UNCOMPRESSED_BYTES,
    private val pdfOcrEngine: PdfPageOcrEngine = MlKitPdfPageOcrEngine()
) {
    constructor(context: Context) : this(
        context = context,
        maxImportFileBytes = MAX_IMPORT_FILE_BYTES,
        maxExtractedTextChars = MAX_EXTRACTED_TEXT_CHARS,
        maxLineChars = MAX_TXT_LINE_CHARS,
        maxEpubTotalUncompressedBytes = MAX_EPUB_TOTAL_UNCOMPRESSED_BYTES,
        pdfOcrEngine = MlKitPdfPageOcrEngine()
    )

    suspend fun inspectUri(uri: Uri): FileInspectionPreview = withContext(Dispatchers.IO) {
        val (rawName, declaredSize) = resolveFileMeta(uri)
        val fileName = rawName ?: "Document"
        val mimeType = context.contentResolver.getType(uri).orEmpty()

        if (declaredSize > maxImportFileBytes) {
            return@withContext FileInspectionPreview(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = declaredSize,
                mimeType = mimeType,
                detectedFormat = FormatDetector.detectFromMetadata(fileName, mimeType).format,
                isSupported = false,
                unsupportedReason = "حجم الملف يتجاوز الحد الأقصى المسموح (${maxImportFileBytes / (1024 * 1024)} MB)."
            )
        }

        val metaDetection = FormatDetector.detectFromMetadata(fileName, mimeType)
        if (metaDetection.format == null && metaDetection.rejectionReasonAr != null &&
            fileName.substringAfterLast('.', "").isNotEmpty()
        ) {
            return@withContext FileInspectionPreview(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = declaredSize.coerceAtLeast(0L),
                mimeType = mimeType,
                detectedFormat = null,
                isSupported = false,
                unsupportedReason = metaDetection.rejectionReasonAr
            )
        }

        // Fast bounded header inspection without duplicating massive files
        val tempPeek = File(context.cacheDir, "peek_${UUID.randomUUID()}.tmp")
        return@withContext try {
            val actualPeekBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val cap = if (metaDetection.format == SupportedImportFormat.EPUB ||
                    metaDetection.format == SupportedImportFormat.DOCX ||
                    fileName.endsWith(".zip", ignoreCase = true)
                ) {
                    maxImportFileBytes
                } else {
                    64 * 1024L
                }
                copyUpToBytes(stream, tempPeek, cap)
            } ?: return@withContext FileInspectionPreview(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = declaredSize.coerceAtLeast(0L),
                mimeType = mimeType,
                detectedFormat = null,
                isSupported = false,
                unsupportedReason = "تعذر فتح الملف المحدد من مزوّد التخزين."
            )

            val resolvedSize = if (declaredSize > 0L) declaredSize else actualPeekBytes
            val detected = FormatDetector.detectFromFile(tempPeek, fileName, mimeType)
            FileInspectionPreview(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = resolvedSize,
                mimeType = mimeType,
                detectedFormat = detected.format,
                isSupported = detected.format != null,
                unsupportedReason = detected.rejectionReasonAr,
                isPdfLikelyEncrypted = detected.format == SupportedImportFormat.PDF &&
                    tempPeek.length() == resolvedSize &&
                    PdfDocumentParser.isLikelyEncrypted(tempPeek)
            )
        } catch (e: Exception) {
            FileInspectionPreview(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = declaredSize.coerceAtLeast(0L),
                mimeType = mimeType,
                detectedFormat = metaDetection.format,
                isSupported = false,
                unsupportedReason = e.message ?: "تعذر فحص الملف المحدد."
            )
        } finally {
            tempPeek.delete()
        }
    }

    suspend fun inspectUris(uris: List<Uri>): List<FileInspectionPreview> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            currentCoroutineContext().ensureActive()
            inspectUri(uri)
        }
    }

    suspend fun parseDocumentFromUri(
        uri: Uri,
        options: DocumentExtractionOptions = DocumentExtractionOptions(),
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<ParsedSourceDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val meta = resolveFileMeta(uri)
            val fileName = meta.first ?: "Document"
            val declaredSize = meta.second
            val mimeType = context.contentResolver.getType(uri).orEmpty()

            if (declaredSize > maxImportFileBytes) {
                error("حجم الملف يتجاوز الحد الأقصى المسموح للاستيراد (${maxImportFileBytes / (1024 * 1024)} MB).")
            }

            onProgress(ImportProgressEvent(fileName, "جاري قراءة الملف...", 0.05f))

            val tempFile = File(context.cacheDir, "doc_import_${UUID.randomUUID()}.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    copyBoundedToTempFile(stream, tempFile, maxImportFileBytes)
                } ?: error("تعذر فتح الملف المحدد.")

                currentCoroutineContext().ensureActive()
                val actualSize = tempFile.length()
                if (actualSize == 0L) error("الملف المحدد فارغ.")

                val detection = FormatDetector.detectFromFile(tempFile, fileName, mimeType)
                val format = detection.format
                    ?: error(detection.rejectionReasonAr ?: "صيغة الملف غير مدعومة.")

                val fileHash = computeSha256(tempFile)
                currentCoroutineContext().ensureActive()

                val parsed = when (format) {
                    SupportedImportFormat.PDF -> PdfDocumentParser.parseFile(
                        context = context,
                        pdfFile = tempFile,
                        rawFileName = fileName,
                        options = options,
                        maxExtractedTextChars = maxExtractedTextChars,
                        ocrEngine = pdfOcrEngine,
                        onStageProgress = { stage, p ->
                            onProgress(ImportProgressEvent(fileName, stage, p))
                        }
                    )
                    SupportedImportFormat.EPUB -> EpubParser.parseFile(
                        epubFile = tempFile,
                        rawFileName = fileName,
                        maxEpubEntries = MAX_EPUB_ENTRIES,
                        maxSpineEntryBytes = MAX_EPUB_SPINE_ENTRY_BYTES,
                        maxTotalUncompressedBytes = maxEpubTotalUncompressedBytes,
                        maxExtractedTextChars = maxExtractedTextChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري استخراج فصول EPUB...", p))
                        }
                    )
                    SupportedImportFormat.DOCX -> DocxParser.parseFile(
                        docxFile = tempFile,
                        rawFileName = fileName,
                        maxZipEntries = MAX_EPUB_ENTRIES,
                        maxTotalUncompressedBytes = maxEpubTotalUncompressedBytes,
                        maxExtractedTextChars = maxExtractedTextChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري استخراج محتوى DOCX...", p))
                        }
                    )
                    SupportedImportFormat.MD -> MarkdownParser.parseFile(
                        file = tempFile,
                        rawFileName = fileName,
                        maxExtractedTextChars = maxExtractedTextChars,
                        maxLineChars = maxLineChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري تنسيق مستند Markdown...", p))
                        }
                    )
                    SupportedImportFormat.HTML -> HtmlDocumentParser.parseFile(
                        file = tempFile,
                        rawFileName = fileName,
                        maxExtractedTextChars = maxExtractedTextChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري تنظيف واستخراج HTML...", p))
                        }
                    )
                    SupportedImportFormat.SRT, SupportedImportFormat.VTT -> SubtitleTranscriptParser.parseFile(
                        file = tempFile,
                        rawFileName = fileName,
                        format = format,
                        maxExtractedTextChars = maxExtractedTextChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري ترتيب المقاطع الزمنية (${format.code})...", p))
                        }
                    )
                    SupportedImportFormat.TXT -> PlainTextParser.parseFile(
                        file = tempFile,
                        rawFileName = fileName,
                        maxExtractedTextChars = maxExtractedTextChars,
                        maxLineChars = maxLineChars,
                        onProgress = { p ->
                            onProgress(ImportProgressEvent(fileName, "جاري قراءة النص...", p))
                        }
                    )
                }

                parsed.copy(
                    sourceContentHash = fileHash,
                    fileSizeBytes = actualSize
                )
            } finally {
                tempFile.delete()
            }
        }
    }

    suspend fun importFromUri(
        uri: Uri,
        options: DocumentExtractionOptions = DocumentExtractionOptions(),
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<ImportedBookPayload> = withContext(Dispatchers.IO) {
        parseDocumentFromUri(uri, options, onProgress).mapCatching { doc ->
            currentCoroutineContext().ensureActive()
            onProgress(ImportProgressEvent(doc.fileName, "جاري تقسيم الجمل والحفظ...", 0.96f))
            val bookId = "${doc.sourceType.lowercase()}_${UUID.randomUUID().toString().take(8)}"
            buildBookPayload(
                bookId = bookId,
                title = doc.title,
                author = doc.author,
                sourceType = doc.sourceType,
                sourceLanguage = doc.detectedLanguageCode.ifBlank { doc.detectedLanguage.code },
                sections = doc.sections,
                sourceContentHash = doc.sourceContentHash,
                fileSizeBytes = doc.fileSizeBytes,
                diagnostics = doc.diagnostics
            )
        }
    }

    suspend fun importDirectText(
        title: String,
        rawBody: String,
        languageCode: String? = null
    ): Result<ImportedBookPayload> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanedBody = rawBody.replace("\r\n", "\n").replace('\r', '\n').trim()
            require(cleanedBody.isNotEmpty()) { "النص المدخل فارغ." }
            require(cleanedBody.length <= maxExtractedTextChars) {
                "النص يتجاوز الحد الأقصى المسموح ($maxExtractedTextChars حرفاً)."
            }
            val cleanTitle = title.trim().ifBlank {
                cleanedBody.lineSequence().firstOrNull { it.isNotBlank() }?.take(42)?.trim()
                    ?: "نص مباشر"
            }
            val lang = if (!languageCode.isNullOrBlank()) {
                SpeechTextProcessor.normalizeLangCode(languageCode)
            } else {
                LanguageDetector.detect(cleanedBody.take(2500), null).languageCode
            }
            val hash = computeSha256Bytes(cleanedBody.toByteArray(Charsets.UTF_8))
            val bookId = "txt_${UUID.randomUUID().toString().take(8)}"
            buildBookPayload(
                bookId = bookId,
                title = cleanTitle,
                author = "نص مضاف يدوياً",
                sourceType = "TEXT",
                sourceLanguage = lang,
                sections = listOf(ExtractedDocumentSection(cleanTitle, cleanedBody)),
                sourceContentHash = hash,
                fileSizeBytes = cleanedBody.toByteArray(Charsets.UTF_8).size.toLong()
            )
        }
    }

    private suspend fun copyBoundedToTempFile(input: InputStream, dest: File, maxBytes: Long) {
        var total = 0L
        BufferedInputStream(input, 64 * 1024).use { bis ->
            FileOutputStream(dest).use { fos ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = bis.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        error("يتجاوز حجم الملف الحد الأقصى المسموح (${maxBytes / (1024 * 1024)} MB).")
                    }
                    fos.write(buffer, 0, read)
                }
            }
        }
    }

    private fun copyUpToBytes(input: InputStream, dest: File, maxBytes: Long): Long {
        var total = 0L
        BufferedInputStream(input, 32 * 1024).use { bis ->
            FileOutputStream(dest).use { fos ->
                val buffer = ByteArray(16 * 1024)
                while (total < maxBytes) {
                    val toRead = minOf(buffer.size.toLong(), maxBytes - total).toInt()
                    val read = bis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    total += read
                }
            }
        }
        return total
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeSha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun buildBookPayload(
        bookId: String,
        title: String,
        author: String,
        sourceType: String,
        sourceLanguage: String,
        sections: List<ExtractedDocumentSection>,
        sourceContentHash: String = "",
        fileSizeBytes: Long = 0L,
        diagnostics: ExtractionDiagnostics? = null
    ): ImportedBookPayload {
        val chapters = mutableListOf<ChapterEntity>()
        val sentences = mutableListOf<SentenceEntity>()
        var totalSentences = 0

        sections.forEachIndexed { idx, section ->
            val processed = SpeechTextProcessor.splitAndPreprocess(section.body, sourceLanguage)
            if (processed.isNotEmpty()) {
                val realChIndex = chapters.size
                val chTitle = section.title.ifBlank {
                    section.sourceAnchor?.takeIf { it.isNotBlank() } ?: "Section ${idx + 1}"
                }
                chapters.add(
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = realChIndex,
                        title = chTitle,
                        sentenceCount = processed.size
                    )
                )
                processed.forEach { s ->
                    sentences.add(
                        SentenceEntity(
                            bookId = bookId,
                            chapterIndex = realChIndex,
                            sentenceIndex = s.index,
                            paragraphIndex = s.paragraphIndex,
                            originalText = s.originalText,
                            spokenText = s.spokenText
                        )
                    )
                }
                totalSentences += processed.size
            }
        }

        if (totalSentences == 0) error("لم يتم استخراج أي جمل صالحة من المستند.")

        val coverStyle = when (sourceType.uppercase()) {
            "AUDIO" -> "audio_waves"
            "EPUB" -> "atelier_classic"
            "PDF", "DOCX" -> "botanical_parchment"
            else -> "auto"
        }
        val book = BookEntity(
            id = bookId,
            title = title,
            author = author,
            sourceType = sourceType,
            sourceLanguage = sourceLanguage,
            totalChapters = chapters.size,
            totalSentences = totalSentences,
            mediaKind = if (sourceType.equals("AUDIO", ignoreCase = true)) "AUDIO" else "BOOK",
            coverStyleId = coverStyle,
            sourceContentHash = sourceContentHash.ifBlank { null },
            fileSizeBytes = fileSizeBytes
        )
        return ImportedBookPayload(book, chapters, sentences, diagnostics)
    }

    private fun resolveFileMeta(uri: Uri): Pair<String?, Long> {
        val resolved = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    name to size
                } else null
            }
        }.getOrNull()
        return (resolved?.first ?: uri.lastPathSegment) to (resolved?.second ?: -1L)
    }

    companion object {
        const val MAX_IMPORT_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_EPUB_ENTRIES = 2000
        const val MAX_EPUB_SPINE_ENTRY_BYTES = 5L * 1024L * 1024L
        const val MAX_EPUB_TOTAL_UNCOMPRESSED_BYTES = 10L * 1024L * 1024L
        const val MAX_EXTRACTED_TEXT_CHARS = 2_000_000
        const val MAX_TXT_LINE_CHARS = 16_384

        fun detectSourceLanguage(sampleText: String, opfLangHint: String?): SourceLanguage {
            return LanguageDetector.detect(sampleText, opfLangHint).translationSourceLanguage
        }
    }
}
