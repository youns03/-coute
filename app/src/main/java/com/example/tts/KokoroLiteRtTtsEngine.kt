package com.example.tts

import android.content.Context
import android.os.SystemClock
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.TtsSynthesisResult
import com.example.domain.WordTimestampRange
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class KokoroLiteRtTtsEngine(
    private val context: Context,
    private val storageManager: ModelStorageManager
) : TtsEngine {

    override val engineType: EngineType = EngineType.KOKORO_LITERT
    override var modelId: String = ""
        private set
    override var sampleRateHz: Int = 24000
        private set
    override val isLoaded: Boolean
        get() = tts != null
    override var activeAccelerator: String = "UNINITIALIZED"
        private set
    override var fellBackToCpu: Boolean = false
        private set
    override var fallbackReason: String? = null
        private set

    private var tts: TextToSpeech? = null
    private var defaultVoiceId: String = "ff_siwis"
    private var activeSpec: ModelSpec? = null
    private var activeModelDir: File? = null
    private var activeModelFile: File? = null
    private var activeCacheDir: File? = null
    private var activeDelegate: Delegate = Delegate.CPU
    private var activeAcceleratorMode: AcceleratorMode = AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK
    private var configuredSpeakingRate: Float = 1.0f
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
            activeSpec = spec
            activeModelDir = modelDir
            activeCacheDir = cacheDir
            activeAcceleratorMode = acceleratorMode
            configuredSpeakingRate = 1.0f
            cpuFallbackExhausted = false

            val startMs = SystemClock.elapsedRealtime()
            val isCpuVariant = spec.id.contains("cpu", ignoreCase = true)

            when {
                acceleratorMode == AcceleratorMode.CPU_ONLY || isCpuVariant -> {
                    activeModelFile = primaryModelFile
                    activeDelegate = Delegate.CPU
                    tts = createWithDelegate(primaryModelFile, cacheDir, Delegate.CPU, spec.voiceId, 1.0f)
                    activeAccelerator = "CPU (LiteRT)"
                    fellBackToCpu = false
                    fallbackReason = null
                    cpuFallbackExhausted = true
                }
                acceleratorMode == AcceleratorMode.GPU_ONLY -> {
                    activeModelFile = primaryModelFile
                    activeDelegate = Delegate.GPU
                    tts = createWithDelegate(primaryModelFile, cacheDir, Delegate.GPU, spec.voiceId, 1.0f)
                    activeAccelerator = "GPU (OpenCL FP16)"
                    fellBackToCpu = false
                    fallbackReason = null
                    cpuFallbackExhausted = true
                }
                else -> {
                    // AUTO_GPU_WITH_CPU_FALLBACK
                    val gpuAttempt = runCatching {
                        createWithDelegate(primaryModelFile, cacheDir, Delegate.GPU, spec.voiceId, 1.0f)
                    }
                    if (gpuAttempt.isSuccess) {
                        activeModelFile = primaryModelFile
                        activeDelegate = Delegate.GPU
                        tts = gpuAttempt.getOrThrow()
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
        val cpuSpec = ModelCatalog.findById("kokoro-82m-cpu-fp16")
        if (cpuSpec != null && storageManager.verifyInstalledPackage(cpuSpec)) {
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
        speakingRate: Float,
        reason: String
    ) {
        runCatching { tts?.close() }
        tts = null
        val (cpuFile, isDedicatedCpuModel) = resolveCpuFallbackModelFile(primaryDir)
        tts = createWithDelegate(cpuFile, cacheDir, Delegate.CPU, voiceId, speakingRate)
        activeModelFile = cpuFile
        activeDelegate = Delegate.CPU
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
        speakingRate: Float
    ): TextToSpeech {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = TextToSpeechOptions(
            baseOptions = baseOptions,
            defaultVoiceId = voiceId,
            defaultLocale = "fr-FR",
            speakingRate = speakingRate.coerceIn(0.5f, 2.0f),
            cacheDirectory = cacheDir.absolutePath
        )
        return TextToSpeech.create(options)
    }

    override suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float
    ): Result<TtsSynthesisResult> = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveVoice = voiceId.ifBlank { defaultVoiceId }
            val targetRate = speed.coerceIn(0.5f, 2.0f)

            // Apply speakingRate via TextToSpeechOptions when user/benchmark speed changes
            if (abs(targetRate - configuredSpeakingRate) > 0.02f) {
                val modelFile = activeModelFile
                val cacheDir = activeCacheDir
                if (modelFile != null && cacheDir != null) {
                    runCatching { tts?.close() }
                    tts = createWithDelegate(modelFile, cacheDir, activeDelegate, effectiveVoice, targetRate)
                    configuredSpeakingRate = targetRate
                }
            }

            val synthesisOptions = SynthesisOptions(
                voiceId = effectiveVoice,
                locale = "fr-FR"
            )

            val startMs = SystemClock.elapsedRealtime()
            val result = try {
                val engine = tts ?: error("محرك Kokoro غير مهيأ.")
                engine.synthesize(spokenText, synthesisOptions)
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
                        speakingRate = targetRate,
                        reason = "Runtime GPU synthesis error: ${t.message ?: t::class.java.simpleName}"
                    )
                    val cpuEngine = tts ?: error("فشل التهيئة الاحتياطية على CPU.")
                    cpuEngine.synthesize(spokenText, synthesisOptions)
                } else {
                    throw t
                }
            }

            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            sampleRateHz = result.sampleRateHz.takeIf { it > 0 } ?: 24000
            val timestamps = result.wordTimestamps.map {
                WordTimestampRange(
                    word = it.word,
                    startChar = it.startChar,
                    endChar = it.endChar,
                    startSec = it.startSec,
                    endSec = it.endSec
                )
            }
            TtsSynthesisResult(
                samples = result.samples,
                sampleRateHz = sampleRateHz,
                wordTimestamps = timestamps,
                synthesisTimeMs = elapsedMs,
                usedAccelerator = activeAccelerator,
                fellBackToCpu = fellBackToCpu,
                fallbackReason = fallbackReason
            )
        }
    }

    override fun cancel() {
        runCatching { tts?.cancel() }
    }

    override fun release() {
        runCatching { tts?.close() }
        tts = null
        activeAccelerator = "RELEASED"
    }
}
