package com.example.tts

import android.content.Context
import android.os.SystemClock
import com.example.data.BenchmarkReportEntity
import com.example.domain.AcceleratorMode
import com.example.domain.BenchmarkItem
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EngineRuntimeState(
    val loadedModelId: String? = null,
    val loadedModelName: String? = null,
    val engineType: EngineType? = null,
    val acceleratorLabel: String = "غير محمل",
    val fellBackToCpu: Boolean = false,
    val fallbackReason: String? = null,
    val lastLoadTimeMs: Long = 0L,
    val ramBeforeLoadMb: Float = 0f,
    val ramAfterLoadMb: Float = 0f,
    val ramPeakSynthesisMb: Float = 0f,
    val isLoading: Boolean = false,
    val lastError: String? = null
)

class TtsEngineManager internal constructor(
    private val context: Context,
    private val storageManager: ModelStorageManager,
    private val engineFactory: ((ModelSpec) -> TtsEngine)? = null,
    private val packageVerifier: ((ModelSpec) -> Boolean)? = null
) {
    constructor(context: Context, storageManager: ModelStorageManager) : this(
        context = context,
        storageManager = storageManager,
        engineFactory = null,
        packageVerifier = null
    )

    private val mutex = Mutex()
    @Volatile
    private var activeEngine: TtsEngine? = null

    private val _runtimeState = MutableStateFlow(EngineRuntimeState())
    val runtimeState: StateFlow<EngineRuntimeState> = _runtimeState.asStateFlow()

    @Volatile
    private var onTtsActivityCallback: (() -> Unit)? = null

    val isEngineLoaded: Boolean
        get() = activeEngine?.isLoaded == true

    fun setOnActivityCallback(callback: () -> Unit) {
        onTtsActivityCallback = callback
    }

    private fun verifyPackageReady(spec: ModelSpec): Boolean {
        return packageVerifier?.invoke(spec)
            ?: storageManager.verifyInstalledPackage(spec, verifyIntegrity = true)
    }

    private fun createEngine(spec: ModelSpec): TtsEngine {
        return engineFactory?.invoke(spec) ?: when (spec.engineType) {
            EngineType.KOKORO_LITERT -> KokoroLiteRtTtsEngine(context, storageManager)
            EngineType.PIPER_SHERPA_ONNX -> PiperSherpaTtsEngine(storageManager)
            EngineType.SHERPA_ASR -> error("نموذج ${spec.displayName} مخصص للتعرف على الكلام وليس للنطق الصوتي.")
        }
    }

    private fun releaseActiveLocked(errorMessage: String? = null) {
        activeEngine?.let { prev ->
            runCatching { prev.cancel() }
            runCatching { prev.release() }
        }
        activeEngine = null
        _runtimeState.value = EngineRuntimeState(lastError = errorMessage)
    }

    /**
     * Resolves an installed, device-compatible model supporting [languageCode].
     * Prefers [preferredModelId]; if it only supports another language (e.g. Piper on an English book)
     * or temporarily lacks free RAM, automatically selects another compatible ready model or fails clearly.
     */
    fun resolveModelForLanguage(
        preferredModelId: String,
        languageCode: String
    ): Result<ModelSpec> {
        val normLang = SpeechTextProcessor.normalizeLangCode(languageCode)
        val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
        val preferred = ModelCatalog.findById(preferredModelId)

        fun isReadyAndCompatible(spec: ModelSpec): Boolean {
            if (!MemoryDiagnostics.evaluateRuntimeMemoryForLoad(spec, hw).isSupported) return false
            if (!verifyPackageReady(spec)) return false
            if (spec.requiresEspeakData && packageVerifier == null &&
                !storageManager.verifyInstalledPackage(ModelCatalog.espeakNgDataSpec, verifyIntegrity = true)
            ) {
                return false
            }
            return true
        }

        if (preferred != null && isReadyAndCompatible(preferred) && preferred.supportsLanguage(normLang)) {
            return Result.success(preferred)
        }

        val alternate = ModelCatalog.ttsModels.firstOrNull { candidate ->
            candidate.id != preferredModelId &&
                candidate.supportsLanguage(normLang) &&
                isReadyAndCompatible(candidate)
        }
        if (alternate != null) {
            return Result.success(alternate)
        }

        return when {
            preferred != null && isReadyAndCompatible(preferred) && !preferred.supportsLanguage(normLang) -> {
                Result.failure(
                    IllegalStateException(
                        "النموذج النشط (${preferred.displayName}) يدعم الفرنسية فقط ولا يدعم ${if (normLang == "en") "الإنجليزية" else normLang}. ثبّت نموذج Kokoro-82M من شاشة النماذج لقراءة النصوص الإنجليزية."
                    )
                )
            }
            preferred != null && !MemoryDiagnostics.evaluateRuntimeMemoryForLoad(preferred, hw).isSupported -> {
                val report = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(preferred, hw)
                Result.failure(IllegalStateException(report.detailsAr ?: report.summaryAr))
            }
            else -> {
                Result.failure(
                    IllegalStateException("لا يوجد نموذج صوتي مثبّت ومتحقق منه للغة ($normLang). افتح شاشة النماذج ونزّل نموذجاً متوافقاً.")
                )
            }
        }
    }

    suspend fun ensureLoaded(
        modelId: String,
        acceleratorMode: AcceleratorMode
    ): Result<TtsEngine> = mutex.withLock {
        val current = activeEngine
        if (current != null && current.isLoaded && current.modelId == modelId) {
            return@withLock Result.success(current)
        }
        loadModelLocked(modelId, acceleratorMode)
    }

    suspend fun switchModel(
        modelId: String,
        acceleratorMode: AcceleratorMode
    ): Result<TtsEngine> = mutex.withLock {
        loadModelLocked(modelId, acceleratorMode)
    }

    private suspend fun loadModelLocked(
        modelId: String,
        acceleratorMode: AcceleratorMode
    ): Result<TtsEngine> {
        // Strictly release any previously loaded model so two voice models never occupy RAM together,
        // and immediately clear runtimeState so UI never displays a released model as still active.
        releaseActiveLocked()

        val spec = ModelCatalog.findById(modelId)
            ?: return Result.failure(IllegalArgumentException("نموذج غير معروف: $modelId").also {
                _runtimeState.value = EngineRuntimeState(lastError = it.message)
            })

        val compat = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(spec, context)
        if (!compat.isSupported) {
            val msg = compat.detailsAr ?: compat.summaryAr
            _runtimeState.value = EngineRuntimeState(lastError = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val modelDir = storageManager.getInstalledModelDir(modelId)
        if (!verifyPackageReady(spec)) {
            val msg = "النموذج ${spec.displayName} غير مثبّت أو فشل فحص سلامة الملفات (SHA-256)."
            _runtimeState.value = EngineRuntimeState(lastError = msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (spec.requiresEspeakData && packageVerifier == null &&
            !storageManager.verifyInstalledPackage(ModelCatalog.espeakNgDataSpec, verifyIntegrity = true)
        ) {
            val msg = "بيانات eSpeak-ng المرافقة مفقودة أو تالفة. أعد تنزيل النموذج من شاشة النماذج."
            _runtimeState.value = EngineRuntimeState(lastError = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val ramBefore = MemoryDiagnostics.getProcessPssMb(context)
        _runtimeState.value = _runtimeState.value.copy(
            isLoading = true,
            ramBeforeLoadMb = ramBefore,
            lastError = null
        )

        val engine = createEngine(spec)

        return try {
            val initResult = engine.initialize(spec, modelDir, acceleratorMode)
            if (initResult.isSuccess) {
                activeEngine = engine
                val ramAfter = MemoryDiagnostics.getProcessPssMb(context)
                val loadMs = initResult.getOrDefault(0L)
                _runtimeState.value = EngineRuntimeState(
                    loadedModelId = spec.id,
                    loadedModelName = spec.displayName,
                    engineType = spec.engineType,
                    acceleratorLabel = engine.activeAccelerator,
                    fellBackToCpu = engine.fellBackToCpu,
                    fallbackReason = engine.fallbackReason,
                    lastLoadTimeMs = loadMs,
                    ramBeforeLoadMb = ramBefore,
                    ramAfterLoadMb = ramAfter,
                    ramPeakSynthesisMb = ramAfter,
                    isLoading = false,
                    lastError = null
                )
                Result.success(engine)
            } else {
                runCatching { engine.release() }
                val cause = initResult.exceptionOrNull()
                val err = if (cause is OutOfMemoryError || cause?.cause is OutOfMemoryError) {
                    "نفدت الذاكرة المتاحة أثناء تحميل ${spec.displayName}. أغلق التطبيقات الخلفية أو استخدم Piper الخفيف."
                } else {
                    cause?.message ?: "فشل تحميل النموذج"
                }
                _runtimeState.value = EngineRuntimeState(
                    isLoading = false,
                    ramBeforeLoadMb = ramBefore,
                    lastError = err
                )
                Result.failure(cause ?: IllegalStateException(err))
            }
        } catch (oom: OutOfMemoryError) {
            runCatching { engine.release() }
            val msg = "نفدت الذاكرة المتاحة (OOM) أثناء تحميل ${spec.displayName}. أغلق التطبيقات الخلفية أو بدّل إلى Piper."
            _runtimeState.value = EngineRuntimeState(
                isLoading = false,
                ramBeforeLoadMb = ramBefore,
                lastError = msg
            )
            Result.failure(IllegalStateException(msg, oom))
        }
    }

    suspend fun synthesizeSentence(
        modelId: String,
        acceleratorMode: AcceleratorMode,
        spokenText: String,
        voiceId: String,
        speed: Float,
        languageCode: String = "fr"
    ): Result<TtsSynthesisResult> = mutex.withLock {
        val engine = if (activeEngine != null && activeEngine!!.isLoaded && activeEngine!!.modelId == modelId) {
            activeEngine!!
        } else {
            val loaded = loadModelLocked(modelId, acceleratorMode)
            if (loaded.isFailure) return@withLock Result.failure(loaded.exceptionOrNull()!!)
            loaded.getOrThrow()
        }

        val spec = ModelCatalog.findById(modelId)
        val locale = spec?.localeFor(languageCode) ?: "fr-FR"
        val resolvedVoice = voiceId.ifBlank { spec?.defaultVoiceFor(languageCode) ?: "ff_siwis" }

        return@withLock try {
            onTtsActivityCallback?.invoke()
            val res = engine.synthesize(spokenText, resolvedVoice, speed, locale)
            onTtsActivityCallback?.invoke()
            val peakRam = MemoryDiagnostics.getProcessPssMb(context)
            val synth = res.getOrNull()
            _runtimeState.value = _runtimeState.value.copy(
                acceleratorLabel = synth?.usedAccelerator ?: engine.activeAccelerator,
                fellBackToCpu = synth?.fellBackToCpu ?: engine.fellBackToCpu,
                fallbackReason = synth?.fallbackReason ?: engine.fallbackReason,
                ramPeakSynthesisMb = maxOf(_runtimeState.value.ramPeakSynthesisMb, peakRam),
                lastError = res.exceptionOrNull()?.message
            )
            res
        } catch (oom: OutOfMemoryError) {
            val msg = "نفدت الذاكرة المتاحة أثناء النطق. تم تفريغ المحرك لحماية التطبيق."
            releaseActiveLocked(errorMessage = msg)
            Result.failure(IllegalStateException(msg, oom))
        }
    }

    /**
     * True chunk-by-chunk audio streaming flow serialized safely per engine instance.
     */
    fun synthesizeSentenceStream(
        modelId: String,
        acceleratorMode: AcceleratorMode,
        spokenText: String,
        languageCode: String = "fr",
        voiceId: String = "",
        speed: Float = 1.0f
    ): Flow<TtsStreamChunk> = channelFlow {
        mutex.withLock {
            val engine = if (activeEngine != null && activeEngine!!.isLoaded && activeEngine!!.modelId == modelId) {
                activeEngine!!
            } else {
                loadModelLocked(modelId, acceleratorMode).getOrThrow()
            }

            val spec = ModelCatalog.findById(modelId)
            val locale = spec?.localeFor(languageCode) ?: "fr-FR"
            val resolvedVoice = voiceId.ifBlank { spec?.defaultVoiceFor(languageCode) ?: "ff_siwis" }

            try {
                engine.synthesizeStream(spokenText, resolvedVoice, speed, locale).collect { chunk ->
                    send(chunk)
                    val peakRam = MemoryDiagnostics.getProcessPssMb(context)
                    _runtimeState.value = _runtimeState.value.copy(
                        acceleratorLabel = engine.activeAccelerator,
                        fellBackToCpu = engine.fellBackToCpu,
                        fallbackReason = engine.fallbackReason,
                        ramPeakSynthesisMb = maxOf(_runtimeState.value.ramPeakSynthesisMb, peakRam)
                    )
                }
            } catch (oom: OutOfMemoryError) {
                val msg = "نفدت الذاكرة المتاحة أثناء البث الصوتي. تم تفريغ المحرك لحماية التطبيق."
                releaseActiveLocked(errorMessage = msg)
                throw IllegalStateException(msg, oom)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _runtimeState.value = _runtimeState.value.copy(
                    acceleratorLabel = engine.activeAccelerator,
                    fellBackToCpu = engine.fellBackToCpu,
                    fallbackReason = engine.fallbackReason,
                    lastError = t.message ?: "تعذر بث الجملة صوتياً"
                )
                throw t
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Runs a real load + short synthesis verification test right after download/extraction
     * before marking a model as permanently installed.
     */
    suspend fun verifyModelLoadable(spec: ModelSpec, extractedDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val compat = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(spec, context)
                if (!compat.isSupported) {
                    error(compat.detailsAr ?: compat.summaryAr)
                }

                // Evict any currently loaded model BEFORE allocating verification buffers and keep runtimeState accurate
                releaseActiveLocked()

                val testEngine = createEngine(spec)
                try {
                    val initRes = testEngine.initialize(spec, extractedDir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK)
                    initRes.getOrThrow()
                    val synRes = testEngine.synthesize(
                        spokenText = "Bonjour.",
                        voiceId = spec.defaultVoiceFor("fr"),
                        speed = 1.0f,
                        locale = spec.localeFor("fr")
                    )
                    val audio = synRes.getOrThrow()
                    if (audio.samples.isEmpty()) {
                        error("النموذج لم ينتج أي عينات صوتية في اختبار التحقق.")
                    }
                } catch (oom: OutOfMemoryError) {
                    val msg = "نفدت الذاكرة أثناء اختبار تحميل ${spec.displayName}. أغلق التطبيقات الخلفية أو استخدم Piper."
                    _runtimeState.value = EngineRuntimeState(lastError = msg)
                    throw IllegalStateException(msg, oom)
                } finally {
                    runCatching { testEngine.cancel() }
                    runCatching { testEngine.release() }
                }
            }.onFailure { err ->
                if (_runtimeState.value.loadedModelId != null || _runtimeState.value.lastError.isNullOrBlank()) {
                    _runtimeState.value = EngineRuntimeState(
                        lastError = err.message ?: "فشل اختبار تحميل ${spec.displayName}."
                    )
                }
            }
        }
    }

    suspend fun runBenchmarkItem(
        spec: ModelSpec,
        item: BenchmarkItem,
        acceleratorMode: AcceleratorMode,
        speedFactor: Float
    ): Result<BenchmarkReportEntity> = mutex.withLock {
        runCatching {
            val ramBefore = MemoryDiagnostics.getProcessPssMb(context)
            val loadRes = loadModelLocked(spec.id, acceleratorMode)
            val engine = loadRes.getOrThrow()
            val ramAfterLoad = MemoryDiagnostics.getProcessPssMb(context)
            val loadTimeMs = _runtimeState.value.lastLoadTimeMs

            val sentences = SpeechTextProcessor.splitAndPreprocess(item.frenchText, "fr")
            val allSamples = mutableListOf<Float>()
            var sampleRate = engine.sampleRateHz
            var firstChunkTimeMs = -1L
            val totalStartMs = SystemClock.elapsedRealtime()
            var peakRam = ramAfterLoad
            val voice = spec.defaultVoiceFor("fr")
            val locale = spec.localeFor("fr")

            try {
                for (s in sentences) {
                    var emittedFirst = false
                    engine.synthesizeStream(s.spokenText, voice, speedFactor, locale).collect { chunk ->
                        if (!emittedFirst && firstChunkTimeMs < 0L) {
                            firstChunkTimeMs = SystemClock.elapsedRealtime() - totalStartMs
                            emittedFirst = true
                        }
                        sampleRate = chunk.sampleRateHz
                        chunk.samples.forEach { allSamples.add(it) }
                        peakRam = maxOf(peakRam, MemoryDiagnostics.getProcessPssMb(context))
                    }
                }
            } catch (oom: OutOfMemoryError) {
                val msg = "نفدت الذاكرة المتاحة أثناء الاختبار المرجعي لنموذج ${spec.displayName}."
                releaseActiveLocked(errorMessage = msg)
                throw IllegalStateException(msg, oom)
            }
            val totalSynthMs = (SystemClock.elapsedRealtime() - totalStartMs).coerceAtLeast(1L)
            val pcmArray = allSamples.toFloatArray()
            val audioDurationMs = ((pcmArray.size.toDouble() / sampleRate.toDouble()) * 1000.0).toLong().coerceAtLeast(1L)
            val rtf = totalSynthMs.toFloat() / audioDurationMs.toFloat()

            val wavFile = File(
                storageManager.savedSamplesRoot,
                "${spec.id}_${item.id}_${System.currentTimeMillis()}.wav"
            )
            WavFileWriter.writeFloatMonoWav(wavFile, pcmArray, sampleRate)

            val hw = MemoryDiagnostics.getDeviceHardwareInfo(context)
            val note = buildString {
                append("Sentences=${sentences.size}, Samples=${pcmArray.size}, SR=${sampleRate}Hz")
                if (engine.fellBackToCpu && !engine.fallbackReason.isNullOrBlank()) {
                    append(" | Fallback: ${engine.fallbackReason}")
                }
            }

            BenchmarkReportEntity(
                modelId = spec.id,
                modelDisplayName = spec.displayName,
                engineType = spec.engineType.displayName,
                acceleratorUsed = engine.activeAccelerator,
                fellBackToCpu = engine.fellBackToCpu,
                corpusItemId = item.id,
                corpusCategory = item.categoryAr,
                speedFactor = speedFactor,
                modelLoadTimeMs = loadTimeMs,
                ttfaMs = firstChunkTimeMs.coerceAtLeast(1L),
                totalSynthesisMs = totalSynthMs,
                audioDurationMs = audioDurationMs,
                realTimeFactor = rtf,
                ramBeforeLoadMb = ramBefore,
                ramAfterLoadMb = ramAfterLoad,
                ramPeakSynthesisMb = peakRam,
                deviceModel = hw.deviceModel,
                socOrAbi = hw.primaryAbi,
                totalRamMb = hw.totalRamMb,
                androidVersion = "Android ${hw.androidVersion} (API ${hw.sdkInt})",
                wavSamplePath = wavFile.absolutePath,
                notes = note
            )
        }
    }

    fun cancelActiveSynthesis() {
        activeEngine?.cancel()
    }

    suspend fun releaseActiveEngine() {
        mutex.withLock {
            releaseActiveLocked()
        }
    }
}

object WavFileWriter {
    fun writeFloatMonoWav(outFile: File, samples: FloatArray, sampleRateHz: Int) {
        val pcm16 = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val dataBytes = pcm16.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // Mono
        header.putInt(sampleRateHz)
        header.putInt(sampleRateHz * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        val pcmBuffer = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm16.forEach { pcmBuffer.putShort(it) }

        FileOutputStream(outFile).use { fos ->
            fos.write(header.array())
            fos.write(pcmBuffer.array())
        }
    }
}
