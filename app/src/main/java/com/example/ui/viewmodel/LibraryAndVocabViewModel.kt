package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.data.BookEntity
import com.example.data.LibraryRepository
import com.example.data.VocabularyEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val bookProgress: Map<String, Float> = emptyMap(),
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val statusBanner: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAndVocabViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val libraryRepo = app.libraryRepository
    private val playbackController = app.playbackController

    private val _bookSearchQuery = MutableStateFlow("")
    private val _isImporting = MutableStateFlow(false)
    private val _statusBanner = MutableStateFlow<String?>(null)

    val libraryState: StateFlow<LibraryUiState> = combine(
        libraryRepo.observeAllBooks(),
        libraryRepo.observeAllChapters(),
        _bookSearchQuery,
        _isImporting,
        _statusBanner
    ) { books, allChapters, query, importing, banner ->
        val chaptersByBook = allChapters.groupBy { it.bookId }
        val progressMap = books.associate { book ->
            book.id to LibraryRepository.calculateBookProgress(book, chaptersByBook[book.id].orEmpty())
        }
        val filtered = if (query.isBlank()) books else {
            books.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.author.contains(query, ignoreCase = true)
            }
        }
        LibraryUiState(
            books = filtered,
            bookProgress = progressMap,
            searchQuery = query,
            isImporting = importing,
            statusBanner = banner
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    private val _vocabQuery = MutableStateFlow("")
    val vocabQuery: StateFlow<String> = _vocabQuery.asStateFlow()

    val vocabularyList: StateFlow<List<VocabularyEntity>> = _vocabQuery
        .flatMapLatest { q -> libraryRepo.observeVocabulary(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBookSearchQuery(q: String) {
        _bookSearchQuery.value = q
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
            val res = libraryRepo.importBook(uri)
            _isImporting.value = false
            res.onSuccess { book ->
                _statusBanner.value = "تم استيراد «${book.title}» (${book.totalSentences} جملة)."
                onImported(book.id)
            }.onFailure { err ->
                _statusBanner.value = "تعذر استيراد الملف: ${err.message ?: "خطأ غير معروف"}"
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            if (playbackController.state.value.bookId == bookId) {
                playbackController.stop()
            }
            libraryRepo.deleteBook(bookId)
            _statusBanner.value = "تم حذف الكتاب من المكتبة."
        }
    }

    fun pronounceVocabTerm(term: String) {
        playbackController.pronounceStandalone(term, title = "نطق المفردة")
    }

    fun deleteVocabulary(id: Long) {
        viewModelScope.launch {
            libraryRepo.deleteVocabulary(id)
        }
    }
}
