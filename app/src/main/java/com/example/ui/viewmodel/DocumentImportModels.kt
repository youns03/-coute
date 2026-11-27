package com.example.ui.viewmodel

import com.example.data.importengine.FileInspectionPreview
import com.example.data.importengine.PdfExtractionMode
import com.example.data.importengine.SupportedImportFormat

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
