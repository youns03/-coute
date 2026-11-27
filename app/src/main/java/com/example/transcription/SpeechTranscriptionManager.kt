package com.example.transcription

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.BookDao
import com.example.data.BookEntity
import com.example.data.ChapterEntity
import com.example.data.RecentActivityDao
import com.example.data.RecentActivityEntity
import com.example.data.SentenceEntity
import com.example.data.SettingsRepository
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.runtime.LocalTaskCoordinator
import com.example.runtime.LocalTaskPriority
import com.example.runtime.LocalTaskType
import com.example.runtime.TaskControls
import com.example.tts.MemoryDiagnostics
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
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

interface OfflineAsrSession {
    fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String
    fun release()
}

data class TranscriptionRuntimeState(
    val isTranscribing: Boolean = false,
    val loadedAsrModelId: String? = null,
    val activeFileTitle: String = "",
    val progress: Float = 0f,
    val statusMessage: String = "",
    val lastError: String? = null,
    val lastCompletedBookId: String? = null
)

/**
 * Real offline audio transcription engine using Sherpa-ONNX OfflineRecognizer.
 * Decodes imported audio files locally -> runs NeMo FastConformer / Canary ASR in bounded
 * segments -> stores the resulting transcript as a readable, translatable & playable BookEntity
 * (`mediaKind = "AUDIO"`). Never generates simulated or mock transcripts.
 */
