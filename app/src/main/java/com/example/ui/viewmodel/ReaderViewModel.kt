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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    val paragraphTranslations: Map<Int, String> = emptyMap(),
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
    private var readerSessionGeneration = 0L
    private var inlineTranslationJob: Job? = null
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
    private val paragraphTranslationsFlow = combine(_bookId, _chapterIndex, bookFlow) { id, ch, book ->
        Triple(id, ch, SourceLanguage.fromCode(book?.sourceLanguage).code)
    }.flatMapLatest { (id, ch, langCode) ->
        if (id == null) {
            flowOf(emptyMap())
        } else {
            libraryRepo.observeParagraphTranslations(id, ch, langCode).map { list ->
                list.associate { it.paragraphIndex to it.arabicTranslation }
            }
        }
    }

    private val searchBundleFlow = combine(_searchResults, _searchQuery, _isSearchOpen) { res, q, open ->
        Triple(res, q, open)
    }
    private val translationUiFlow = combine(
        _translatingSentenceIds,
        _translatingParagraphIndices,
        _revealedSentenceIds,
        _translationError,
        paragraphTranslationsFlow
    ) { sIds, pIdxs, revealed, err, paragraphTranslations ->
        TranslationStatusBundle(sIds, pIdxs, paragraphTranslations, revealed, err)
    }

    private data class TranslationStatusBundle(
        val translatingSentenceIds: Set<Long>,
        val translatingParagraphIndices: Set<Int>,
        val paragraphTranslations: Map<Int, String>,
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
            paragraphTranslations = txBundle.paragraphTranslations,
            revealedSentenceIds = txBundle.revealedSentenceIds,
            translationError = txBundle.translationError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderUiState())

    fun openBook(bookId: String) {
        if (_bookId.value == bookId) return
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _bookId.value = bookId
        _revealedSentenceIds.value = emptySet()
        _translatingParagraphIndices.value = emptySet()
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
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _chapterIndex.value = chapterIndex
        _revealedSentenceIds.value = emptySet()
        _translatingParagraphIndices.value = emptySet()
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
        if (book.sourceLanguage.lowercase().startsWith("ar")) return
        val current = SourceLanguage.fromCode(book.sourceLanguage)
        val next = if (current == SourceLanguage.FRENCH) SourceLanguage.ENGLISH else SourceLanguage.FRENCH
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _translatingParagraphIndices.value = emptySet()
        viewModelScope.launch {
            if (playbackController.state.value.bookId == book.id) {
                playbackController.stop()
            }
            _revealedSentenceIds.value = emptySet()
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
        inlineTranslationJob?.cancel()
        inlineTranslationJob = viewModelScope.launch {
            val next = !settings.value.showInlineTranslations
            settingsRepo.setShowInlineTranslations(next)
            if (!next) {
                _revealedSentenceIds.value = emptySet()
                _translatingParagraphIndices.value = emptySet()
            } else {
                val snap = readerState.value
                val book = snap.book ?: return@launch
                if (book.sourceLanguage.lowercase().startsWith("ar")) {
                    settingsRepo.setShowInlineTranslations(false)
                    return@launch
                }
                val chapterIndex = snap.activeChapterIndex
                val sourceLang = SourceLanguage.fromCode(book.sourceLanguage)
                val existingParagraphs = snap.paragraphTranslations
                val requestGeneration = readerSessionGeneration
                val paragraphs = snap.sentences
                    .groupBy { it.paragraphIndex }
                    .toSortedMap()
                    .filter { (pIdx, sents) ->
                        existingParagraphs[pIdx].isNullOrBlank() &&
                            sents.any { it.cachedArabicTranslation.isNullOrBlank() }
                    }
                for ((paragraphIndex, paragraphSentences) in paragraphs) {
                    if (!isActive || requestGeneration != readerSessionGeneration) break
                    val paragraphText = paragraphSentences.joinToString(" ") { it.originalText.trim() }
                    if (paragraphText.isBlank()) continue
                    _translationError.value = null
                    _translatingParagraphIndices.value = _translatingParagraphIndices.value + paragraphIndex
                    val res = engineManager.translateSentence(
                        sentenceId = -1L,
                        sourceLanguage = sourceLang,
                        originalText = paragraphText,
                        cachedTranslation = null
                    )
                    _translatingParagraphIndices.value = _translatingParagraphIndices.value - paragraphIndex
                    if (requestGeneration != readerSessionGeneration) break
                    val translated = res.getOrNull()
                    if (res.isSuccess && !translated.isNullOrBlank()) {
                        libraryRepo.saveParagraphTranslation(
                            bookId = book.id,
                            chapterIndex = chapterIndex,
                            paragraphIndex = paragraphIndex,
                            sourceLanguage = sourceLang.code,
                            arabicTranslation = translated
                        )
                    } else {
                        _translationError.value = res.exceptionOrNull()?.message ?: "تعذر ترجمة الفصل محلياً."
                        break
                    }
                }
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

        val rawSourceLang = readerState.value.book?.sourceLanguage
        if (rawSourceLang.orEmpty().lowercase().startsWith("ar")) return
        val sourceLang = SourceLanguage.fromCode(rawSourceLang)
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
        val snap = readerState.value
        val book = snap.book ?: return
        if (book.sourceLanguage.lowercase().startsWith("ar")) return
        val chapterIndex = snap.activeChapterIndex
        val globalShown = settings.value.showInlineTranslations
        val sentenceIds = paragraphSentences.map { it.id }.toSet()
        val allRevealed = sentenceIds.all { it in _revealedSentenceIds.value }
        val cachedParagraph = snap.paragraphTranslations[paragraphIndex]

        if (!globalShown && cachedParagraph != null && allRevealed) {
            _revealedSentenceIds.value = _revealedSentenceIds.value - sentenceIds
            return
        }
        if (cachedParagraph != null) {
            _revealedSentenceIds.value = _revealedSentenceIds.value + sentenceIds
            return
        }

        val sourceLang = SourceLanguage.fromCode(book.sourceLanguage)
        val paragraphText = paragraphSentences.joinToString(" ") { it.originalText.trim() }
        val requestGeneration = readerSessionGeneration
        viewModelScope.launch {
            _translationError.value = null
            _revealedSentenceIds.value = _revealedSentenceIds.value + sentenceIds
            _translatingParagraphIndices.value = _translatingParagraphIndices.value + paragraphIndex
            // Translate the complete paragraph in one model request so context and sentence flow
            // are preserved; sentence-by-sentence translation remains available as a legacy fallback.
            val res = engineManager.translateSentence(
                sentenceId = -1L,
                sourceLanguage = sourceLang,
                originalText = paragraphText,
                cachedTranslation = null
            )
            // A chapter/book/language switch invalidates this request; never let an old result
            // populate a different paragraph that happens to reuse the same paragraph index.
            if (requestGeneration != readerSessionGeneration) return@launch
            _translatingParagraphIndices.value = _translatingParagraphIndices.value - paragraphIndex
            res.onSuccess { translated ->
                if (translated.isNotBlank()) {
                    libraryRepo.saveParagraphTranslation(
                        bookId = book.id,
                        chapterIndex = chapterIndex,
                        paragraphIndex = paragraphIndex,
                        sourceLanguage = sourceLang.code,
                        arabicTranslation = translated
                    )
                } else {
                    _revealedSentenceIds.value = _revealedSentenceIds.value - sentenceIds
                    _translationError.value = "أعاد محرك الترجمة نتيجة فارغة."
                }
            }.onFailure { err ->
                _revealedSentenceIds.value = _revealedSentenceIds.value - sentenceIds
                _translationError.value = err.message ?: "تعذرت ترجمة الفقرة"
            }
        }
    }

    fun onWordTapped(rawWord: String, sentence: SentenceEntity) {
        val rawLang = readerState.value.book?.sourceLanguage.orEmpty().lowercase()
        if (rawLang.startsWith("ar")) return
        val clean = rawWord.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' && it != '-' }
        if (clean.isEmpty()) return
        val sourceLang = SourceLanguage.fromCode(rawLang)
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

    fun saveParagraphToVocab(paragraphSentences: List<SentenceEntity>, arabic: String) {
        if (paragraphSentences.isEmpty() || arabic.isBlank()) return
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        val sourceParagraph = paragraphSentences.joinToString(" ") { it.originalText.trim() }
        viewModelScope.launch {
            libraryRepo.saveVocabulary(
                frenchTerm = sourceParagraph,
                arabicTranslation = arabic,
                contextSentence = sourceParagraph,
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
