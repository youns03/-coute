package com.example.tts

import android.os.SystemClock
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.TtsSynthesisResult
import com.example.models.ModelStorageManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PiperSherpaTtsEngine(
    private val storageManager: ModelStorageManager
) : TtsEngine {

    override val engineType: EngineType = EngineType.PIPER_SHERPA_ONNX
    override var modelId: String = ""
        private set
    override var sampleRateHz: Int = 22050
        private set
    override val isLoaded: Boolean
        get() = offlineTts != null
    override var activeAccelerator: String = "UNINITIALIZED"
        private set
    override var fellBackToCpu: Boolean = false
        private set
    override var fallbackReason: String? = null
        private set

    private var offlineTts: OfflineTts? = null

    @Volatile
    private var cancelled: Boolean = false

    override suspend fun initialize(
        spec: ModelSpec,
        modelDir: File,
        acceleratorMode: AcceleratorMode
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            release()
            val (onnxFile, tokensFile, espeakDir) = storageManager.resolvePiperPaths(modelDir)
                ?: error("ملفات Piper ONNX أو espeak-ng-data غير مكتملة في ${modelDir.absolutePath}")
            modelId = spec.id
            val startMs = SystemClock.elapsedRealtime()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = onnxFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDir.absolutePath
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            )
            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                maxNumSentences = 1
            )
            val created = OfflineTts(assetManager = null, config = ttsConfig)
            sampleRateHz = created.sampleRate().takeIf { it > 0 } ?: 22050
            offlineTts = created
            if (acceleratorMode == AcceleratorMode.GPU_ONLY) {
                activeAccelerator = "CPU (ONNX VITS)"
                fellBackToCpu = true
                fallbackReason = "Piper VITS via Sherpa-ONNX runs on CPU provider"
            } else {
                activeAccelerator = "CPU ($threads threads)"
                fellBackToCpu = false
                fallbackReason = null
            }
            SystemClock.elapsedRealtime() - startMs
        }
    }

    override suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float
    ): Result<TtsSynthesisResult> = withContext(Dispatchers.IO) {
        runCatching {
            val engine = offlineTts ?: error("محرك Sherpa-ONNX غير مهيأ.")
            cancelled = false
            val startMs = SystemClock.elapsedRealtime()
            val generated = engine.generate(
                text = spokenText,
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.0f)
            )
            if (cancelled) {
                error("تم إلغاء التوليد")
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val rate = generated.sampleRate.takeIf { it > 0 } ?: sampleRateHz
            sampleRateHz = rate
            TtsSynthesisResult(
                samples = generated.samples,
                sampleRateHz = rate,
                wordTimestamps = emptyList(), // Piper VITS does not provide word-level timestamps
                synthesisTimeMs = elapsedMs,
                usedAccelerator = activeAccelerator,
                fellBackToCpu = fellBackToCpu,
                fallbackReason = fallbackReason
            )
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun release() {
        runCatching { offlineTts?.release() }
        offlineTts = null
        activeAccelerator = "RELEASED"
    }
}
