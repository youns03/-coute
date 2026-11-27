package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.data.UserSettings
import com.example.domain.AcceleratorMode
import com.example.domain.ModelInstallStatus
import com.example.domain.SpeedControlMode
import com.example.domain.StorageBreakdown
import com.example.models.ModelUiEntry
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.tts.DeviceHardwareInfo
import com.example.tts.EngineRuntimeState
import com.example.tts.MemoryDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsAndSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val modelRepo = app.modelRepository
    private val settingsRepo = app.settingsRepository
    private val engineManager = app.translationEngineManager
    private val ttsEngineManager = app.ttsEngineManager

    val settings: StateFlow<UserSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val models: StateFlow<List<ModelUiEntry>> = modelRepo
        .observeAllModels(
            activeTtsIdFlow = settingsRepo.settingsFlow.map { it.activeModelId },
            activeAsrIdFlow = settingsRepo.settingsFlow.map { it.activeAsrModelId }
        )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val engineRuntimeState: StateFlow<EngineRuntimeState> = ttsEngineManager.runtimeState
    val memoryGovernorSnapshot: StateFlow<com.example.runtime.MemoryGovernorSnapshot> = app.memoryGovernor.snapshot
    val translationState: StateFlow<TranslationManagerUiState> = engineManager.state

    private val _storageBreakdown = MutableStateFlow(modelRepo.getStorageBreakdown())
    val storageBreakdown: StateFlow<StorageBreakdown> = _storageBreakdown.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    val hardwareInfo: DeviceHardwareInfo = MemoryDiagnostics.getDeviceHardwareInfo(application)

    fun refreshStorageAndTranslation() {
        _storageBreakdown.value = modelRepo.getStorageBreakdown()
        viewModelScope.launch {
            engineManager.refreshAllStatuses()
            app.memoryGovernor.evaluateAndEnforce()
        }
    }

    fun releaseIdleEnginesNow() {
        viewModelScope.launch {
            val pbStatus = app.playbackController.state.value.status
            if (pbStatus != com.example.audio.PlaybackStatus.PLAYING &&
                pbStatus != com.example.audio.PlaybackStatus.BUFFERING
            ) {
                ttsEngineManager.releaseActiveEngine()
            }
            if (!app.speechTranscriptionManager.isTranscribing) {
                app.speechTranscriptionManager.releaseActiveEngine()
            }
            app.memoryGovernor.evaluateAndEnforce()
            _actionMessage.value = "تم تحرير الذاكرة من المحركات الخاملة بنجاح."
        }
    }

    fun startOrResumeDownload(modelId: String) {
        val spec = com.example.models.ModelCatalog.findById(modelId)
        if (spec != null) {
            val compat = MemoryDiagnostics.evaluateModelCompatibility(spec, app)
            if (!compat.isSupported) {
                _actionMessage.value = compat.detailsAr ?: compat.summaryAr
                return
            }
        }
        modelRepo.startOrResumeDownload(modelId)
    }

    fun pauseDownload(modelId: String) {
        modelRepo.pauseDownload(modelId)
    }

    fun cancelDownload(modelId: String) {
        viewModelScope.launch {
            modelRepo.cancelAndDiscardDownload(modelId)
            _storageBreakdown.value = modelRepo.getStorageBreakdown()
        }
    }

    fun activateModel(entry: ModelUiEntry) {
        if (!entry.compatibility.isSupported) {
            _actionMessage.value = entry.compatibility.detailsAr ?: entry.compatibility.summaryAr
            return
        }
        if (entry.status != ModelInstallStatus.INSTALLED) {
            _actionMessage.value = "يجب تثبيت النموذج والتحقق منه أولاً قبل تفعيله."
            return
        }
        viewModelScope.launch {
            if (entry.spec.role == com.example.domain.ModelRole.ASR) {
                settingsRepo.setActiveAsrModelId(entry.spec.id)
                _actionMessage.value = "تم تعيين «${entry.spec.displayName}» كنموذج التعرف على الكلام النشط."
            } else {
                app.playbackController.stop()
                val res = ttsEngineManager.switchModel(entry.spec.id, settings.value.acceleratorMode)
                res.onSuccess {
                    settingsRepo.setActiveModelId(entry.spec.id)
                    settingsRepo.setActiveVoiceId("")
                    _actionMessage.value = "تم تفعيل وتحميل ${entry.spec.displayName} (${it.activeAccelerator})."
                }.onFailure { err ->
                    _actionMessage.value = "فشل تحميل النموذج (تم الإبقاء على الاختيار السابق): ${err.message}"
                }
            }
            _storageBreakdown.value = modelRepo.getStorageBreakdown()
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val spec = com.example.models.ModelCatalog.findById(modelId)
            val activeGuardId = if (spec?.role == com.example.domain.ModelRole.ASR) {
                if (app.speechTranscriptionManager.isTranscribing && settings.value.activeAsrModelId == modelId) {
                    modelId
                } else ""
            } else {
                settings.value.activeModelId
            }
            val res = modelRepo.deleteInstalledModel(modelId, activeGuardId)
            res.onSuccess {
                _actionMessage.value = "تم حذف النموذج وتحرير المساحة."
            }.onFailure { err ->
                _actionMessage.value = err.message
            }
            _storageBreakdown.value = modelRepo.getStorageBreakdown()
        }
    }

    fun setAcceleratorMode(mode: AcceleratorMode) {
        viewModelScope.launch {
            val loadedId = ttsEngineManager.runtimeState.value.loadedModelId
            if (loadedId != null) {
                app.playbackController.stop()
                val res = ttsEngineManager.switchModel(loadedId, mode)
                res.onSuccess {
                    settingsRepo.setAcceleratorMode(mode)
                }.onFailure { err ->
                    _actionMessage.value = "تعذر تطبيق وضع التسريع الجديد: ${err.message}"
                }
            } else {
                settingsRepo.setAcceleratorMode(mode)
            }
        }
    }

    fun setSpeedControlMode(mode: SpeedControlMode) {
        viewModelScope.launch { settingsRepo.setSpeedControlMode(mode) }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepo.setReaderFontSize(sizeSp) }
    }

    fun setLineHeight(mult: Float) {
        viewModelScope.launch { settingsRepo.setReaderLineHeight(mult) }
    }

    fun setNightMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setNightMode(enabled) }
    }

    fun downloadTranslationPack(direction: TranslationDirection) {
        viewModelScope.launch {
            engineManager.downloadPack(direction)
            _storageBreakdown.value = modelRepo.getStorageBreakdown()
        }
    }

    fun deleteTranslationPack(direction: TranslationDirection) {
        viewModelScope.launch {
            engineManager.deletePack(direction)
            _storageBreakdown.value = modelRepo.getStorageBreakdown()
        }
    }

    fun clearAudioCache() {
        modelRepo.clearAudioCache()
        _storageBreakdown.value = modelRepo.getStorageBreakdown()
        _actionMessage.value = "تم تنظيف ذاكرة الصوت المؤقتة."
    }

    fun dismissMessage() {
        _actionMessage.value = null
    }
}
