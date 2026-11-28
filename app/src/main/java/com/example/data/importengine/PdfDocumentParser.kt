package com.example.data.importengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.domain.DocumentExtractionOptions
import com.example.domain.ExtractedDocumentSection
import com.example.domain.ExtractionDiagnostics
import com.example.domain.ParsedSourceDocument
import com.example.domain.PdfExtractionMode
import com.example.domain.PdfPageClassification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

internal interface PdfPageOcrEngine {
    suspend fun recognizePages(
        pdfFile: File,
        pageIndicesZeroBased: List<Int>,
        onPageDone: suspend (pageIndexZeroBased: Int, progressFraction: Float) -> Unit = { _, _ -> }
    ): Map<Int, String>
}

internal class MlKitPdfPageOcrEngine : PdfPageOcrEngine {

    override suspend fun recognizePages(
        pdfFile: File,
        pageIndicesZeroBased: List<Int>,
        onPageDone: suspend (pageIndexZeroBased: Int, progressFraction: Float) -> Unit
    ): Map<Int, String> {
        if (pageIndicesZeroBased.isEmpty()) return emptyMap()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = linkedMapOf<Int, String>()

        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                val totalTarget = pageIndicesZeroBased.size
                pageIndicesZeroBased.forEachIndexed { idx, pageIdx ->
                    currentCoroutineContext().ensureActive()
                    if (pageIdx in 0 until renderer.pageCount) {
                        renderer.openPage(pageIdx).use { page ->
                            val maxEdge = 1600f
                            val scale = (maxEdge / maxOf(page.width, page.height).coerceAtLeast(1))
                                .coerceIn(1f, 2.5f)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val image = InputImage.fromBitmap(bitmap, 0)
                                val visionText = recognizer.process(image).await()
                                val cleaned = visionText.text.trim()
                                if (cleaned.isNotEmpty()) {
                                    results[pageIdx] = cleaned
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    onPageDone(pageIdx, (idx + 1).toFloat() / totalTarget.toFloat())
                }
            }
        } finally {
            runCatching { pfd.close() }
            runCatching { recognizer.close() }
        }
        return results
    }
}

internal object PdfDocumentParser {

    private val pdfBoxInitialized = AtomicBoolean(false)

    fun ensurePdfBoxInitialized(context: Context) {
        if (pdfBoxInitialized.compareAndSet(false, true)) {
            runCatching {
                PDFBoxResourceLoader.init(context.applicationContext)
            }.onFailure {
                pdfBoxInitialized.set(false)
            }
        }
    }

    fun isLikelyEncrypted(pdfFile: File): Boolean {
        return runCatching {
            PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                doc.isEncrypted
            }
        }.getOrElse { err ->
            err is InvalidPasswordException
        }
    }

