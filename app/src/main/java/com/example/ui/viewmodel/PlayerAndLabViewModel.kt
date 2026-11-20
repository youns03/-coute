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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
        .observeModels(kotlinx.coroutines.flow.flowOf(""))
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
            val readyModels = ModelCatalog.ttsModels.filter { app.storageManager.verifyInstalledPackage(it) }
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
        if (!file.exists()) return
        wavPlayJob = viewModelScope.launch {
            _labState.value = _labState.value.copy(playingSamplePath = path)
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = FileInputStream(file).use { it.readBytes() }
                    if (bytes.size <= 44) return@runCatching
                    val headerBuf = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
                    val sampleRate = headerBuf.getInt(24)
                    val pcmLen = bytes.size - 44
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
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
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    try {
                        track.play()
                        track.write(bytes, 44, pcmLen)
                        track.stop()
                    } finally {
                        track.release()
                    }
                }
            }
            _labState.value = _labState.value.copy(playingSamplePath = null)
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
