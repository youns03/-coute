package com.example.tts

import android.content.Context
import android.os.SystemClock
import com.example.contracts.ModelCatalogProvider
import com.example.contracts.ModelStorageGateway
import com.example.contracts.TtsEngine
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.MemoryDiagnostics
import com.example.domain.ModelSpec
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.domain.WordTimestampRange
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

internal data class KokoroSynthesisOutput(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val wordTimestamps: List<WordTimestampRange>,
    val isFinal: Boolean = true
)

internal interface KokoroNativeSession {
    fun synthesize(spokenText: String, voiceId: String, locale: String): KokoroSynthesisOutput
    fun synthesizeStream(spokenText: String, voiceId: String, locale: String): Flow<KokoroSynthesisOutput>
    fun cancel()
    fun close()
}

internal fun interface KokoroSessionFactory {
    fun create(
        modelFile: File,
        cacheDir: File,
        delegate: Delegate,
        voiceId: String,
        locale: String,
        speakingRate: Float
    ): KokoroNativeSession
}

private object DefaultKokoroSessionFactory : KokoroSessionFactory {
    override fun create(
        modelFile: File,
        cacheDir: File,
        delegate: Delegate,
        voiceId: String,
        locale: String,
        speakingRate: Float
    ): KokoroNativeSession {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = TextToSpeechOptions(
            baseOptions = baseOptions,
            defaultVoiceId = voiceId,
            defaultLocale = locale,
            speakingRate = speakingRate.coerceIn(0.5f, 2.0f),
            cacheDirectory = cacheDir.absolutePath
        )
        val delegateTts = TextToSpeech.create(options)
        return object : KokoroNativeSession {
            override fun synthesize(
                spokenText: String,
                voiceId: String,
                locale: String
            ): KokoroSynthesisOutput {
                val res = delegateTts.synthesize(
                    spokenText,
                    SynthesisOptions(voiceId = voiceId, locale = locale)
                )
                return KokoroSynthesisOutput(
                    samples = res.samples,
                    sampleRateHz = res.sampleRateHz,
                    wordTimestamps = res.wordTimestamps.map {
                        WordTimestampRange(
                            word = it.word,
                            startChar = it.startChar,
                            endChar = it.endChar,
                            startSec = it.startSec,
                            endSec = it.endSec
                        )
                    },
                    isFinal = true
                )
            }

            override fun synthesizeStream(
                spokenText: String,
                voiceId: String,
                locale: String
            ): Flow<KokoroSynthesisOutput> {
                return delegateTts.synthesizeStream(
                    spokenText,
                    SynthesisOptions(voiceId = voiceId, locale = locale)
                ).map { chunk ->
                    KokoroSynthesisOutput(
                        samples = chunk.samples,
                        sampleRateHz = chunk.sampleRateHz,
                        wordTimestamps = chunk.wordTimestamps.map {
                            WordTimestampRange(
                                word = it.word,
                                startChar = it.startChar,
                                endChar = it.endChar,
                                startSec = it.startSec,
                                endSec = it.endSec
                            )
                        },
                        isFinal = chunk.isFinalChunk
                    )
                }
            }

            override fun cancel() {
                delegateTts.cancel()
            }

            override fun close() {
                delegateTts.close()
            }
        }
    }
}

