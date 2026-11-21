package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EcouteApplication
import com.example.audio.PlaybackUiState
import com.example.data.BookEntity
import com.example.data.ChapterEntity
import com.example.data.SentenceEntity
import com.example.data.UserSettings
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WordLookupSheetState(
    val selectedWord: String,
    val contextSentence: SentenceEntity,
    val sourceLanguage: SourceLanguage,
    val translation: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedToVocab: Boolean = false
)

data class ReaderUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val activeChapterIndex: Int = 0,
    val sentences: List<SentenceEntity> = emptyList(),
    val searchResults: List<SentenceEntity> = emptyList(),
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val translatingSentenceIds: Set<Long> = emptySet(),
    val translatingParagraphIndices: Set<Int> = emptySet(),
    val revealedSentenceIds: Set<Long> = emptySet(),
    val translationError: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcouteApplication
    private val libraryRepo = app.libraryRepository
    private val settingsRepo = app.settingsRepository
    private val engineManager = app.translationEngineManager
    private val playbackController = app.playbackController

    private val _bookId = MutableStateFlow<String?>(null)
    private val _chapterIndex = MutableStateFlow(0)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchOpen = MutableStateFlow(false)
    private val _searchResults = MutableStateFlow<List<SentenceEntity>>(emptyList())
    private val _translatingSentenceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _translatingParagraphIndices = MutableStateFlow<Set<Int>>(emptySet())
    private val _revealedSentenceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _translationError = MutableStateFlow<String?>(null)

    private val _wordLookupSheet = MutableStateFlow<WordLookupSheetState?>(null)
    val wordLookupSheet: StateFlow<WordLookupSheetState?> = _wordLookupSheet.asStateFlow()

    val settings: StateFlow<UserSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val playbackState: StateFlow<PlaybackUiState> = playbackController.state

    val translationManagerState: StateFlow<TranslationManagerUiState> = engineManager.state

    private val bookFlow = _bookId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepo.observeBook(id)
    }
    private val chaptersFlow = _bookId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else libraryRepo.observeChapters(id)
    }
    private val sentencesFlow = combine(_bookId, _chapterIndex) { id, ch -> id to ch }
        .flatMapLatest { (id, ch) ->
            if (id == null) flowOf(emptyList()) else libraryRepo.observeChapterSentences(id, ch)
        }

    private val searchBundleFlow = combine(_searchResults, _searchQuery, _isSearchOpen) { res, q, open ->
        Triple(res, q, open)
    }
    private val translationUiFlow = combine(
        _translatingSentenceIds,
        _translatingParagraphIndices,
        _revealedSentenceIds,
        _translationError
    ) { sIds, pIdxs, revealed, err ->
        TranslationStatusBundle(sIds, pIdxs, revealed, err)
    }

    private data class TranslationStatusBundle(
        val translatingSentenceIds: Set<Long>,
        val translatingParagraphIndices: Set<Int>,
        val revealedSentenceIds: Set<Long>,
        val translationError: String?
    )

    val readerState: StateFlow<ReaderUiState> = combine(
        bookFlow,
        chaptersFlow,
        _chapterIndex,
        sentencesFlow,
        searchBundleFlow,
        translationUiFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val searchTriple = values[4] as Triple<List<SentenceEntity>, String, Boolean>
        val txBundle = values[5] as TranslationStatusBundle
        ReaderUiState(
            book = values[0] as BookEntity?,
            chapters = values[1] as List<ChapterEntity>,
            activeChapterIndex = values[2] as Int,
            sentences = values[3] as List<SentenceEntity>,
            searchResults = searchTriple.first,
            searchQuery = searchTriple.second,
            isSearchOpen = searchTriple.third,
            translatingSentenceIds = txBundle.translatingSentenceIds,
            translatingParagraphIndices = txBundle.translatingParagraphIndices,
            revealedSentenceIds = txBundle.revealedSentenceIds,
            translationError = txBundle.translationError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderUiState())

    fun openBook(bookId: String) {
        if (_bookId.value == bookId) return
        _bookId.value = bookId
        _revealedSentenceIds.value = emptySet()
        _translationError.value = null
        viewModelScope.launch {
            settingsRepo.setActiveBookId(bookId)
            val book = app.database.bookDao().getBook(bookId)
            if (book != null) {
                _chapterIndex.value = book.currentChapterIndex.coerceIn(0, (book.totalChapters - 1).coerceAtLeast(0))
            }
            engineManager.refreshAllStatuses()
        }
    }

    fun selectChapter(chapterIndex: Int) {
        val bookId = _bookId.value ?: return
        _chapterIndex.value = chapterIndex
        _revealedSentenceIds.value = emptySet()
        val pState = playbackController.state.value
        if (pState.bookId == bookId && pState.chapterIndex != chapterIndex) {
            playbackController.stop()
        }
        viewModelScope.launch {
            libraryRepo.updateReadingPosition(bookId, chapterIndex, 0)
        }
    }

    fun toggleBookSourceLanguage() {
        val book = readerState.value.book ?: return
        val current = SourceLanguage.fromCode(book.sourceLanguage)
        val next = if (current == SourceLanguage.FRENCH) SourceLanguage.ENGLISH else SourceLanguage.FRENCH
        viewModelScope.launch {
            if (playbackController.state.value.bookId == book.id) {
                playbackController.stop()
            }
            libraryRepo.updateBookSourceLanguage(book.id, next.code)
        }
    }

    fun playFromSentence(sentence: SentenceEntity) {
        val st = readerState.value
        val book = st.book ?: return
        val chTitle = st.chapters.getOrNull(st.activeChapterIndex)?.title ?: "Chapitre ${st.activeChapterIndex + 1}"
        playbackController.playChapterFrom(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = st.activeChapterIndex,
            chapterTitle = chTitle,
            startSentenceIndex = sentence.sentenceIndex,
            languageCode = book.sourceLanguage
        )
    }

    fun togglePlayCurrentChapter() {
        val pState = playbackState.value
        val st = readerState.value
        val book = st.book ?: return
        if (pState.bookId == book.id && pState.chapterIndex == st.activeChapterIndex) {
            playbackController.togglePlayPause()
        } else {
            val chTitle = st.chapters.getOrNull(st.activeChapterIndex)?.title ?: "Chapitre ${st.activeChapterIndex + 1}"
            playbackController.playChapterFrom(
                bookId = book.id,
                bookTitle = book.title,
                chapterIndex = st.activeChapterIndex,
                chapterTitle = chTitle,
                startSentenceIndex = book.currentSentenceIndex,
                languageCode = book.sourceLanguage
            )
        }
    }

    fun toggleInlineTranslations() {
        viewModelScope.launch {
            val next = !settings.value.showInlineTranslations
            settingsRepo.setShowInlineTranslations(next)
            if (!next) {
                _revealedSentenceIds.value = emptySet()
            } else {
                val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
                val pending = readerState.value.sentences
                    .filter { it.cachedArabicTranslation.isNullOrBlank() }
                    .take(15)
                pending.forEach { translateSentenceInternal(it, sourceLang) }
            }
        }
    }

    fun toggleOrTranslateSentence(sentence: SentenceEntity) {
        val globalShown = settings.value.showInlineTranslations
        val isIndividuallyRevealed = sentence.id in _revealedSentenceIds.value
        val hasCached = !sentence.cachedArabicTranslation.isNullOrBlank()

        if (!globalShown && isIndividuallyRevealed && hasCached) {
            // Hide this sentence's translation without deleting cached Arabic
            _revealedSentenceIds.value = _revealedSentenceIds.value - sentence.id
            return
        }
        if (hasCached) {
            _revealedSentenceIds.value = _revealedSentenceIds.value + sentence.id
            return
        }

        val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        viewModelScope.launch {
            translateSentenceInternal(sentence, sourceLang)
        }
    }

    private suspend fun translateSentenceInternal(sentence: SentenceEntity, sourceLang: SourceLanguage) {
        _translationError.value = null
        _translatingSentenceIds.value = _translatingSentenceIds.value + sentence.id
        _revealedSentenceIds.value = _revealedSentenceIds.value + sentence.id
        val res = engineManager.translateSentence(
            sentenceId = sentence.id,
            sourceLanguage = sourceLang,
            originalText = sentence.originalText,
            cachedTranslation = sentence.cachedArabicTranslation
        )
        _translatingSentenceIds.value = _translatingSentenceIds.value - sentence.id
        res.onFailure { err ->
            _revealedSentenceIds.value = _revealedSentenceIds.value - sentence.id
            _translationError.value = err.message ?: "تعذر ترجمة الجملة"
        }
    }

    fun translateOrToggleParagraph(paragraphIndex: Int, paragraphSentences: List<SentenceEntity>) {
        if (paragraphSentences.isEmpty()) return
        val globalShown = settings.value.showInlineTranslations
        val allCached = paragraphSentences.all { !it.cachedArabicTranslation.isNullOrBlank() }
        val allRevealed = paragraphSentences.all { it.id in _revealedSentenceIds.value }

        if (!globalShown && allCached && allRevealed) {
            _revealedSentenceIds.value = _revealedSentenceIds.value - paragraphSentences.map { it.id }.toSet()
            return
        }

        _revealedSentenceIds.value = _revealedSentenceIds.value + paragraphSentences.map { it.id }.toSet()
        if (allCached) return

        val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        viewModelScope.launch {
            _translationError.value = null
            _translatingParagraphIndices.value = _translatingParagraphIndices.value + paragraphIndex
            val res = engineManager.translateParagraph(paragraphSentences, sourceLang)
            _translatingParagraphIndices.value = _translatingParagraphIndices.value - paragraphIndex
            res.onFailure { err ->
                _translationError.value = err.message ?: "تعذر ترجمة الفقرة"
            }
        }
    }

    fun onWordTapped(rawWord: String, sentence: SentenceEntity) {
        val clean = rawWord.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' && it != '-' }
        if (clean.isEmpty()) return
        val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        _wordLookupSheet.value = WordLookupSheetState(
            selectedWord = clean,
            contextSentence = sentence,
            sourceLanguage = sourceLang,
            isLoading = true
        )
        viewModelScope.launch {
            val res = engineManager.translateWord(sourceLang, clean)
            _wordLookupSheet.value = _wordLookupSheet.value?.copy(
                translation = res.getOrNull(),
                isLoading = false,
                error = res.exceptionOrNull()?.message
            )
        }
    }

    fun pronounceSelectedWord(word: String) {
        val lang = _wordLookupSheet.value?.sourceLanguage
            ?: SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        playbackController.pronounceStandalone(
            frenchText = word,
            title = "نطق الكلمة",
            languageCode = lang.code
        )
    }

    fun saveSelectedWordToVocab() {
        val sheet = _wordLookupSheet.value ?: return
        val arabic = sheet.translation ?: return
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        viewModelScope.launch {
            libraryRepo.saveVocabulary(
                frenchTerm = sheet.selectedWord,
                arabicTranslation = arabic,
                contextSentence = sheet.contextSentence.originalText,
                bookTitle = bookTitle
            )
            _wordLookupSheet.value = _wordLookupSheet.value?.copy(savedToVocab = true)
        }
    }

    fun saveSentenceToVocab(sentence: SentenceEntity, arabic: String) {
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        viewModelScope.launch {
            libraryRepo.saveVocabulary(
                frenchTerm = sentence.originalText,
                arabicTranslation = arabic,
                contextSentence = sentence.originalText,
                bookTitle = bookTitle
            )
        }
    }

    fun dismissWordLookup() {
        _wordLookupSheet.value = null
    }

    fun dismissTranslationError() {
        _translationError.value = null
    }

    fun toggleSearch() {
        _isSearchOpen.value = !_isSearchOpen.value
        if (!_isSearchOpen.value) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        val bookId = _bookId.value ?: return
        viewModelScope.launch {
            _searchResults.value = libraryRepo.searchInBook(bookId, query)
        }
    }

    fun jumpToSearchResult(sentence: SentenceEntity) {
        _chapterIndex.value = sentence.chapterIndex
        _isSearchOpen.value = false
        playFromSentence(sentence)
    }

    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)
    fun setFontSize(sizeSp: Float) = viewModelScope.launch { settingsRepo.setReaderFontSize(sizeSp) }
    fun setLineHeight(mult: Float) = viewModelScope.launch { settingsRepo.setReaderLineHeight(mult) }
    fun setNightMode(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNightMode(enabled) }

    fun downloadActiveBookTranslationPack() {
        val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        val direction = TranslationDirection.forSource(sourceLang)
        viewModelScope.launch {
            engineManager.downloadPack(direction)
        }
    }
}
