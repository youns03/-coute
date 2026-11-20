package com.example.tts

import android.content.Context
import android.os.SystemClock
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.TtsSynthesisResult
import com.example.domain.WordTimestampRange
import com.example.models.ModelStorageManager
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    override suspend fun initialize(
        spec: ModelSpec,
        modelDir: File,
        acceleratorMode: AcceleratorMode
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            release()
            val modelFile = storageManager.resolveKokoroModelFile(modelDir)
                ?: error("ملف Kokoro .litertlm غير موجود في ${modelDir.absolutePath}")
            val cacheDir = File(context.cacheDir, "kokoro_delegate_cache").apply { mkdirs() }
            modelId = spec.id
            defaultVoiceId = spec.voiceId

            val startMs = SystemClock.elapsedRealtime()
            val isCpuVariant = spec.id.contains("cpu", ignoreCase = true)

            when {
                acceleratorMode == AcceleratorMode.CPU_ONLY || isCpuVariant -> {
                    tts = createWithDelegate(modelFile, cacheDir, Delegate.CPU, spec.voiceId)
                    activeAccelerator = "CPU (LiteRT)"
                    fellBackToCpu = false
                    fallbackReason = null
                }
                acceleratorMode == AcceleratorMode.GPU_ONLY -> {
                    tts = createWithDelegate(modelFile, cacheDir, Delegate.GPU, spec.voiceId)
                    activeAccelerator = "GPU (OpenCL FP16)"
                    fellBackToCpu = false
                    fallbackReason = null
                }
                else -> {
                    // AUTO_GPU_WITH_CPU_FALLBACK
                    val gpuAttempt = runCatching {
                        createWithDelegate(modelFile, cacheDir, Delegate.GPU, spec.voiceId)
                    }
                    if (gpuAttempt.isSuccess) {
                        tts = gpuAttempt.getOrThrow()
                        activeAccelerator = "GPU (OpenCL FP16)"
                        fellBackToCpu = false
                        fallbackReason = null
                    } else {
                        val reason = gpuAttempt.exceptionOrNull()?.message ?: "GPU init failed"
                        tts = createWithDelegate(modelFile, cacheDir, Delegate.CPU, spec.voiceId)
                        activeAccelerator = "CPU (Fallback)"
                        fellBackToCpu = true
                        fallbackReason = reason
                    }
                }
            }
            SystemClock.elapsedRealtime() - startMs
        }
    }

    private fun createWithDelegate(
        modelFile: File,
        cacheDir: File,
        delegate: Delegate,
        voiceId: String
    ): TextToSpeech {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = TextToSpeechOptions(
            baseOptions = baseOptions,
            defaultVoiceId = voiceId,
            defaultLocale = "fr-FR",
            speakingRate = 1.0f,
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
            val engine = tts ?: error("محرك Kokoro غير مهيأ.")
            val effectiveVoice = voiceId.ifBlank { defaultVoiceId }
            val startMs = SystemClock.elapsedRealtime()
            val synthesisOptions = SynthesisOptions(
                voiceId = effectiveVoice,
                locale = "fr-FR"
            )
            val result = engine.synthesize(spokenText, synthesisOptions)
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