class KokoroLiteRtTtsEngine internal constructor(
    private val context: Context,
    private val storageManager: ModelStorageGateway,
    private val modelCatalog: ModelCatalogProvider,
    private val sessionFactory: KokoroSessionFactory
) : TtsEngine {

    constructor(
        context: Context,
        storageManager: ModelStorageGateway,
        modelCatalog: ModelCatalogProvider
    ) : this(
        context = context,
        storageManager = storageManager,
        modelCatalog = modelCatalog,
        sessionFactory = DefaultKokoroSessionFactory
    )

    override val engineType: EngineType = EngineType.KOKORO_LITERT
    override var modelId: String = ""
        private set
    override var sampleRateHz: Int = 24000
        private set
    override val isLoaded: Boolean
        get() = session != null
    override var activeAccelerator: String = "UNINITIALIZED"
        private set
    override var fellBackToCpu: Boolean = false
        private set
    override var fallbackReason: String? = null
        private set

    private var session: KokoroNativeSession? = null
    private var defaultVoiceId: String = "ff_siwis"
    private var configuredVoiceId: String = "ff_siwis"
    private var configuredLocale: String = "fr-FR"
    private var configuredSpeakingRate: Float = 1.0f
    private var activeSpec: ModelSpec? = null
    private var activeModelDir: File? = null
    private var activeModelFile: File? = null
    private var activeCacheDir: File? = null
    private var activeDelegate: Delegate = Delegate.CPU
    private var activeAcceleratorMode: AcceleratorMode = AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK
    private var cpuFallbackExhausted: Boolean = false

    override suspend fun initialize(
        spec: ModelSpec,
        modelDir: File,
        acceleratorMode: AcceleratorMode
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            release()
            val primaryModelFile = storageManager.resolveKokoroModelFile(modelDir)
                ?: error("ملف Kokoro .litertlm غير موجود في ${modelDir.absolutePath}")
            val cacheDir = File(context.cacheDir, "kokoro_delegate_cache").apply { mkdirs() }
            modelId = spec.id
            defaultVoiceId = spec.voiceId
            configuredVoiceId = spec.voiceId
            configuredLocale = spec.localeFor("fr")
            configuredSpeakingRate = 1.0f
            activeSpec = spec
            activeModelDir = modelDir
            activeCacheDir = cacheDir
            activeAcceleratorMode = acceleratorMode
            cpuFallbackExhausted = false

            val startMs = SystemClock.elapsedRealtime()
            val isCpuVariant = spec.id.contains("cpu", ignoreCase = true)

            when {
                acceleratorMode == AcceleratorMode.CPU_ONLY || isCpuVariant -> {
                    activeModelFile = primaryModelFile
                    activeDelegate = Delegate.CPU
                    session = createWithDelegate(primaryModelFile, cacheDir, Delegate.CPU, spec.voiceId, configuredLocale, 1.0f)
                    activeAccelerator = "CPU (LiteRT)"
                    fellBackToCpu = false
                    fallbackReason = null
                    cpuFallbackExhausted = true
                }
                acceleratorMode == AcceleratorMode.GPU_ONLY -> {
                    activeModelFile = primaryModelFile
                    activeDelegate = Delegate.GPU
                    session = createWithDelegate(primaryModelFile, cacheDir, Delegate.GPU, spec.voiceId, configuredLocale, 1.0f)
                    activeAccelerator = "GPU (OpenCL FP16)"
                    fellBackToCpu = false
                    fallbackReason = null
                    cpuFallbackExhausted = true
                }
                else -> {
                    val gpuAttempt = runCatching {
                        createWithDelegate(primaryModelFile, cacheDir, Delegate.GPU, spec.voiceId, configuredLocale, 1.0f)
                    }
                    if (gpuAttempt.isSuccess) {
                        activeModelFile = primaryModelFile
                        activeDelegate = Delegate.GPU
                        session = gpuAttempt.getOrThrow()
                        activeAccelerator = "GPU (OpenCL FP16)"
                        fellBackToCpu = false
                        fallbackReason = null
                        cpuFallbackExhausted = false
                    } else {
                        val reason = gpuAttempt.exceptionOrNull()?.message ?: "GPU init failed"
                        switchToCpuFallback(
                            primaryDir = modelDir,
                            cacheDir = cacheDir,
                            voiceId = spec.voiceId,
                            locale = configuredLocale,
                            speakingRate = 1.0f,
                            reason = "Init: $reason"
                        )
                    }
                }
            }
            SystemClock.elapsedRealtime() - startMs
        }
    }

    private fun resolveCpuFallbackModelFile(primaryDir: File): Pair<File, Boolean> {
        val cpuSpec = modelCatalog.findById("kokoro-82m-cpu-fp16")
        if (cpuSpec != null &&
            MemoryDiagnostics.evaluateRuntimeMemoryForLoad(cpuSpec, context).isSupported &&
            storageManager.verifyInstalledPackage(cpuSpec, verifyIntegrity = true)
        ) {
            val cpuFile = storageManager.resolveKokoroModelFile(storageManager.getInstalledModelDir(cpuSpec.id))
            if (cpuFile != null) return cpuFile to true
        }
        val currentFile = activeModelFile ?: storageManager.resolveKokoroModelFile(primaryDir)
            ?: error("ملف Kokoro .litertlm غير موجود.")
        return currentFile to false
    }

    private fun switchToCpuFallback(
        primaryDir: File,
        cacheDir: File,
        voiceId: String,
        locale: String,
        speakingRate: Float,
        reason: String
    ) {
        runCatching { session?.close() }
        session = null
        val (cpuFile, isDedicatedCpuModel) = resolveCpuFallbackModelFile(primaryDir)
        val clampedRate = speakingRate.coerceIn(0.5f, 2.0f)
        session = try {
            createWithDelegate(cpuFile, cacheDir, Delegate.CPU, voiceId, locale, clampedRate)
        } catch (oom: OutOfMemoryError) {
            activeAccelerator = "ERROR (OOM)"
            throw IllegalStateException("نفدت الذاكرة المتاحة أثناء التحويل الاحتياطي إلى CPU لنموذج Kokoro.", oom)
        }
        activeModelFile = cpuFile
        activeDelegate = Delegate.CPU
        configuredVoiceId = voiceId
        configuredLocale = locale
        configuredSpeakingRate = clampedRate
        cpuFallbackExhausted = true
        fellBackToCpu = true
        activeAccelerator = if (isDedicatedCpuModel) {
            "CPU (Dedicated CPU Model Fallback)"
        } else {
            "CPU Delegate (GPU Model Fallback)"
        }
        fallbackReason = if (isDedicatedCpuModel) {
            reason
        } else {
            "$reason (يُفضَّل تثبيت نسخة CPU المخصصة لأفضل استقرار على هذا المعالج)"
        }
    }

    private fun createWithDelegate(
        modelFile: File,
        cacheDir: File,
        delegate: Delegate,
        voiceId: String,
        locale: String,
        speakingRate: Float
    ): KokoroNativeSession {
        return sessionFactory.create(
            modelFile = modelFile,
            cacheDir = cacheDir,
            delegate = delegate,
            voiceId = voiceId,
            locale = locale,
            speakingRate = speakingRate.coerceIn(0.5f, 2.0f)
        )
    }

    private fun ensureSessionConfigured(voiceId: String, locale: String, targetRate: Float) {
        val needsRecreate = abs(targetRate - configuredSpeakingRate) > 0.02f ||
            !locale.equals(configuredLocale, ignoreCase = true) ||
            !voiceId.equals(configuredVoiceId, ignoreCase = true)
        if (needsRecreate) {
            val modelFile = activeModelFile
            val cacheDir = activeCacheDir
            if (modelFile != null && cacheDir != null) {
                runCatching { session?.close() }
                session = null
                session = createWithDelegate(modelFile, cacheDir, activeDelegate, voiceId, locale, targetRate)
                configuredVoiceId = voiceId
                configuredLocale = locale
                configuredSpeakingRate = targetRate
            }
        }
    }

    override suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float,
        locale: String
    ): Result<TtsSynthesisResult> = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveLocale = locale.ifBlank { "fr-FR" }
            val effectiveVoice = voiceId.ifBlank {
                if (effectiveLocale.startsWith("en", ignoreCase = true)) "af_heart" else defaultVoiceId
            }
            val targetRate = speed.coerceIn(0.5f, 2.0f)

            val startMs = SystemClock.elapsedRealtime()
            val result = try {
                ensureSessionConfigured(effectiveVoice, effectiveLocale, targetRate)
                val engine = session ?: error("محرك Kokoro غير مهيأ.")
                engine.synthesize(spokenText, effectiveVoice, effectiveLocale)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val modelDir = activeModelDir
                val cacheDir = activeCacheDir
                if (activeDelegate == Delegate.GPU &&
                    activeAcceleratorMode == AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK &&
                    !cpuFallbackExhausted &&
                    modelDir != null &&
                    cacheDir != null
                ) {
                    switchToCpuFallback(
                        primaryDir = modelDir,
                        cacheDir = cacheDir,
                        voiceId = effectiveVoice,
                        locale = effectiveLocale,
                        speakingRate = targetRate,
                        reason = "Runtime GPU synthesis error: ${t.message ?: t::class.java.simpleName}"
                    )
                    val cpuEngine = session ?: error("فشل التهيئة الاحتياطية على CPU.")
                    cpuEngine.synthesize(spokenText, effectiveVoice, effectiveLocale)
                } else {
                    throw t
                }
            }

            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            sampleRateHz = result.sampleRateHz.takeIf { it in 8000..96000 } ?: 24000
            TtsSynthesisResult(
                samples = sanitizeSamples(result.samples),
                sampleRateHz = sampleRateHz,
                wordTimestamps = result.wordTimestamps,
                synthesisTimeMs = elapsedMs,
                usedAccelerator = activeAccelerator,
                fellBackToCpu = fellBackToCpu,
                fallbackReason = fallbackReason
            )
        }
    }

    override fun synthesizeStream(
        spokenText: String,
        voiceId: String,
        speed: Float,
        locale: String
    ): Flow<TtsStreamChunk> = flow {
        val effectiveLocale = locale.ifBlank { "fr-FR" }
        val effectiveVoice = voiceId.ifBlank {
            if (effectiveLocale.startsWith("en", ignoreCase = true)) "af_heart" else defaultVoiceId
        }
        val targetRate = speed.coerceIn(0.5f, 2.0f)

        var emittedAnyAudio = false
        try {
            ensureSessionConfigured(effectiveVoice, effectiveLocale, targetRate)
            val engine = session ?: error("محرك Kokoro غير مهيأ.")
            engine.synthesizeStream(spokenText, effectiveVoice, effectiveLocale).collect { rawChunk ->
                val sr = rawChunk.sampleRateHz.takeIf { it in 8000..96000 } ?: sampleRateHz
                sampleRateHz = sr
                val clean = sanitizeSamples(rawChunk.samples)
                if (clean.isNotEmpty() || rawChunk.isFinal) {
                    if (clean.isNotEmpty()) emittedAnyAudio = true
                    emit(
                        TtsStreamChunk(
                            samples = clean,
                            sampleRateHz = sr,
                            wordTimestamps = rawChunk.wordTimestamps,
                            isFinal = rawChunk.isFinal
                        )
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val modelDir = activeModelDir
            val cacheDir = activeCacheDir
            if (!emittedAnyAudio &&
                activeDelegate == Delegate.GPU &&
                activeAcceleratorMode == AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK &&
                !cpuFallbackExhausted &&
                modelDir != null &&
                cacheDir != null
            ) {
                switchToCpuFallback(
                    primaryDir = modelDir,
                    cacheDir = cacheDir,
                    voiceId = effectiveVoice,
                    locale = effectiveLocale,
                    speakingRate = targetRate,
                    reason = "Runtime GPU streaming error: ${t.message ?: t::class.java.simpleName}"
                )
                val cpuEngine = session ?: error("فشل التهيئة الاحتياطية على CPU.")
                emitAll(
                    cpuEngine.synthesizeStream(spokenText, effectiveVoice, effectiveLocale).map { rawChunk ->
                        val sr = rawChunk.sampleRateHz.takeIf { it in 8000..96000 } ?: sampleRateHz
                        sampleRateHz = sr
                        TtsStreamChunk(
                            samples = sanitizeSamples(rawChunk.samples),
                            sampleRateHz = sr,
                            wordTimestamps = rawChunk.wordTimestamps,
                            isFinal = rawChunk.isFinal
                        )
                    }
                )
            } else {
                throw t
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun sanitizeSamples(raw: FloatArray): FloatArray {
        var needsCopy = false
        for (s in raw) {
            if (!s.isFinite() || s < -1f || s > 1f) {
                needsCopy = true
                break
            }
        }
        if (!needsCopy) return raw
        return FloatArray(raw.size) { i ->
            val v = raw[i]
            if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
        }
    }

    override fun cancel() {
        runCatching { session?.cancel() }
    }

    override fun release() {
        runCatching { session?.close() }
        session = null
        activeAccelerator = "RELEASED"
    }
}
