package com.example.tts

import android.os.SystemClock
import com.example.contracts.ModelStorageGateway
import com.example.contracts.TtsEngine
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.ModelSpec
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

internal interface PiperNativeSession {
    val sampleRateHz: Int
    fun generate(text: String, sid: Int, speed: Float): FloatArray
    fun generateWithCallback(text: String, sid: Int, speed: Float, callback: (FloatArray) -> Int)
    fun release()
}

internal fun interface PiperSessionFactory {
    fun create(onnxFile: File, tokensFile: File, espeakDir: File, threads: Int): PiperNativeSession
}

private object DefaultPiperSessionFactory : PiperSessionFactory {
    override fun create(
        onnxFile: File,
        tokensFile: File,
        espeakDir: File,
        threads: Int
    ): PiperNativeSession {
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
        return object : PiperNativeSession {
            override val sampleRateHz: Int
                get() = created.sampleRate().takeIf { it in 8000..96000 } ?: 22050

            override fun generate(text: String, sid: Int, speed: Float): FloatArray {
                return created.generate(text = text, sid = sid, speed = speed).samples
            }

            override fun generateWithCallback(
                text: String,
                sid: Int,
                speed: Float,
                callback: (FloatArray) -> Int
            ) {
                created.generateWithCallback(text = text, sid = sid, speed = speed, callback = callback)
            }

            override fun release() {
                created.release()
            }
        }
    }
}

class PiperSherpaTtsEngine internal constructor(
    private val storageManager: ModelStorageGateway,
    private val sessionFactory: PiperSessionFactory
) : TtsEngine {

    constructor(storageManager: ModelStorageGateway) : this(
        storageManager = storageManager,
        sessionFactory = DefaultPiperSessionFactory
    )

    override val engineType: EngineType = EngineType.PIPER_SHERPA_ONNX
    override var modelId: String = ""
        private set
    override var sampleRateHz: Int = 22050
        private set
    override val isLoaded: Boolean
        get() = session != null
    override var activeAccelerator: String = "UNINITIALIZED"
        private set
    override var fellBackToCpu: Boolean = false
        private set
    override var fallbackReason: String? = null
        private set

    private var session: PiperNativeSession? = null
    private var activeSpec: ModelSpec? = null

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
            activeSpec = spec
            val startMs = SystemClock.elapsedRealtime()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val created = sessionFactory.create(onnxFile, tokensFile, espeakDir, threads)
            sampleRateHz = created.sampleRateHz.takeIf { it in 8000..96000 } ?: 22050
            session = created
            if (acceleratorMode == AcceleratorMode.GPU_ONLY) {
                activeAccelerator = "CPU فقط (ONNX VITS)"
                fellBackToCpu = true
                fallbackReason = "نموذج Piper يعمل على CPU دائماً (لا يدعم GPU)"
            } else {
                activeAccelerator = "CPU ($threads threads)"
                fellBackToCpu = false
                fallbackReason = null
            }
            SystemClock.elapsedRealtime() - startMs
        }
    }

    private fun validateLocaleSupported(locale: String) {
        val langCode = if (locale.startsWith("en", ignoreCase = true)) "en" else "fr"
        val spec = activeSpec
        if (spec != null && !spec.supportsLanguage(langCode)) {
            error("نموذج ${spec.displayName} مخصّص للفرنسية فقط ولا يدعم قراءة الإنجليزية. فعّل Kokoro-82M للكتب الإنجليزية.")
        }
    }

    override suspend fun synthesize(
        spokenText: String,
        voiceId: String,
        speed: Float,
        locale: String
    ): Result<TtsSynthesisResult> = withContext(Dispatchers.IO) {
        val activeContext = currentCoroutineContext()
        val res = runCatching {
            validateLocaleSupported(locale)
            val engine = session ?: error("محرك Sherpa-ONNX غير مهيأ.")
            cancelled = false
            val startMs = SystemClock.elapsedRealtime()
            val chunks = mutableListOf<FloatArray>()
            var totalSamples = 0
            // Piper VITS single-speaker models always encode their speaker in the ONNX weights (sid = 0)
            engine.generateWithCallback(
                text = spokenText,
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.0f)
            ) { chunk ->
                if (cancelled || !activeContext.isActive) {
                    return@generateWithCallback 0
                }
                if (chunk.isNotEmpty()) {
                    val copy = chunk.copyOf()
                    chunks.add(copy)
                    totalSamples += copy.size
                }
                1
            }
            if (!activeContext.isActive) {
                throw CancellationException("تم إلغاء التوليد")
            }
            if (cancelled) {
                error("تم إلغاء التوليد")
            }
            val rawSamples = if (chunks.size == 1) {
                chunks[0]
            } else {
                FloatArray(totalSamples).also { merged ->
                    var offset = 0
                    for (seg in chunks) {
                        seg.copyInto(merged, offset)
                        offset += seg.size
                    }
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val rate = engine.sampleRateHz.takeIf { it in 8000..96000 } ?: sampleRateHz
            sampleRateHz = rate
            TtsSynthesisResult(
                samples = sanitizeSamples(rawSamples),
                sampleRateHz = rate,
                wordTimestamps = emptyList(),
                synthesisTimeMs = elapsedMs,
                usedAccelerator = activeAccelerator,
                fellBackToCpu = fellBackToCpu,
                fallbackReason = fallbackReason
            )
        }
        val err = res.exceptionOrNull()
        if (err is CancellationException) throw err
        res
    }

    override fun synthesizeStream(
        spokenText: String,
        voiceId: String,
        speed: Float,
        locale: String
    ): Flow<TtsStreamChunk> = callbackFlow {
        validateLocaleSupported(locale)
        val engine = session ?: throw IllegalStateException("محرك Sherpa-ONNX غير مهيأ.")
        cancelled = false
        val rate = engine.sampleRateHz.takeIf { it in 8000..96000 } ?: sampleRateHz
        sampleRateHz = rate

        var totalEmittedSamples = 0
        try {
            engine.generateWithCallback(
                text = spokenText,
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.0f)
            ) { chunk ->
                if (cancelled) return@generateWithCallback 0
                val clean = sanitizeSamples(chunk)
                if (clean.isNotEmpty()) {
                    totalEmittedSamples += clean.size
                    val sendResult = trySendBlocking(
                        TtsStreamChunk(
                            samples = clean,
                            sampleRateHz = rate,
                            wordTimestamps = emptyList(),
                            isFinal = false
                        )
                    )
                    if (sendResult.isFailure) return@generateWithCallback 0
                }
                1
            }
            if (!cancelled && totalEmittedSamples > 0) {
                trySendBlocking(
                    TtsStreamChunk(
                        samples = FloatArray(0),
                        sampleRateHz = rate,
                        wordTimestamps = emptyList(),
                        isFinal = true
                    )
                )
            }
            close()
        } catch (t: Throwable) {
            close(t)
        }
        awaitClose { cancelled = true }
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
        cancelled = true
    }

    override fun release() {
        cancelled = true
        runCatching { session?.release() }
        session = null
        activeAccelerator = "RELEASED"
    }
}