class SpeechTranscriptionManager internal constructor(
    private val context: Context,
    private val storageManager: ModelStorageManager,
    private val bookDao: BookDao,
    private val recentActivityDao: RecentActivityDao,
    private val taskCoordinator: LocalTaskCoordinator,
    private val settingsRepository: SettingsRepository,
    private val sessionFactory: ((ModelSpec, ModelStorageManager.AsrResolvedFiles, String) -> OfflineAsrSession)? = null,
    private val audioDecoder: (File) -> DecodedMonoAudio = AudioDecoder::decodeFileToMono16k,
    private val packageVerifier: ((ModelSpec) -> Boolean)? = null
) {
    constructor(
        context: Context,
        storageManager: ModelStorageManager,
        bookDao: BookDao,
        recentActivityDao: RecentActivityDao,
        taskCoordinator: LocalTaskCoordinator,
        settingsRepository: SettingsRepository
    ) : this(
        context = context,
        storageManager = storageManager,
        bookDao = bookDao,
        recentActivityDao = recentActivityDao,
        taskCoordinator = taskCoordinator,
        settingsRepository = settingsRepository,
        sessionFactory = null
    )

    private val mutex = Mutex()

    @Volatile
    private var activeSession: OfflineAsrSession? = null
    @Volatile
    private var loadedModelKey: String? = null
    @Volatile
    private var activityCallback: (() -> Unit)? = null

    private val _state = MutableStateFlow(TranscriptionRuntimeState())
    val state: StateFlow<TranscriptionRuntimeState> = _state.asStateFlow()

    val isAsrLoaded: Boolean
        get() = activeSession != null

    val isTranscribing: Boolean
        get() = _state.value.isTranscribing

    fun setOnActivityCallback(callback: () -> Unit) {
        activityCallback = callback
    }

    val importedAudioRoot: File
        get() = File(context.filesDir, "imported_audio").apply { mkdirs() }

    private fun verifyPackageReady(spec: ModelSpec): Boolean =
        packageVerifier?.invoke(spec) ?: storageManager.verifyInstalledPackage(spec, verifyIntegrity = true)

    fun resolveReadyAsrModel(preferredId: String): Result<ModelSpec> {
        val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
        val preferred = ModelCatalog.findById(preferredId)?.takeIf { it.engineType == EngineType.SHERPA_ASR }
        if (preferred != null && verifyPackageReady(preferred) &&
            MemoryDiagnostics.evaluateRuntimeMemoryForLoad(preferred, hw).isSupported
        ) {
            return Result.success(preferred)
        }
        val alternate = ModelCatalog.asrModels.firstOrNull { candidate ->
            verifyPackageReady(candidate) && MemoryDiagnostics.evaluateRuntimeMemoryForLoad(candidate, hw).isSupported
        }
        if (alternate != null) return Result.success(alternate)

        return Result.failure(
            IllegalStateException(
                "لا يوجد نموذج تفريغ صوتي مثبّت محلياً بعد. نزّل نموذج «التعرف على الكلام» من شاشة النماذج أولاً لتفريغ الصوتيات دون إنترنت."
            )
        )
    }

    suspend fun verifyModelLoadable(spec: ModelSpec, extractedDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val compat = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(spec, context)
                if (!compat.isSupported) error(compat.detailsAr ?: compat.summaryAr)

                val paths = storageManager.resolveAsrPaths(extractedDir)
                    ?: error("ملفات نموذج التعرف على الكلام غير مكتملة داخل الحزمة.")

                releaseActiveLocked()
                val testSession = createAsrSession(spec, paths, "fr")
                try {
                    // Run a short silence probe (0.5s) to validate native recognizer creation & inference
                    testSession.transcribeSegment(FloatArray(8_000), AudioDecoder.TARGET_SAMPLE_RATE)
                } finally {
                    runCatching { testSession.release() }
                }
                Unit
            }
        }
    }

    /**
     * Launches background offline audio import & transcription via [LocalTaskCoordinator].
     */
    fun enqueueAudioImportAndTranscribe(
        uri: Uri,
        languageCode: String,
        onCompletedBook: (String) -> Unit = {}
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

    suspend fun importAndTranscribeBlocking(
        uri: Uri,
        languageCode: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
            val settings = settingsRepository.settingsFlow.first()
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
                val duplicate = bookDao.findByContentHash(fileHash)
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
                    val session = ensureAsrSessionLocked(asrSpec, normLang)
                    segments.forEachIndexed { idx, segmentSamples ->
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("Cancelled")
                        }
                        val stepProgress = 0.30f + ((idx + 1).toFloat() / segments.size.coerceAtLeast(1).toFloat()) * 0.60f
                        val label = "تفريغ المقطع ${idx + 1} من ${segments.size}..."
                        onProgress(stepProgress, label)
                        _state.value = _state.value.copy(progress = stepProgress, statusMessage = label)

                        val recognized = session.transcribeSegment(segmentSamples, AudioDecoder.TARGET_SAMPLE_RATE).trim()
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
                val chapter = ChapterEntity(
                    bookId = bookId,
                    chapterIndex = 0,
                    title = title,
                    sentenceCount = processed.size
                )
                val sentences = processed.map { s ->
                    SentenceEntity(
                        bookId = bookId,
                        chapterIndex = 0,
                        sentenceIndex = s.index,
                        paragraphIndex = s.paragraphIndex,
                        originalText = s.originalText,
                        spokenText = s.spokenText
                    )
                }
                val durationMin = (decoded.durationMs / 60_000L).coerceAtLeast(1L)
                val book = BookEntity(
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
                bookDao.insertFullBook(book, listOf(chapter), sentences)
                recentActivityDao.upsert(
                    RecentActivityEntity(
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

    suspend fun releaseActiveEngine() {
        mutex.withLock {
            releaseActiveLocked()
        }
    }

    private fun cancelCurrentState() {
        _state.value = _state.value.copy(isTranscribing = false, statusMessage = "تم إلغاء التفريغ")
    }

    private fun ensureAsrSessionLocked(spec: ModelSpec, languageCode: String): OfflineAsrSession {
        val key = "${spec.id}:$languageCode"
        val current = activeSession
        if (current != null && loadedModelKey == key) return current

        releaseActiveLocked()
        val modelDir = storageManager.getInstalledModelDir(spec.id)
        val paths = storageManager.resolveAsrPaths(modelDir)
            ?: error("ملفات نموذج ${spec.displayName} مفقودة.")
        val created = createAsrSession(spec, paths, languageCode)
        activeSession = created
        loadedModelKey = key
        _state.value = _state.value.copy(loadedAsrModelId = spec.id)
        return created
    }

    private fun releaseActiveLocked() {
        runCatching { activeSession?.release() }
        activeSession = null
        loadedModelKey = null
        _state.value = _state.value.copy(loadedAsrModelId = null)
    }

    private fun createAsrSession(
        spec: ModelSpec,
        paths: ModelStorageManager.AsrResolvedFiles,
        languageCode: String
    ): OfflineAsrSession {
        sessionFactory?.let { return it(spec, paths, languageCode) }

        val modelConfig = OfflineModelConfig().apply {
            tokens = paths.tokensFile.absolutePath
            numThreads = 2
            provider = "cpu"
            if (paths.encoderFile != null && paths.decoderFile != null) {
                canary = OfflineCanaryModelConfig().apply {
                    encoder = paths.encoderFile.absolutePath
                    decoder = paths.decoderFile.absolutePath
                    srcLang = languageCode
                    tgtLang = languageCode
                    usePnc = true
                }
                modelType = "canary"
            } else if (paths.ctcModelFile != null) {
                nemo = OfflineNemoEncDecCtcModelConfig().apply {
                    model = paths.ctcModelFile.absolutePath
                }
                modelType = "nemo_ctc"
            } else {
                error("صيغة ملفات ASR غير مدعومة داخل ${spec.id}")
            }
        }

        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(AudioDecoder.TARGET_SAMPLE_RATE, 80, 0f)
            this.modelConfig = modelConfig
        }

        val recognizer = OfflineRecognizer(null, config)
        return object : OfflineAsrSession {
            override fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String {
                val stream = recognizer.createStream()
                return try {
                    stream.acceptWaveform(samples16k, sampleRateHz)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
            }

            override fun release() {
                recognizer.release()
            }
        }
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
