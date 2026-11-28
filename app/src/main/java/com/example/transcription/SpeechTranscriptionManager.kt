package com.example.transcription

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.contracts.AudioTranscriptionGateway
import com.example.contracts.LibraryContentGateway
import com.example.contracts.ModelCatalogProvider
import com.example.contracts.ModelStorageGateway
import com.example.contracts.RecentActivityGateway
import com.example.contracts.SettingsGateway
import com.example.contracts.SpeechRecognitionEngine
import com.example.contracts.TaskControls
import com.example.contracts.TaskCoordinatorGateway
import com.example.domain.Book
import com.example.domain.Chapter
import com.example.domain.EngineType
import com.example.domain.LocalTaskPriority
import com.example.domain.LocalTaskType
import com.example.domain.MemoryDiagnostics
import com.example.domain.ModelRole
import com.example.domain.ModelSpec
import com.example.domain.RecentActivity
import com.example.domain.Sentence
import com.example.domain.SpeechTextProcessor
import com.example.domain.TranscriptionRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs

/**
 * Coordinates local audio import, WAV/MediaCodec decoding, silence-valley segmentation,
 * and offline speech recognition via a swappable [SpeechRecognitionEngine].
 */
class SpeechTranscriptionManager internal constructor(
    private val context: Context,
    private val storageManager: ModelStorageGateway,
    private val modelCatalog: ModelCatalogProvider,
    private val libraryGateway: LibraryContentGateway,
    private val recentActivityGateway: RecentActivityGateway,
    private val taskCoordinator: TaskCoordinatorGateway,
    private val settingsGateway: SettingsGateway,
    private val recognitionEngine: SpeechRecognitionEngine,
    private val audioDecoder: (File) -> DecodedMonoAudio = AudioDecoder::decodeFileToMono16k,
    private val packageVerifier: ((ModelSpec) -> Boolean)? = null
) : AudioTranscriptionGateway {

    constructor(
        context: Context,
        storageManager: ModelStorageGateway,
        modelCatalog: ModelCatalogProvider,
        libraryGateway: LibraryContentGateway,
        recentActivityGateway: RecentActivityGateway,
        taskCoordinator: TaskCoordinatorGateway,
        settingsGateway: SettingsGateway,
        recognitionEngine: SpeechRecognitionEngine = SherpaSpeechRecognitionEngine(context, storageManager)
    ) : this(
        context = context,
        storageManager = storageManager,
        modelCatalog = modelCatalog,
        libraryGateway = libraryGateway,
        recentActivityGateway = recentActivityGateway,
        taskCoordinator = taskCoordinator,
        settingsGateway = settingsGateway,
        recognitionEngine = recognitionEngine,
        audioDecoder = AudioDecoder::decodeFileToMono16k,
        packageVerifier = null
    )

    private val mutex = Mutex()

    @Volatile
    private var activityCallback: (() -> Unit)? = null

    private val _state = MutableStateFlow(TranscriptionRuntimeState())
    override val state: StateFlow<TranscriptionRuntimeState> = _state.asStateFlow()

    override val isAsrLoaded: Boolean
        get() = recognitionEngine.isLoaded

    override val isTranscribing: Boolean
        get() = _state.value.isTranscribing

    override fun supportsRole(role: ModelRole): Boolean = role == ModelRole.ASR

    override fun setOnActivityCallback(callback: () -> Unit) {
        activityCallback = callback
    }

    val importedAudioRoot: File
        get() = File(context.filesDir, "imported_audio").apply { mkdirs() }

    private fun verifyPackageReady(spec: ModelSpec): Boolean =
        packageVerifier?.invoke(spec) ?: storageManager.verifyInstalledPackage(spec, verifyIntegrity = true)

    override fun resolveReadyAsrModel(preferredId: String): Result<ModelSpec> {
        val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
        val preferred = modelCatalog.findById(preferredId)?.takeIf { it.engineType == EngineType.SHERPA_ASR }
        if (preferred != null && verifyPackageReady(preferred) &&
            MemoryDiagnostics.evaluateRuntimeMemoryForLoad(preferred, hw).isSupported
        ) {
            return Result.success(preferred)
        }
        val alternate = modelCatalog.asrModels.firstOrNull { candidate ->
            verifyPackageReady(candidate) && MemoryDiagnostics.evaluateRuntimeMemoryForLoad(candidate, hw).isSupported
        }
        if (alternate != null) return Result.success(alternate)

        return Result.failure(
            IllegalStateException(
                "لا يوجد نموذج تفريغ صوتي مثبّت محلياً بعد. نزّل نموذج «التعرف على الكلام» من شاشة النماذج أولاً لتفريغ الصوتيات دون إنترنت."
            )
        )
    }

    override suspend fun verifyModelLoadable(spec: ModelSpec, extractedDir: File): Result<Unit> =
        recognitionEngine.verifyLoadable(spec, extractedDir)

    /**
     * Launches background offline audio import & transcription via [TaskCoordinatorGateway].
     */
    override fun enqueueAudioImportAndTranscribe(
        uri: Uri,
        languageCode: String,
        onCompletedBook: (String) -> Unit
    ) {
        val meta = resolveUriDisplayNameAndSize(uri)
        val displayTitle = meta.first.substringBeforeLast('.').ifBlank { "تسجيل صوتي" }
        val taskId = "asr_${UUID.randomUUID().toString().take(8)}"

        taskCoordinator.launchManagedTask(
            id = taskId,
            type = LocalTaskType.AUDIO_TRANSCRIPTION,
            title = displayTitle,
            subtitle = "جاري تحضير الملف الصوتي للتفريغ...",
            priority = LocalTaskPriority.HIGH,
            requiresHeavyInferenceLock = true,
            canCancel = true,
            controls = TaskControls(
                onCancel = { cancelCurrentState() }
            )
        ) { reporter ->
            val bookResult = importAndTranscribeBlocking(
                uri = uri,
                languageCode = languageCode,
                onProgress = { pct, msg -> reporter.report(pct, msg) }
            )
            val book = bookResult.getOrThrow()
            onCompletedBook(book.id)
        }
    }

    override suspend fun importAndTranscribeBlocking(
        uri: Uri,
        languageCode: String,
        onProgress: (Float, String) -> Unit
    ): Result<Book> = withContext(Dispatchers.IO) {
        runCatching {
            val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
            val settings = settingsGateway.settingsFlow.first()
            val asrSpec = resolveReadyAsrModel(settings.activeAsrModelId).getOrThrow()

            val meta = resolveUriDisplayNameAndSize(uri)
            val rawName = meta.first
            val title = rawName.substringBeforeLast('.').ifBlank { "ملف صوتي" }
            val ext = rawName.substringAfterLast('.', "m4a").lowercase().takeIf { it.length <= 5 } ?: "m4a"
            val bookId = "aud_${UUID.randomUUID().toString().take(8)}"
            val destAudioFile = File(importedAudioRoot, "$bookId.$ext")

            _state.value = TranscriptionRuntimeState(
                isTranscribing = true,
                activeFileTitle = title,
                progress = 0.05f,
                statusMessage = "نسخ الملف الصوتي محلياً..."
            )
            onProgress(0.05f, "نسخ الملف الصوتي محلياً...")

            var copiedSuccessfully = false
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destAudioFile).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            copied += read
                            if (copied > MAX_AUDIO_IMPORT_BYTES) {
                                error("حجم الملف الصوتي يتجاوز الحد الأقصى (${MAX_AUDIO_IMPORT_BYTES / (1024 * 1024)} MB).")
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                } ?: error("تعذر قراءة الملف الصوتي المحدد.")

                val fileHash = storageManager.computeSha256(destAudioFile)
                val duplicate = libraryGateway.findBookByContentHash(fileHash)
                if (duplicate != null) {
                    destAudioFile.delete()
                    _state.value = TranscriptionRuntimeState(
                        isTranscribing = false,
                        progress = 1f,
                        statusMessage = "هذا الملف مستورد مسبقاً.",
                        lastCompletedBookId = duplicate.id
                    )
                    return@runCatching duplicate
                }

                onProgress(0.15f, "فك ترميز الصوت إلى 16 kHz أحادي...")
                _state.value = _state.value.copy(progress = 0.15f, statusMessage = "فك ترميز الصوت محلياً...")
                val decoded = audioDecoder(destAudioFile)

                onProgress(0.28f, "تحميل نموذج التفريغ الصوتي محلياً...")
                _state.value = _state.value.copy(progress = 0.28f, statusMessage = "تحميل نموذج التفريغ...")

                val segments = segmentSpeechWindows(decoded.samples16k, AudioDecoder.TARGET_SAMPLE_RATE)
                val transcriptChunks = mutableListOf<String>()

                mutex.withLock {
                    activityCallback?.invoke()
                    recognitionEngine.ensureLoaded(asrSpec, normLang).getOrThrow()
                    _state.value = _state.value.copy(loadedAsrModelId = asrSpec.id)
                    segments.forEachIndexed { idx, segmentSamples ->
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("Cancelled")
                        }
                        val stepProgress = 0.30f + ((idx + 1).toFloat() / segments.size.coerceAtLeast(1).toFloat()) * 0.60f
                        val label = "تفريغ المقطع ${idx + 1} من ${segments.size}..."
                        onProgress(stepProgress, label)
                        _state.value = _state.value.copy(progress = stepProgress, statusMessage = label)

                        val recognized = recognitionEngine
                            .transcribeSegment(segmentSamples, AudioDecoder.TARGET_SAMPLE_RATE)
                            .trim()
                        if (recognized.isNotEmpty()) {
                            transcriptChunks.add(recognized)
                        }
                        activityCallback?.invoke()
                    }
                }

                val combinedText = transcriptChunks.joinToString("\n\n").trim()
                if (combinedText.isEmpty()) {
                    error("لم يتم التعرف على كلام واضح داخل هذا الملف الصوتي.")
                }

                onProgress(0.94f, "حفظ النص والفصول في المكتبة...")
                val processed = SpeechTextProcessor.splitAndPreprocess(combinedText, normLang)
                val chapter = Chapter(
                    bookId = bookId,
                    chapterIndex = 0,
                    title = title,
                    sentenceCount = processed.size
                )
                val sentences = processed.map { s ->
                    Sentence(
                        bookId = bookId,
                        chapterIndex = 0,
                        sentenceIndex = s.index,
                        paragraphIndex = s.paragraphIndex,
                        originalText = s.originalText,
                        spokenText = s.spokenText
                    )
                }
                val durationMin = (decoded.durationMs / 60_000L).coerceAtLeast(1L)
                val book = Book(
                    id = bookId,
                    title = title,
                    author = "تفريغ صوتي محلي",
                    sourceType = "AUDIO",
                    sourceLanguage = normLang,
                    levelTag = "${durationMin} د • مفرّغ",
                    totalChapters = 1,
                    totalSentences = sentences.size,
                    mediaKind = "AUDIO",
                    coverStyleId = "audio_waves",
                    sourceContentHash = fileHash,
                    fileSizeBytes = destAudioFile.length(),
                    audioFilePath = destAudioFile.absolutePath,
                    audioDurationMs = decoded.durationMs
                )
                libraryGateway.insertTranscribedBook(book, listOf(chapter), sentences)
                recentActivityGateway.upsert(
                    RecentActivity(
                        id = "act_book_$bookId",
                        kind = "TRANSCRIPTION",
                        title = book.title,
                        subtitle = "تم تفريغ الملف الصوتي (${sentences.size} جملة)",
                        targetRoute = "reader",
                        targetId = bookId,
                        progress = 0f
                    )
                )
                copiedSuccessfully = true
                _state.value = TranscriptionRuntimeState(
                    isTranscribing = false,
                    loadedAsrModelId = asrSpec.id,
                    activeFileTitle = title,
                    progress = 1f,
                    statusMessage = "اكتمل التفريغ الصوتي وحُفظ في مكتبتك.",
                    lastCompletedBookId = bookId
                )
                book
            } finally {
                if (!copiedSuccessfully && destAudioFile.exists()) {
                    destAudioFile.delete()
                }
            }
        }.onFailure { err ->
            if (err !is CancellationException) {
                _state.value = _state.value.copy(
                    isTranscribing = false,
                    lastError = err.message ?: "تعذر تفريغ الملف الصوتي"
                )
            } else {
                _state.value = _state.value.copy(isTranscribing = false, statusMessage = "تم إلغاء التفريغ")
            }
        }
    }

    override suspend fun releaseActiveEngine() {
        mutex.withLock {
            recognitionEngine.release()
            _state.value = _state.value.copy(loadedAsrModelId = null)
        }
    }

    private fun cancelCurrentState() {
        _state.value = _state.value.copy(isTranscribing = false, statusMessage = "تم إلغاء التفريغ")
    }

    private fun resolveUriDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        val resolved = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val n = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val s = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                    n to s
                } else null
            }
        }.getOrNull()
        return (resolved?.first ?: uri.lastPathSegment ?: "audio.m4a") to (resolved?.second ?: -1L)
    }

    companion object {
        const val MAX_AUDIO_IMPORT_BYTES = 180L * 1024L * 1024L
        private const val WINDOW_SECONDS = 24
        private const val MIN_ENERGY_RMS = 0.002f

        /**
         * Splits waveform into <= 24s speech windows near quiet valleys so long podcasts/books
         * never overflow native ONNX tensors or drop mid-word boundaries.
         */
        fun segmentSpeechWindows(samples: FloatArray, sampleRateHz: Int): List<FloatArray> {
            if (samples.isEmpty()) return emptyList()
            val maxSamplesPerWindow = WINDOW_SECONDS * sampleRateHz
            if (samples.size <= maxSamplesPerWindow) {
                return listOf(samples)
            }
            val segments = mutableListOf<FloatArray>()
            var start = 0
            val searchSpan = 3 * sampleRateHz // look back up to 3s for lowest energy pause
            while (start < samples.size) {
                val hardEnd = (start + maxSamplesPerWindow).coerceAtMost(samples.size)
                if (hardEnd == samples.size) {
                    val tail = samples.copyOfRange(start, hardEnd)
                    if (hasMeaningfulEnergy(tail)) segments.add(tail)
                    break
                }
                val searchStart = (hardEnd - searchSpan).coerceAtLeast(start + sampleRateHz * 5)
                var bestSplit = hardEnd
                var lowestWindowEnergy = Float.MAX_VALUE
                val step = sampleRateHz / 10 // 100ms frame
                var pos = searchStart
                while (pos + step < hardEnd) {
                    var sumAbs = 0f
                    for (k in pos until pos + step) sumAbs += abs(samples[k])
                    val avg = sumAbs / step.toFloat()
                    if (avg < lowestWindowEnergy) {
                        lowestWindowEnergy = avg
                        bestSplit = pos + (step / 2)
                    }
                    pos += step
                }
                val chunk = samples.copyOfRange(start, bestSplit)
                if (hasMeaningfulEnergy(chunk)) segments.add(chunk)
                start = bestSplit
            }
            return segments.ifEmpty { listOf(samples.copyOfRange(0, minOf(samples.size, maxSamplesPerWindow))) }
        }

        private fun hasMeaningfulEnergy(samples: FloatArray): Boolean {
            if (samples.isEmpty()) return false
            var sum = 0f
            val step = (samples.size / 500).coerceAtLeast(1)
            var count = 0
            var i = 0
            while (i < samples.size) {
                sum += abs(samples[i])
                count++
                i += step
            }
            return (sum / count.coerceAtLeast(1)) >= MIN_ENERGY_RMS * 0.25f
        }
    }
}