    suspend fun parseFile(
        context: Context,
        pdfFile: File,
        rawFileName: String,
        options: DocumentExtractionOptions,
        maxExtractedTextChars: Int,
        ocrEngine: PdfPageOcrEngine = MlKitPdfPageOcrEngine(),
        onStageProgress: (stageLabelAr: String, progress: Float) -> Unit = { _, _ -> }
    ): ParsedSourceDocument {
        ensurePdfBoxInitialized(context)
        currentCoroutineContext().ensureActive()
        onStageProgress("جاري فحص ملف PDF...", 0.05f)

        val document: PDDocument = try {
            PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            error("ملف PDF محمي بكلمة مرور ولا يمكن فتحه بدون فك التشفير.")
        } catch (e: Exception) {
            error("ملف PDF تالف أو غير صالح (${e.message ?: "خطأ غير معروف"}).")
        }

        val pageCount: Int
        val metaTitle: String
        val metaAuthor: String
        val rawPageTexts: MutableList<String>
        val pageClassifications: MutableList<PdfPageClassification>

        document.use { doc ->
            pageCount = doc.numberOfPages
            if (pageCount <= 0) {
                error("ملف PDF فارغ ولا يحتوي على صفحات.")
            }
            metaTitle = doc.documentInformation?.title?.trim().orEmpty()
            metaAuthor = doc.documentInformation?.author?.trim().orEmpty()

            rawPageTexts = MutableList(pageCount) { "" }
            pageClassifications = MutableList(pageCount) { PdfPageClassification.EMPTY }

            if (options.pdfMode != PdfExtractionMode.FORCE_OCR) {
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                for (pageOneBased in 1..pageCount) {
                    currentCoroutineContext().ensureActive()
                    stripper.startPage = pageOneBased
                    stripper.endPage = pageOneBased
                    val extractedRaw = runCatching { stripper.getText(doc) }.getOrDefault("")
                    val cleaned = cleanExtractedPdfText(extractedRaw)
                    rawPageTexts[pageOneBased - 1] = cleaned

                    val alphaNumCount = cleaned.count { it.isLetterOrDigit() }
                    val hasImages = runCatching {
                        val res = doc.getPage(pageOneBased - 1)?.resources
                        res?.xObjectNames?.any { name -> res.isImageXObject(name) } == true
                    }.getOrDefault(false)

                    pageClassifications[pageOneBased - 1] = when {
                        alphaNumCount >= 30 -> if (hasImages && alphaNumCount < 180) {
                            PdfPageClassification.MIXED
                        } else {
                            PdfPageClassification.TEXT
                        }
                        hasImages || alphaNumCount > 0 -> PdfPageClassification.SCANNED_IMAGE
                        else -> PdfPageClassification.EMPTY
                    }

                    val extractRatio = (pageOneBased.toFloat() / pageCount.toFloat()) * 0.55f + 0.05f
                    onStageProgress("استخراج نص PDF ($pageOneBased / $pageCount)...", extractRatio)
                }
            } else {
                for (i in 0 until pageCount) {
                    pageClassifications[i] = PdfPageClassification.SCANNED_IMAGE
                }
            }
        }

        // Determine which pages need local OCR fallback
        val pagesNeedingOcr = when (options.pdfMode) {
            PdfExtractionMode.TEXT_ONLY -> emptyList()
            PdfExtractionMode.FORCE_OCR -> (0 until pageCount).take(options.maxOcrPages)
            PdfExtractionMode.AUTO_PREFER_TEXT -> {
                if (!options.enableOcrFallback) {
                    emptyList()
                } else {
                    pageClassifications.mapIndexedNotNull { idx, cls ->
                        if (cls == PdfPageClassification.SCANNED_IMAGE ||
                            (cls == PdfPageClassification.MIXED && rawPageTexts[idx].count { it.isLetterOrDigit() } < 45)
                        ) idx else null
                    }.take(options.maxOcrPages)
                }
            }
        }

        var ocrUsedCount = 0
        var ocrFailureReason: String? = null
        if (pagesNeedingOcr.isNotEmpty()) {
            onStageProgress("تشغيل OCR المحلي على الصفحات المصورة (${pagesNeedingOcr.size})...", 0.62f)
            val ocrOutcome = runCatching {
                ocrEngine.recognizePages(pdfFile, pagesNeedingOcr) { _, fraction ->
                    onStageProgress(
                        "التعرف على النصوص المصورة (OCR)...",
                        0.62f + (fraction * 0.30f)
                    )
                }
            }
            ocrOutcome.onSuccess { ocrMap ->
                ocrMap.forEach { (pageIdx, recognized) ->
                    val normalizedOcr = cleanExtractedPdfText(recognized)
                    if (normalizedOcr.isNotBlank()) {
                        ocrUsedCount++
                        val existing = rawPageTexts[pageIdx]
                        rawPageTexts[pageIdx] = if (existing.isBlank()) {
                            normalizedOcr
                        } else if (existing.count { it.isLetterOrDigit() } < 45) {
                            "$existing\n\n$normalizedOcr"
                        } else {
                            existing
                        }
                    }
                }
            }.onFailure { err ->
                ocrFailureReason = err.message
            }
        }

        // Build structured reading sections preserving page anchors
        val sections = mutableListOf<ExtractedDocumentSection>()
        val bucketText = StringBuilder()
        var bucketStartPage = -1
        var bucketEndPage = -1
        var pagesInBucket = 0
        var totalChars = 0L

        fun flushBucket() {
            val body = bucketText.toString().trim()
            if (body.isNotEmpty()) {
                val rangeLabel = if (bucketStartPage == bucketEndPage) {
                    "صفحة $bucketStartPage"
                } else {
                    "صفحات $bucketStartPage–$bucketEndPage"
                }
                sections.add(
                    ExtractedDocumentSection(
                        title = rangeLabel,
                        body = body,
                        sourceAnchor = "ص $bucketStartPage–$bucketEndPage"
                    )
                )
            }
            bucketText.clear()
            bucketStartPage = -1
            bucketEndPage = -1
            pagesInBucket = 0
        }

        rawPageTexts.forEachIndexed { idx, pageText ->
            currentCoroutineContext().ensureActive()
            if (pageText.isNotBlank()) {
                totalChars += pageText.length
                if (totalChars > maxExtractedTextChars) {
                    error("حجم النص المستخرج من PDF يتجاوز الحد الأقصى المسموح ($maxExtractedTextChars حرفاً).")
                }
                val pageOneBased = idx + 1
                if (bucketStartPage < 0) bucketStartPage = pageOneBased
                bucketEndPage = pageOneBased
                if (bucketText.isNotEmpty()) bucketText.append("\n\n")
                bucketText.append(pageText)
                pagesInBucket++
                if (bucketText.length >= 3200 || pagesInBucket >= 8) {
                    flushBucket()
                }
            }
        }
        flushBucket()

        val hadScannedPages = pageClassifications.any {
            it == PdfPageClassification.SCANNED_IMAGE || it == PdfPageClassification.MIXED
        }
        val hasTextLayer = pageClassifications.any { it == PdfPageClassification.TEXT }

        if (sections.isEmpty()) {
            val detail = when {
                options.pdfMode == PdfExtractionMode.TEXT_ONLY && hadScannedPages ->
                    "ملف PDF مصور (يحتوي على صور صفحات دون طبقة نصية). فعّل وضع «تلقائي + OCR» لاستخراج النصوص اللاتينية المصورة."
                ocrFailureReason != null ->
                    "تعذر استخراج نص من هذا PDF المصور محلياً: $ocrFailureReason"
                hadScannedPages ->
                    "هذا الملف يحتوي على صفحات مصورة لم ينجح OCR المحلي في استخراج نص منها (يدعم OCR المحلي الحروف اللاتينية الفرنسية والإنجليزية؛ ولا يدعم OCR العربي للصور دون اتصال)."
                else ->
                    "ملف PDF لا يحتوي على نص قابل للاستخراج."
            }
            error(detail)
        }

        val warningAr = when {
            hadScannedPages && ocrUsedCount == 0 && pagesNeedingOcr.isNotEmpty() ->
                "بعض الصفحات مصورة ولم يتم التعرف عليها (OCR المحلي يدعم الفرنسية والإنجليزية فقط)."
            pagesNeedingOcr.size < pageClassifications.count { it == PdfPageClassification.SCANNED_IMAGE } ->
                "تمت معالجة أول ${options.maxOcrPages} صفحة مصورة عبر OCR المحلي حفاظاً على الذاكرة."
            else -> null
        }

        val sample = sections.take(3).joinToString(" ") { it.body }.take(3000)
        val lang = LanguageDetector.detect(sample, null)
        val resolvedTitle = metaTitle.ifBlank { rawFileName.substringBeforeLast('.').ifBlank { "مستند PDF" } }
        val resolvedAuthor = metaAuthor.ifBlank {
            if (ocrUsedCount > 0) "PDF ($pageCount ص • OCR محلي)" else "PDF ($pageCount ص)"
        }

        onStageProgress("تم تجهيز المستند بنجاح", 1f)

        return ParsedSourceDocument(
            fileName = rawFileName,
            title = resolvedTitle,
            author = resolvedAuthor,
            sourceType = "PDF",
            detectedLanguage = lang.translationSourceLanguage,
            detectedLanguageCode = lang.languageCode,
            sections = sections,
            diagnostics = ExtractionDiagnostics(
                detectedLanguageCode = lang.languageCode,
                translationSourceLanguage = lang.translationSourceLanguage,
                pageCount = pageCount,
                ocrPagesUsed = ocrUsedCount,
                pdfHasTextLayer = hasTextLayer,
                pdfHadScannedPages = hadScannedPages,
                warningMessageAr = warningAr
            )
        )
    }

    internal fun cleanExtractedPdfText(raw: String): String {
        if (raw.isBlank()) return ""
        // Normalize Arabic presentation forms (FB50-FEFF) & compatibility forms cleanly
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u00AD", "") // soft hyphen
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')

        // Rejoin hyphenated Latin words wrapped across line breaks (e.g. "impor-\ntant" -> "important")
        text = Regex("""([A-Za-zÀ-ÿ])-\n([a-zà-ÿ])""").replace(text) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}"
        }

        // Collapse single line breaks inside paragraphs while preserving true blank-line paragraph breaks
        val rawParagraphs = text.split(Regex("""\n\s*\n+"""))
        val cleanedParagraphs = rawParagraphs.mapNotNull { para ->
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@mapNotNull null
            // Drop isolated page-number-only lines like "12" or "- 12 -"
            val filtered = lines.filterNot { line ->
                line.matches(Regex("""^[-–—]?\s*\d{1,4}\s*[-–—]?$"""))
            }
            val joined = filtered.joinToString(" ").replace(Regex("""\s{2,}"""), " ").trim()
            joined.takeIf { it.isNotEmpty() }
        }
        return cleanedParagraphs.joinToString("\n\n").trim()
    }
}
