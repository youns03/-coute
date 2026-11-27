package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.data.BookEntity
import com.example.data.LibraryRepository
import com.example.data.RecentActivityEntity
import com.example.data.VocabularyEntity
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.PdfExtractionMode
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelRole
import com.example.runtime.LocalTaskEntry
import com.example.transcription.TranscriptionRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL,
    BOOKS,
    AUDIO,
    TRANSLATED,
    IN_PROGRESS,
    COMPLETED
}

enum class LibrarySortOrder {
    RECENT,
    ADDED,
    NAME
}

enum class LibraryViewMode {
    GRID,
    LIST
}

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val allBooksCount: Int = 0,
    val bookProgress: Map<String, Float> = emptyMap(),
    val translatedBookIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.RECENT,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val isImporting: Boolean = false,
    val statusBanner: String? = null
)

data class HomeDashboardUiState(
    val continueReadingBook: BookEntity? = null,
    val continueReadingProgress: Float = 0f,
    val recentBooks: List<BookEntity> = emptyList(),
    val bookProgress: Map<String, Float> = emptyMap(),
    val translatedBookIds: Set<String> = emptySet(),
    val recentActivities: List<RecentActivityEntity> = emptyList(),
    val activeTasks: List<LocalTaskEntry> = emptyList(),
    val transcriptionState: TranscriptionRuntimeState = TranscriptionRuntimeState(),
    val readyAsrAvailable: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAndVocabViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val libraryRepo = app.libraryRepository
    private val settingsRepo = app.settingsRepository
    private val playbackController = app.playbackController
    private val transcriptionManager = app.speechTranscriptionManager
    private val taskCoordinator = app.taskCoordinator

    private val _bookSearchQuery = MutableStateFlow("")
    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    private val _isImporting = MutableStateFlow(false)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _importQueueState = MutableStateFlow(DocumentImportQueueState())
    val importQueueState: StateFlow<DocumentImportQueueState> = _importQueueState.asStateFlow()
    private var activeImportJob: Job? = null

    private val baseLibraryFlow = combine(
        libraryRepo.observeAllBooks(),
        libraryRepo.observeAllChapters(),
        libraryRepo.observeBookIdsWithTranslations(),
        settingsRepo.settingsFlow
    ) { books, allChapters, translatedIds, settings ->
        val chaptersByBook = allChapters.groupBy { it.bookId }
        val progressMap = books.associate { book ->
            book.id to LibraryRepository.calculateBookProgress(book, chaptersByBook[book.id].orEmpty())
        }
        BaseLibrarySnapshot(
            books = books,
            progressMap = progressMap,
            translatedIds = translatedIds.toSet(),
            activeBookId = settings.activeBookId,
            activeAsrModelId = settings.activeAsrModelId,
            sortOrder = LibrarySortOrder.entries.firstOrNull { it.name == settings.librarySortOrder }
                ?: LibrarySortOrder.RECENT,
            viewMode = LibraryViewMode.entries.firstOrNull { it.name == settings.libraryViewMode }
                ?: LibraryViewMode.GRID
        )
    }

    val libraryState: StateFlow<LibraryUiState> = combine(
        baseLibraryFlow,
        _bookSearchQuery,
        _libraryFilter,
        _isImporting,
        _statusBanner
    ) { base, query, filter, importing, banner ->
        val filteredByCategory = base.books.filter { book ->
            val progress = base.progressMap[book.id] ?: 0f
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.BOOKS -> book.mediaKind != "AUDIO"
                LibraryFilter.AUDIO -> book.mediaKind == "AUDIO"
                LibraryFilter.TRANSLATED -> book.id in base.translatedIds
                LibraryFilter.IN_PROGRESS -> !book.isCompleted && (progress > 0f || book.currentChapterIndex > 0 || book.currentSentenceIndex > 0)
                LibraryFilter.COMPLETED -> book.isCompleted || progress >= 0.999f
            }
        }
        val filteredBySearch = if (query.isBlank()) filteredByCategory else {
            filteredByCategory.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.author.contains(query, ignoreCase = true)
            }
        }
        val sorted = when (base.sortOrder) {
            LibrarySortOrder.RECENT -> filteredBySearch.sortedByDescending { it.lastReadAt }
            LibrarySortOrder.ADDED -> filteredBySearch.sortedByDescending { it.addedAt }
            LibrarySortOrder.NAME -> filteredBySearch.sortedBy { it.title.lowercase() }
        }
        LibraryUiState(
            books = sorted,
            allBooksCount = base.books.size,
            bookProgress = base.progressMap,
            translatedBookIds = base.translatedIds,
            searchQuery = query,
            filter = filter,
            sortOrder = base.sortOrder,
            viewMode = base.viewMode,
            isImporting = importing,
            statusBanner = banner
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    private val allModelsFlow = app.modelRepository.observeAllModels(
        activeTtsIdFlow = flowOf(""),
        activeAsrIdFlow = flowOf("")
    )

    val homeState: StateFlow<HomeDashboardUiState> = combine(
        baseLibraryFlow,
        libraryRepo.observeRecentActivities(),
        taskCoordinator.tasks,
        transcriptionManager.state,
        allModelsFlow
    ) { base, activities, tasks, asrRuntime, models ->
        val booksSorted = base.books.sortedByDescending { it.lastReadAt }
        val activeMatch = base.activeBookId?.let { id -> booksSorted.firstOrNull { it.id == id } }
        val continueBook = activeMatch
            ?: booksSorted.firstOrNull { !it.isCompleted && (base.progressMap[it.id] ?: 0f) > 0f }
            ?: booksSorted.firstOrNull()
        val continueProgress = continueBook?.let { base.progressMap[it.id] ?: 0f } ?: 0f
        val readyAsr = models.any {
            it.spec.role == ModelRole.ASR &&
                it.status == ModelInstallStatus.INSTALLED &&
                it.compatibility.isSupported
        }
        HomeDashboardUiState(
            continueReadingBook = continueBook,
            continueReadingProgress = continueProgress,
            recentBooks = booksSorted.take(10),
            bookProgress = base.progressMap,
            translatedBookIds = base.translatedIds,
            recentActivities = activities.take(6),
            activeTasks = tasks.take(5),
            transcriptionState = asrRuntime,
            readyAsrAvailable = readyAsr
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeDashboardUiState())

    private val _vocabQuery = MutableStateFlow("")
    val vocabQuery: StateFlow<String> = _vocabQuery.asStateFlow()

    val vocabularyList: StateFlow<List<VocabularyEntity>> = _vocabQuery
        .flatMapLatest { q -> libraryRepo.observeVocabulary(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBookSearchQuery(q: String) {
        _bookSearchQuery.value = q
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        _libraryFilter.value = filter
    }

    fun setLibrarySortOrder(order: LibrarySortOrder) {
        viewModelScope.launch { settingsRepo.setLibrarySortOrder(order.name) }
    }

    fun setLibraryViewMode(mode: LibraryViewMode) {
        viewModelScope.launch { settingsRepo.setLibraryViewMode(mode.name) }
    }

    fun toggleLibraryViewMode() {
        val current = libraryState.value.viewMode
        setLibraryViewMode(if (current == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID)
    }

    fun setVocabQuery(q: String) {
        _vocabQuery.value = q
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun importBook(uri: Uri, onImported: (String) -> Unit) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusBanner.value = null
            val res = libraryRepo.importBookChecked(uri)
            _isImporting.value = false
            res.onSuccess { outcome ->
                val warnSuffix = outcome.warningMessageAr?.let { " — $it" }.orEmpty()
                _statusBanner.value = if (outcome.wasDuplicate) {
                    "«${outcome.book.title}» موجود بالفعل في مكتبتك — تم فتح النسخة المحفوظة."
                } else {
                    "تم استيراد «${outcome.book.title}» (${outcome.book.totalSentences} جملة)$warnSuffix"
                }
                onImported(outcome.book.id)
            }.onFailure { err ->
                _statusBanner.value = "تعذر استيراد الملف: ${err.message ?: "خطأ غير معروف"}"
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
                val previews = libraryRepo.inspectImportUris(uris)
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

    fun startQueuedImports(onSingleBookImported: (String) -> Unit = {}) {
        val snapshot = _importQueueState.value
        val valid = snapshot.supportedFiles
        if (valid.isEmpty()) {
            _importQueueState.update { it.copy(errorMessage = "لا توجد ملفات صالحة للاستيراد.") }
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
            val importedOutcomes = mutableListOf<com.example.data.BookImportOutcome>()
            val failures = mutableListOf<String>()
            val options = DocumentExtractionOptions(pdfMode = _importQueueState.value.pdfMode)

            try {
                valid.forEachIndexed { index, preview ->
                    ensureActive()
                    _importQueueState.update {
                        it.copy(
                            currentIndex = index,
                            activeFileName = preview.fileName,
                            activeStageLabel = "جاري تجهيز الملف...",
                            currentFileProgress = 0.04f
                        )
                    }
                    val res = libraryRepo.importBookChecked(
                        uri = preview.uri,
                        options = options,
                        onProgress = { event ->
                            _importQueueState.update {
                                it.copy(
                                    activeFileName = event.fileName,
                                    activeStageLabel = event.stageLabelAr,
                                    currentFileProgress = event.progress
                                )
                            }
                        }
                    )
                    res.onSuccess { outcome ->
                        importedOutcomes.add(outcome)
                    }.onFailure { err ->
                        failures.add("${preview.fileName}: ${err.message ?: "خطأ غير معروف"}")
                    }
                }

                _importQueueState.value = DocumentImportQueueState()
                _statusBanner.value = buildLibraryQueueSummary(importedOutcomes, failures)
                if (importedOutcomes.size == 1 && failures.isEmpty()) {
                    onSingleBookImported(importedOutcomes.first().book.id)
                }
            } catch (e: CancellationException) {
                _importQueueState.value = DocumentImportQueueState()
                _statusBanner.value = "تم إلغاء عملية الاستيراد وتنظيف الملفات المؤقتة."
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

    private fun buildLibraryQueueSummary(
        outcomes: List<com.example.data.BookImportOutcome>,
        failures: List<String>
    ): String {
        if (outcomes.size == 1 && failures.isEmpty()) {
            val outcome = outcomes.first()
            val warnSuffix = outcome.warningMessageAr?.let { " — $it" }.orEmpty()
            return if (outcome.wasDuplicate) {
                "«${outcome.book.title}» موجود بالفعل في مكتبتك — تم فتح النسخة المحفوظة."
            } else {
                "تم استيراد «${outcome.book.title}» (${outcome.book.totalSentences} جملة)$warnSuffix"
            }
        }
        val newCount = outcomes.count { !it.wasDuplicate }
        val dupCount = outcomes.count { it.wasDuplicate }
        val parts = mutableListOf<String>()
        if (newCount > 0) parts.add("تم استيراد $newCount ملفاً بنجاح")
        if (dupCount > 0) parts.add("تخطي $dupCount مكرر")
        if (failures.isNotEmpty()) parts.add("فشل ${failures.size}: ${failures.first()}")
        val firstWarning = outcomes.firstNotNullOfOrNull { it.warningMessageAr }
        if (!firstWarning.isNullOrBlank()) parts.add(firstWarning)
        return parts.joinToString(" • ").ifBlank { "لم يتم استيراد أي ملف." }
    }

    fun importDirectText(
        title: String,
        body: String,
        languageCode: String,
        onImported: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusBanner.value = null
            val res = libraryRepo.importDirectText(title, body, languageCode)
            _isImporting.value = false
            res.onSuccess { outcome ->
                _statusBanner.value = if (outcome.wasDuplicate) {
                    "هذا النص موجود بالفعل باسم «${outcome.book.title}»."
                } else {
                    "تمت إضافة «${outcome.book.title}» إلى المكتبة (${outcome.book.totalSentences} جملة)."
                }
                onImported(outcome.book.id)
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "تعذر حفظ النص المضاف."
            }
        }
    }

    fun importAudioForTranscription(
        uri: Uri,
        languageCode: String,
        onCompletedBook: (String) -> Unit = {}
    ) {
        _statusBanner.value = "بدأ تفريغ الملف الصوتي محلياً عبر المحرك المركزي — يمكنك متابعة التقدم من الرئيسية."
        transcriptionManager.enqueueAudioImportAndTranscribe(
            uri = uri,
            languageCode = languageCode,
            onCompletedBook = { bookId ->
                _statusBanner.value = "اكتمل تفريغ الملف الصوتي وحفظه في مكتبتك."
                onCompletedBook(bookId)
            }
        )
    }

    fun renameBook(bookId: String, newTitle: String) {
        viewModelScope.launch {
            libraryRepo.renameBook(bookId, newTitle)
            _statusBanner.value = "تم تحديث اسم الكتاب."
        }
    }

    fun updateBookCoverTemplate(bookId: String, styleId: String) {
        viewModelScope.launch {
            libraryRepo.updateBookCoverTemplate(bookId, styleId)
            _statusBanner.value = "تم تحديث غلاف الكتاب."
        }
    }

    fun updateBookCoverFromUri(bookId: String, imageUri: Uri) {
        viewModelScope.launch {
            val res = libraryRepo.updateBookCoverFromUri(bookId, imageUri)
            res.onSuccess {
                _statusBanner.value = "تم تطبيق صورة الغلاف المخصصة."
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "تعذر حفظ صورة الغلاف."
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            if (playbackController.state.value.bookId == bookId) {
                playbackController.stop()
            }
            libraryRepo.deleteBook(bookId)
            _statusBanner.value = "تم حذف العنصر من المكتبة."
        }
    }

    fun pauseTask(taskId: String) {
        viewModelScope.launch { taskCoordinator.pauseTask(taskId) }
    }

    fun resumeTask(taskId: String) {
        viewModelScope.launch { taskCoordinator.resumeTask(taskId) }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch { taskCoordinator.cancelTask(taskId) }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch { taskCoordinator.retryTask(taskId) }
    }

    fun dismissTask(taskId: String) {
        taskCoordinator.dismissTask(taskId)
    }

    fun clearFinishedTasks() {
        taskCoordinator.clearFinished()
    }

    fun pronounceVocabTerm(term: String) {
        playbackController.pronounceStandalone(term, title = "نطق المفردة")
    }

    fun deleteVocabulary(id: Long) {
        viewModelScope.launch {
            libraryRepo.deleteVocabulary(id)
        }
    }

    private data class BaseLibrarySnapshot(
        val books: List<BookEntity>,
        val progressMap: Map<String, Float>,
        val translatedIds: Set<String>,
        val activeBookId: String?,
        val activeAsrModelId: String,
        val sortOrder: LibrarySortOrder,
        val viewMode: LibraryViewMode
    )
}
