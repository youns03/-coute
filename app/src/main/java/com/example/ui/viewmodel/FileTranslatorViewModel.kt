package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobEntity
import com.example.translation.ExportMode
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FileTranslatorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val repo = app.fileTranslationRepository
    private val engineManager = app.translationEngineManager

    private val _selectedSourceLanguage = MutableStateFlow(SourceLanguage.FRENCH)
    val selectedSourceLanguage: StateFlow<SourceLanguage> = _selectedSourceLanguage.asStateFlow()

    private val _previewJobId = MutableStateFlow<String?>(null)
    val previewJobId: StateFlow<String?> = _previewJobId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    val translationManagerState: StateFlow<TranslationManagerUiState> = engineManager.state

    val jobs: StateFlow<List<TranslationJobEntity>> = repo.observeAllJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val previewChunks: StateFlow<List<TranslationChunkEntity>> = _previewJobId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeChunks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshPackStatuses() {
        viewModelScope.launch { engineManager.refreshAllStatuses() }
    }

    fun selectSourceLanguage(lang: SourceLanguage) {
        _selectedSourceLanguage.value = lang
    }

    fun importFile(uri: Uri, useAutoDetect: Boolean = false) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusBanner.value = null
            val override = if (useAutoDetect) null else _selectedSourceLanguage.value
            val res = repo.createJobFromUri(uri, override)
            _isImporting.value = false
            res.onSuccess { job ->
                _previewJobId.value = job.id
                _statusBanner.value = "تم تجهيز «${job.title}» (${job.totalChunks} جزءاً) — يمكنك بدء الترجمة الآن."
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "تعذر استيراد الملف للترجمة."
            }
        }
    }

    fun startOrResumeJob(job: TranslationJobEntity) {
        viewModelScope.launch {
            val lang = SourceLanguage.fromCode(job.sourceLanguage)
            val direction = TranslationDirection.forSource(lang)
            val packReady = engineManager.isDirectionReady(direction)
            if (!packReady) {
                _statusBanner.value = "يرجى تنزيل حزمة ${direction.displayNameAr} أولاً لتعمل الترجمة دون إنترنت."
                return@launch
            }
            repo.startOrResumeJob(job.id)
            _statusBanner.value = "بدأت ترجمة «${job.title}» في الخلفية — تستمر حتى عند مغادرة هذه الشاشة."
        }
    }

    fun pauseJob(jobId: String) {
        viewModelScope.launch {
            repo.pauseJob(jobId)
            _statusBanner.value = "تم إيقاف الترجمة مؤقتاً مع حفظ جميع الأجزاء المنجزة."
        }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            if (_previewJobId.value == jobId) _previewJobId.value = null
            repo.deleteJob(jobId)
        }
    }

    fun changeJobLanguage(jobId: String, lang: SourceLanguage) {
        viewModelScope.launch {
            val changed = repo.updateJobSourceLanguageIfEmpty(jobId, lang)
            if (!changed) {
                _statusBanner.value = "لا يمكن تبديل لغة مهمة تحتوي على أجزاء مترجمة دون إعادة ضبطها أولاً."
            }
        }
    }

    fun resetAndChangeJobLanguage(jobId: String, lang: SourceLanguage) {
        viewModelScope.launch {
            repo.resetAndChangeJobSourceLanguage(jobId, lang)
            _statusBanner.value = "تمت إعادة تهيئة المهمة إلى اللغة الجديدة (${lang.labelAr}) ومسح الترجمات السابقة بنجاح."
        }
    }

    fun openPreview(jobId: String) {
        _previewJobId.value = if (_previewJobId.value == jobId) null else jobId
    }

    fun closePreview() {
        _previewJobId.value = null
    }

    fun exportJob(jobId: String, targetUri: Uri, mode: ExportMode) {
        viewModelScope.launch {
            val res = repo.exportJobToUri(jobId, targetUri, mode)
            res.onSuccess { title ->
                _statusBanner.value = "تم تصدير ترجمة «$title» إلى ملف مستقل بنجاح دون تعديل الأصل."
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "تعذر تصدير الملف."
            }
        }
    }

    fun downloadPack(direction: TranslationDirection) {
        viewModelScope.launch {
            val state = engineManager.state.value.stateFor(direction)
            if (state.status == TranslationPackStatus.DOWNLOADING) return@launch
            engineManager.downloadPack(direction)
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }
}
