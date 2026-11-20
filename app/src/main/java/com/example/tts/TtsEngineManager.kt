package com.example.tts

import android.content.Context
import android.os.SystemClock
import com.example.data.BenchmarkReportEntity
import com.example.domain.AcceleratorMode
import com.example.domain.BenchmarkItem
import com.example.domain.EngineType
import com.example.domain.FrenchTextPreprocessor
import com.example.domain.ModelSpec
import com.example.domain.TtsSynthesisResult
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class TtsEngineManager(
    private val context: Context,
    private val storageManager: ModelStorageManager
) {
    private val mutex = Mutex()
    private var activeEngine: TtsEngine? = null

    private val _runtimeState = MutableStateFlow(EngineRuntimeState())
    val runtimeState: StateFlow<EngineRuntimeState> = _runtimeState.asStateFlow()

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
        // Strictly release any previously loaded model so two voice models never occupy RAM together
        activeEngine?.let { prev ->
            prev.cancel()
            prev.release()
        }
        activeEngine = null

        val spec = ModelCatalog.findById(modelId)
            ?: return Result.failure(IllegalArgumentException("نموذج غير معروف: $modelId"))
        val modelDir = storageManager.getInstalledModelDir(modelId)
        if (!storageManager.verifyInstalledPackage(spec)) {
            _runtimeState.value = EngineRuntimeState(
                lastError = "النموذج ${spec.displayName} غير مثبت أو ملفاته ناقصة."
            )
            return Result.failure(IllegalStateException("النموذج غير مثبت: ${spec.displayName}"))
        }

        val ramBefore = MemoryDiagnostics.getProcessPssMb(context)
        _runtimeState.value = _runtimeState.value.copy(
            isLoading = true,
            ramBeforeLoadMb = ramBefore,
            lastError = null
        )

        val engine: TtsEngine = when (spec.engineType) {
            EngineType.KOKORO_LITERT -> KokoroLiteRtTtsEngine(context, storageManager)
            EngineType.PIPER_SHERPA_ONNX -> PiperSherpaTtsEngine(storageManager)
        }

        val initResult = engine.initialize(spec, modelDir, acceleratorMode)
        return if (initResult.isSuccess) {
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
            engine.release()
            val err = initResult.exceptionOrNull()?.message ?: "فشل تحميل النموذج"
            _runtimeState.value = EngineRuntimeState(
                isLoading = false,
                ramBeforeLoadMb = ramBefore,
                lastError = err
            )
            Result.failure(initResult.exceptionOrNull() ?: IllegalStateException(err))
        }
    }

    suspend fun synthesizeSentence(
        modelId: String,
        acceleratorMode: AcceleratorMode,
        spokenText: String,
        voiceId: String,
        speed: Float
    ): Result<TtsSynthesisResult> = mutex.withLock {
        val engine = if (activeEngine != null && activeEngine!!.isLoaded && activeEngine!!.modelId == modelId) {
            activeEngine!!
        } else {
            val loaded = loadModelLocked(modelId, acceleratorMode)
            if (loaded.isFailure) return@withLock Result.failure(loaded.exceptionOrNull()!!)
            loaded.getOrThrow()
        }

        val res = engine.synthesize(spokenText, voiceId, speed)
        val peakRam = MemoryDiagnostics.getProcessPssMb(context)
        _runtimeState.value = _runtimeState.value.copy(
            ramPeakSynthesisMb = maxOf(_runtimeState.value.ramPeakSynthesisMb, peakRam)
        )
        res
    }

    /**
     * Runs a real load + short synthesis verification test right after download/extraction
     * before marking a model as permanently installed.
     */
    suspend fun verifyModelLoadable(spec: ModelSpec, extractedDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val previous = activeEngine
                previous?.cancel()
                previous?.release()
                activeEngine = null

                val testEngine: TtsEngine = when (spec.engineType) {
                    EngineType.KOKORO_LITERT -> KokoroLiteRtTtsEngine(context, storageManager)
                    EngineType.PIPER_SHERPA_ONNX -> PiperSherpaTtsEngine(storageManager)
                }
                try {
                    val initRes = testEngine.initialize(spec, extractedDir, AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK)
                    initRes.getOrThrow()
                    val synRes = testEngine.synthesize("Bonjour.", spec.voiceId, 1.0f)
                    val audio = synRes.getOrThrow()
                    if (audio.samples.isEmpty()) {
                        error("النموذج لم ينتج أي عينات صوتية في اختبار التحقق.")
                    }
                } finally {
                    testEngine.release()
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

            val sentences = FrenchTextPreprocessor.splitAndPreprocess(item.frenchText)
            val allSamples = mutableListOf<Float>()
            var sampleRate = engine.sampleRateHz
            var firstChunkTimeMs = -1L
            val totalStartMs = SystemClock.elapsedRealtime()
            var peakRam = ramAfterLoad

            for (s in sentences) {
                val res = engine.synthesize(s.spokenText, spec.voiceId, speedFactor).getOrThrow()
                if (firstChunkTimeMs < 0L) {
                    firstChunkTimeMs = SystemClock.elapsedRealtime() - totalStartMs
                }
                sampleRate = res.sampleRateHz
                res.samples.forEach { allSamples.add(it) }
                peakRam = maxOf(peakRam, MemoryDiagnostics.getProcessPssMb(context))
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
            activeEngine?.cancel()
            activeEngine?.release()
            activeEngine = null
            _runtimeState.value = EngineRuntimeState()
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
