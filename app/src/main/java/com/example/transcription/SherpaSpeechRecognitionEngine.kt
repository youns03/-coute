package com.example.transcription

import android.content.Context
import com.example.contracts.AsrResolvedFiles
import com.example.contracts.ModelStorageGateway
import com.example.contracts.SpeechRecognitionEngine
import com.example.domain.MemoryDiagnostics
import com.example.domain.ModelSpec
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

interface OfflineAsrSession {
    fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String
    fun release()
}

class SherpaSpeechRecognitionEngine internal constructor(
    private val context: Context,
    private val storageManager: ModelStorageGateway,
    private val sessionFactory: ((ModelSpec, AsrResolvedFiles, String) -> OfflineAsrSession)? = null
) : SpeechRecognitionEngine {

    constructor(
        context: Context,
        storageManager: ModelStorageGateway
    ) : this(
        context = context,
        storageManager = storageManager,
        sessionFactory = null
    )

    private val mutex = Mutex()

    @Volatile
    private var activeSession: OfflineAsrSession? = null

    @Volatile
    private var loadedModelKey: String? = null

    @Volatile
    private var _loadedModelId: String? = null

    override val isLoaded: Boolean
        get() = activeSession != null

    override val loadedModelId: String?
        get() = _loadedModelId

    override suspend fun verifyLoadable(spec: ModelSpec, extractedDir: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val compat = MemoryDiagnostics.evaluateRuntimeMemoryForLoad(spec, context)
                    if (!compat.isSupported) error(compat.detailsAr ?: compat.summaryAr)

                    val paths = storageManager.resolveAsrPaths(extractedDir)
                        ?: error("ملفات نموذج التعرف على الكلام غير مكتملة داخل الحزمة.")

                    releaseLocked()
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

    override suspend fun ensureLoaded(spec: ModelSpec, languageCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val key = "${spec.id}:$languageCode"
                    if (activeSession != null && loadedModelKey == key) {
                        return@runCatching
                    }
                    releaseLocked()
                    val modelDir = storageManager.getInstalledModelDir(spec.id)
                    val paths = storageManager.resolveAsrPaths(modelDir)
                        ?: error("ملفات نموذج ${spec.displayName} مفقودة.")
                    val created = createAsrSession(spec, paths, languageCode)
                    activeSession = created
                    loadedModelKey = key
                    _loadedModelId = spec.id
                }
            }
        }

    override suspend fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val session = activeSession ?: error("محرك التفريغ الصوتي غير محمّل حالياً.")
                session.transcribeSegment(samples16k, sampleRateHz)
            }
        }

    override suspend fun release() {
        mutex.withLock {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        runCatching { activeSession?.release() }
        activeSession = null
        loadedModelKey = null
        _loadedModelId = null
    }

    private fun createAsrSession(
        spec: ModelSpec,
        paths: AsrResolvedFiles,
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
}
