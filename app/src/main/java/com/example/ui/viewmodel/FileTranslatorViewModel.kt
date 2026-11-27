package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobEntity
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.PdfExtractionMode
import com.example.translation.ExportMode
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    private val _importQueueState = MutableStateFlow(DocumentImportQueueState())
    val importQueueState: StateFlow<DocumentImportQueueState> = _importQueueState.asStateFlow()
    private var activeImportJob: Job? = null

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

    fun inspectFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_importQueueState.value.isImporting) return
        activeImportJob?.cancel()
        activeImportJob = viewModelScope.launch {
            _importQueueState.value = DocumentImportQueueState(
                isVisible = true,
                isInspecting = true,
                totalCount = uris.size
            )
            try {
                val previews = repo.inspectImportUris(uris)
                _importQueueState.update {
                    it.copy(
                        isInspecting = false,
                        inspectedFiles = previews,
                        totalCount = previews.count { p -> p.isSupported }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importQueueState.update {
                    it.copy(
                        isInspecting = false,
                        errorMessage = e.message ?: "تعذر فحص الملفات المحددة."
                    )
                }
            }
        }
    }

    fun setPdfExtractionMode(mode: PdfExtractionMode) {
        _importQueueState.update { it.copy(pdfMode = mode, errorMessage = null) }
    }

    fun removeQueuedFile(uri: Uri) {
        if (_importQueueState.value.isImporting) return
        _importQueueState.update { current ->
            val remaining = current.inspectedFiles.filterNot { it.uri == uri }
            if (remaining.isEmpty()) DocumentImportQueueState()
            else current.copy(
                inspectedFiles = remaining,
                totalCount = remaining.count { it.isSupported },
                errorMessage = null
            )
        }
    }

    fun startQueuedTranslationImports(useAutoDetect: Boolean = false) {
        val snapshot = _importQueueState.value
        val valid = snapshot.supportedFiles
        if (valid.isEmpty()) {
            _importQueueState.update { it.copy(errorMessage = "لا توجد ملفات صالحة للترجمة.") }
            return
        }
        if (snapshot.isImporting) return

        activeImportJob = viewModelScope.launch {
            _isImporting.value = true
            _statusBanner.value = null
            _importQueueState.update {
                it.copy(
                    isImporting = true,
                    totalCount = valid.size,
                    currentIndex = 0,
                    currentFileProgress = 0.02f,
                    errorMessage = null
                )
            }
            val createdJobs = mutableListOf<TranslationJobEntity>()
            val failures = mutableListOf<String>()
            val options = DocumentExtractionOptions(pdfMode = _importQueueState.value.pdfMode)
            val override = if (useAutoDetect) null else _selectedSourceLanguage.value

            try {
                valid.forEachIndexed { index, preview ->
                    ensureActive()
                    _importQueueState.update {
                        it.copy(
                            currentIndex = index,
                            activeFileName = preview.fileName,
                            activeStageLabel = "جاري تحليل المستند...",
                            currentFileProgress = 0.04f
                        )
                    }
                    val res = repo.createJobFromUri(
                        uri = preview.uri,
                        selectedSourceLanguage = override,
                        options = options,
                        onProgress = { ev ->
                            _importQueueState.update {
                                it.copy(
                                    activeFileName = ev.fileName,
                                    activeStageLabel = ev.stageLabelAr,
                                    currentFileProgress = ev.progress
                                )
                            }
                        }
                    )
                    res.onSuccess { job ->
                        createdJobs.add(job)
                    }.onFailure { err ->
                        failures.add("${preview.fileName}: ${err.message ?: "تعذر التحضير"}")
                    }
                }

                _importQueueState.value = DocumentImportQueueState()
                if (createdJobs.isNotEmpty()) {
                    _previewJobId.value = createdJobs.last().id
                }
                _statusBanner.value = when {
                    createdJobs.size == 1 && failures.isEmpty() -> {
                        val job = createdJobs.first()
                        "تم تجهيز «${job.title}» (${job.totalChunks} جزءاً) — يمكنك بدء الترجمة الآن."
                    }
                    createdJobs.isNotEmpty() && failures.isEmpty() -> {
                        "تم تجهيز ${createdJobs.size} ملفات للترجمة بنجاح."
                    }
                    createdJobs.isNotEmpty() -> {
                        "تم تجهيز ${createdJobs.size} ملفات • تعذر ${failures.size}: ${failures.first()}"
                    }
                    else -> {
                        failures.firstOrNull() ?: "تعذر استيراد الملفات للترجمة."
                    }
                }
            } catch (e: CancellationException) {
                _importQueueState.value = DocumentImportQueueState()
                _statusBanner.value = "تم إلغاء الاستيراد وتنظيف الملفات المؤقتة."
                throw e
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun cancelQueuedImport() {
        activeImportJob?.cancel()
        activeImportJob = null
        _isImporting.value = false
        _importQueueState.value = DocumentImportQueueState()
    }

    fun dismissImportQueue() {
        if (_importQueueState.value.isImporting || _importQueueState.value.isInspecting) {
            cancelQueuedImport()
        } else {
            _importQueueState.value = DocumentImportQueueState()
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
