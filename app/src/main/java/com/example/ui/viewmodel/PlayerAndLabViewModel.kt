package com.example.ui.viewmodel

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.audio.PlaybackUiState
import com.example.data.BenchmarkReportEntity
import com.example.data.UserSettings
import com.example.domain.BenchmarkCorpus
import com.example.domain.BenchmarkItem
import com.example.models.ModelCatalog
import com.example.models.ModelUiEntry
import com.example.tts.EngineRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BenchmarkLabUiState(
    val isRunning: Boolean = false,
    val currentStepLabel: String = "",
    val lastError: String? = null,
    val playingSamplePath: String? = null
)

class PlayerAndLabViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val playbackController = app.playbackController
    private val ttsEngineManager = app.ttsEngineManager
    private val benchmarkDao = app.database.benchmarkReportDao()
    private val settingsRepo = app.settingsRepository

    val playbackState: StateFlow<PlaybackUiState> = playbackController.state
    val engineRuntimeState: StateFlow<EngineRuntimeState> = ttsEngineManager.runtimeState
    val settings: StateFlow<UserSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val installedModels: StateFlow<List<ModelUiEntry>> = app.modelRepository
        .observeModels(settingsRepo.settingsFlow.map { it.activeModelId })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val benchmarkReports: StateFlow<List<BenchmarkReportEntity>> = benchmarkDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _labState = MutableStateFlow(BenchmarkLabUiState())
    val labState: StateFlow<BenchmarkLabUiState> = _labState.asStateFlow()

    private var wavPlayJob: Job? = null

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun stop() = playbackController.stop()
    fun nextSentence() = playbackController.nextSentence()
    fun previousSentence() = playbackController.previousSentence()
    fun replaySentence() = playbackController.replayCurrentSentence()
    fun toggleRepeatSentence() = playbackController.toggleRepeatCurrentSentence()
    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)

    fun runSingleBenchmark(modelId: String, item: BenchmarkItem, speed: Float = 1.0f) {
        if (_labState.value.isRunning) return
        val spec = ModelCatalog.findById(modelId) ?: return
        viewModelScope.launch {
            playbackController.stop()
            _labState.value = BenchmarkLabUiState(
                isRunning = true,
                currentStepLabel = "جاري اختبار ${spec.displayName} على: ${item.categoryAr}"
            )
            val res = ttsEngineManager.runBenchmarkItem(
                spec = spec,
                item = item,
                acceleratorMode = settings.value.acceleratorMode,
                speedFactor = speed
            )
            res.onSuccess { report ->
                benchmarkDao.insert(report)
                _labState.value = BenchmarkLabUiState(isRunning = false)
            }.onFailure { err ->
                _labState.value = BenchmarkLabUiState(
                    isRunning = false,
                    lastError = err.message ?: "فشل تشغيل الاختبار المرجعي"
                )
            }
        }
    }

    fun runFullSuiteOnInstalledModels() {
        if (_labState.value.isRunning) return
        viewModelScope.launch {
            playbackController.stop()
            val readyModels = ModelCatalog.ttsModels.filter {
                app.storageManager.verifyInstalledPackage(it, verifyIntegrity = true) &&
                    com.example.tts.MemoryDiagnostics.evaluateModelCompatibility(it, app).isSupported
            }
            if (readyModels.isEmpty()) {
                _labState.value = BenchmarkLabUiState(
                    lastError = "لا يوجد أي نموذج صوتي مثبت بعد. نزّل Kokoro أو Piper من إدارة النماذج أولاً."
                )
                return@launch
            }
            _labState.value = BenchmarkLabUiState(isRunning = true, currentStepLabel = "بدء اختبار المرحلة صفر...")
            for (spec in readyModels) {
                for (item in BenchmarkCorpus.items) {
                    _labState.value = BenchmarkLabUiState(
                        isRunning = true,
                        currentStepLabel = "${spec.displayName} — ${item.categoryFr}"
                    )
                    val res = ttsEngineManager.runBenchmarkItem(
                        spec = spec,
                        item = item,
                        acceleratorMode = settings.value.acceleratorMode,
                        speedFactor = 1.0f
                    )
                    res.onSuccess { benchmarkDao.insert(it) }
                        .onFailure { err ->
                            _labState.value = _labState.value.copy(lastError = "${spec.id}: ${err.message}")
                        }
                }
            }
            _labState.value = _labState.value.copy(isRunning = false, currentStepLabel = "")
        }
    }

    fun playSavedWavSample(path: String) {
        wavPlayJob?.cancel()
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            _labState.value = _labState.value.copy(lastError = "ملف العينة الصوتية غير موجود.")
            return
        }
        wavPlayJob = viewModelScope.launch {
            _labState.value = _labState.value.copy(playingSamplePath = path, lastError = null)
            try {
                withContext(Dispatchers.IO) {
                    val activeContext = currentCoroutineContext()
                    FileInputStream(file).use { fis ->
                        val headerBytes = ByteArray(44)
                        var headerRead = 0
                        while (headerRead < 44) {
                            val r = fis.read(headerBytes, headerRead, 44 - headerRead)
                            if (r <= 0) break
                            headerRead += r
                        }
                        if (headerRead < 44) error("ترويسة ملف WAV غير مكتملة.")
                        val headerStr = String(headerBytes, 0, 4, Charsets.US_ASCII)
                        val waveStr = String(headerBytes, 8, 4, Charsets.US_ASCII)
                        if (headerStr != "RIFF" || waveStr != "WAVE") {
                            error("تنسيق WAV غير صالح.")
                        }
                        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = headerBuf.getShort(20).toInt()
                        val channels = headerBuf.getShort(22).toInt()
                        val sampleRate = headerBuf.getInt(24)
                        val bitsPerSample = headerBuf.getShort(34).toInt()
                        if (audioFormat != 1 || channels !in 1..2 || bitsPerSample != 16 || sampleRate !in 8000..96000) {
                            error("مواصفات PCM غير مدعومة ($sampleRate Hz / $channels ch / ${bitsPerSample}bit).")
                        }
                        val channelMask = if (channels == 1) {
                            AudioFormat.CHANNEL_OUT_MONO
                        } else {
                            AudioFormat.CHANNEL_OUT_STEREO
                        }
                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT
                        ).coerceAtLeast(4096)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelMask)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        try {
                            if (track.state != AudioTrack.STATE_INITIALIZED) {
                                error("تعذر تهيئة مخرج الصوت للعينة.")
                            }
                            track.play()
                            val chunk = ByteArray(16 * 1024)
                            while (activeContext.isActive) {
                                val read = fis.read(chunk)
                                if (read <= 0) break
                                var off = 0
                                while (off < read && activeContext.isActive) {
                                    val written = track.write(chunk, off, read - off, AudioTrack.WRITE_BLOCKING)
                                    if (written <= 0) break
                                    off += written
                                }
                            }
                            runCatching {
                                track.pause()
                                track.flush()
                                track.stop()
                            }
                        } finally {
                            runCatching { track.release() }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _labState.value = _labState.value.copy(
                    lastError = t.message ?: "تعذر تشغيل ملف العينة"
                )
            } finally {
                withContext(NonCancellable) {
                    if (_labState.value.playingSamplePath == path) {
                        _labState.value = _labState.value.copy(playingSamplePath = null)
                    }
                }
            }
        }
    }

    fun clearBenchmarkReports() {
        viewModelScope.launch {
            benchmarkDao.clearAll()
        }
    }

    fun releaseEngineFromRam() {
        viewModelScope.launch {
            playbackController.stop()
            ttsEngineManager.releaseActiveEngine()
        }
    }
}
