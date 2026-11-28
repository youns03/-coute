package com.example.domain

import android.net.Uri

enum class ImportFormatCategory(val labelAr: String) {
    DOCUMENTS("الكتب والمستندات"),
    TEXT_WEB("النصوص والويب"),
    SUBTITLES("ملفات الترجمة والتفريغ")
}

enum class SupportedImportFormat(
    val code: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
    val displayNameAr: String,
    val category: ImportFormatCategory
) {
    PDF(
        code = "PDF",
        extensions = setOf("pdf"),
        mimeTypes = setOf("application/pdf", "application/x-pdf"),
        displayNameAr = "PDF",
        category = ImportFormatCategory.DOCUMENTS
    ),
    EPUB(
        code = "EPUB",
        extensions = setOf("epub"),
        mimeTypes = setOf("application/epub+zip"),
        displayNameAr = "EPUB",
        category = ImportFormatCategory.DOCUMENTS
    ),
    DOCX(
        code = "DOCX",
        extensions = setOf("docx"),
        mimeTypes = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ),
        displayNameAr = "Word (DOCX)",
        category = ImportFormatCategory.DOCUMENTS
    ),
    TXT(
        code = "TXT",
        extensions = setOf("txt", "text", "log"),
        mimeTypes = setOf("text/plain"),
        displayNameAr = "نص (TXT)",
        category = ImportFormatCategory.TEXT_WEB
    ),
    MD(
        code = "MD",
        extensions = setOf("md", "markdown", "mdown"),
        mimeTypes = setOf("text/markdown", "text/x-markdown"),
        displayNameAr = "Markdown",
        category = ImportFormatCategory.TEXT_WEB
    ),
    HTML(
        code = "HTML",
        extensions = setOf("html", "htm", "xhtml"),
        mimeTypes = setOf("text/html", "application/xhtml+xml"),
        displayNameAr = "HTML",
        category = ImportFormatCategory.TEXT_WEB
    ),
    SRT(
        code = "SRT",
        extensions = setOf("srt"),
        mimeTypes = setOf("application/x-subrip", "text/srt"),
        displayNameAr = "ترجمة SRT",
        category = ImportFormatCategory.SUBTITLES
    ),
    VTT(
        code = "VTT",
        extensions = setOf("vtt"),
        mimeTypes = setOf("text/vtt"),
        displayNameAr = "تفريغ VTT",
        category = ImportFormatCategory.SUBTITLES
    );

    companion object {
        fun fromExtension(ext: String?): SupportedImportFormat? {
            val clean = ext.orEmpty().trim().trimStart('.').lowercase()
            if (clean.isEmpty()) return null
            return entries.firstOrNull { clean in it.extensions }
        }

        fun fromMimeType(mime: String?): SupportedImportFormat? {
            val clean = mime.orEmpty().substringBefore(';').trim().lowercase()
            if (clean.isEmpty()) return null
            return entries.firstOrNull { fmt ->
                fmt.mimeTypes.any { it.equals(clean, ignoreCase = true) }
            }
        }

        fun safPickerMimeTypes(): Array<String> = arrayOf(
            "application/pdf",
            "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "application/xhtml+xml",
            "text/vtt",
            "application/x-subrip",
            "application/octet-stream"
        )
    }
}

enum class PdfExtractionMode(val labelAr: String) {
    AUTO_PREFER_TEXT("تلقائي (نص مباشر + OCR عند الحاجة)"),
    TEXT_ONLY("استخراج النص المدمج فقط"),
    FORCE_OCR("OCR محلي للصفحات (فرنسية / إنجليزية)")
}

enum class PdfPageClassification {
    TEXT,
    SCANNED_IMAGE,
    MIXED,
    EMPTY
}

data class DocumentExtractionOptions(
    val pdfMode: PdfExtractionMode = PdfExtractionMode.AUTO_PREFER_TEXT,
    val enableOcrFallback: Boolean = true,
    val maxOcrPages: Int = 40
)

data class ImportProgressEvent(
    val fileName: String,
    val stageLabelAr: String,
    val progress: Float
)

data class FileInspectionPreview(
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val detectedFormat: SupportedImportFormat?,
    val isSupported: Boolean,
    val unsupportedReason: String? = null,
    val isPdfLikelyEncrypted: Boolean = false
)

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class ExtractionDiagnostics(
    val detectedLanguageCode: String,
    val translationSourceLanguage: SourceLanguage,
    val detectedCharset: String? = null,
    val pageCount: Int = 0,
    val ocrPagesUsed: Int = 0,
    val pdfHasTextLayer: Boolean = false,
    val pdfHadScannedPages: Boolean = false,
    val warningMessageAr: String? = null,
    val subtitleCues: List<SubtitleCue> = emptyList()
)

data class ExtractedDocumentSection(
    val title: String,
    val body: String,
    val sourceAnchor: String? = null
)

data class ParsedSourceDocument(
    val fileName: String,
    val title: String,
    val author: String,
    val sourceType: String,
    val detectedLanguage: SourceLanguage,
    val sections: List<ExtractedDocumentSection>,
    val sourceContentHash: String = "",
    val fileSizeBytes: Long = 0L,
    val detectedLanguageCode: String = detectedLanguage.code,
    val diagnostics: ExtractionDiagnostics? = null
)

data class DocumentImportQueueState(
    val isVisible: Boolean = false,
    val isInspecting: Boolean = false,
    val isImporting: Boolean = false,
    val inspectedFiles: List<FileInspectionPreview> = emptyList(),
    val pdfMode: PdfExtractionMode = PdfExtractionMode.AUTO_PREFER_TEXT,
    val activeFileName: String = "",
    val activeStageLabel: String = "",
    val currentFileProgress: Float = 0f,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val errorMessage: String? = null
) {
    val supportedFiles: List<FileInspectionPreview>
        get() = inspectedFiles.filter { it.isSupported }

    val hasValidPdf: Boolean
        get() = supportedFiles.any { it.detectedFormat == SupportedImportFormat.PDF }
}
