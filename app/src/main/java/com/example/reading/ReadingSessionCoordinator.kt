package com.example.reading

import com.example.contracts.LibraryContentGateway
import com.example.contracts.PlaybackGateway
import com.example.contracts.ReadingEngineContract
import com.example.contracts.SettingsGateway
import com.example.contracts.TranslationGateway
import com.example.domain.Book
import com.example.domain.Chapter
import com.example.domain.PlaybackUiState
import com.example.domain.ReaderUiState
import com.example.domain.Sentence
import com.example.domain.SourceLanguage
import com.example.domain.TranslationDirection
import com.example.domain.TranslationManagerUiState
import com.example.domain.UserSettings
import com.example.domain.WordLookupSheetState
import kotlinx.coroutines.CoroutineScope
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

/**
 * Pure decoupled coordinator encapsulating reading session state, chapter switching,
 * sentence/paragraph inline translation, word lookup, vocabulary persistence, and TTS sync.
 * Depends solely on [domain] and [contracts].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionCoordinator(
    private val scope: CoroutineScope,
    private val libraryContentGateway: LibraryContentGateway,
    private val settingsGateway: SettingsGateway,
    private val translationGateway: TranslationGateway,
    private val playbackGateway: PlaybackGateway
) : ReadingEngineContract {

    private val _bookId = MutableStateFlow<String?>(null)
    private var readerSessionGeneration = 0L
    private var inlineTranslationJob: Job? = null
    private val _chapterIndex = MutableStateFlow(0)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchOpen = MutableStateFlow(false)
    private val _searchResults = MutableStateFlow<List<Sentence>>(emptyList())
    private val _translatingSentenceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _translatingParagraphIndices = MutableStateFlow<Set<Int>>(emptySet())
    private val _revealedSentenceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _translationError = MutableStateFlow<String?>(null)

    private val _wordLookupSheet = MutableStateFlow<WordLookupSheetState?>(null)
    override val wordLookupSheet: StateFlow<WordLookupSheetState?> = _wordLookupSheet.asStateFlow()

    override val settings: StateFlow<UserSettings> = settingsGateway.settingsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), UserSettings())

    override val playbackState: StateFlow<PlaybackUiState> = playbackGateway.state

    override val translationManagerState: StateFlow<TranslationManagerUiState> = translationGateway.state

    private val bookFlow = _bookId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryContentGateway.observeBook(id)
    }
    private val chaptersFlow = _bookId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else libraryContentGateway.observeChapters(id)
    }
    private val sentencesFlow = combine(_bookId, _chapterIndex) { id, ch -> id to ch }
        .flatMapLatest { (id, ch) ->
            if (id == null) flowOf(emptyList()) else libraryContentGateway.observeChapterSentences(id, ch)
        }
    private val paragraphTranslationsFlow = combine(_bookId, _chapterIndex, bookFlow) { id, ch, book ->
        Triple(id, ch, SourceLanguage.fromCode(book?.sourceLanguage).code)
    }.flatMapLatest { (id, ch, langCode) ->
        if (id == null) {
            flowOf(emptyMap())
        } else {
            libraryContentGateway.observeParagraphTranslations(id, ch, langCode).map { list ->
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

    override val readerState: StateFlow<ReaderUiState> = combine(
        bookFlow,
        chaptersFlow,
        _chapterIndex,
        sentencesFlow,
        searchBundleFlow,
        translationUiFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val searchTriple = values[4] as Triple<List<Sentence>, String, Boolean>
        val txBundle = values[5] as TranslationStatusBundle
        @Suppress("UNCHECKED_CAST")
        ReaderUiState(
            book = values[0] as Book?,
            chapters = values[1] as List<Chapter>,
            activeChapterIndex = values[2] as Int,
            sentences = values[3] as List<Sentence>,
            searchResults = searchTriple.first,
            searchQuery = searchTriple.second,
            isSearchOpen = searchTriple.third,
            translatingSentenceIds = txBundle.translatingSentenceIds,
            translatingParagraphIndices = txBundle.translatingParagraphIndices,
            paragraphTranslations = txBundle.paragraphTranslations,
            revealedSentenceIds = txBundle.revealedSentenceIds,
            translationError = txBundle.translationError
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), ReaderUiState())

    override fun openBook(bookId: String) {
        if (_bookId.value == bookId) return
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _bookId.value = bookId
        _revealedSentenceIds.value = emptySet()
        _translatingParagraphIndices.value = emptySet()
        _translationError.value = null
        scope.launch {
            settingsGateway.setActiveBookId(bookId)
            val book = libraryContentGateway.getBook(bookId)
            if (book != null) {
                _chapterIndex.value = book.currentChapterIndex.coerceIn(0, (book.totalChapters - 1).coerceAtLeast(0))
            }
            translationGateway.refreshAllStatuses()
        }
    }

    override fun selectChapter(chapterIndex: Int) {
        val bookId = _bookId.value ?: return
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _chapterIndex.value = chapterIndex
        _revealedSentenceIds.value = emptySet()
        _translatingParagraphIndices.value = emptySet()
        val pState = playbackGateway.state.value
        if (pState.bookId == bookId && pState.chapterIndex != chapterIndex) {
            playbackGateway.stop()
        }
        scope.launch {
            libraryContentGateway.updateReadingPosition(bookId, chapterIndex, 0)
        }
    }

    override fun toggleBookSourceLanguage() {
        val book = readerState.value.book ?: return
        if (book.sourceLanguage.lowercase().startsWith("ar")) return
        val current = SourceLanguage.fromCode(book.sourceLanguage)
        val next = if (current == SourceLanguage.FRENCH) SourceLanguage.ENGLISH else SourceLanguage.FRENCH
        readerSessionGeneration++
        inlineTranslationJob?.cancel()
        _translatingParagraphIndices.value = emptySet()
        scope.launch {
            if (playbackGateway.state.value.bookId == book.id) {
                playbackGateway.stop()
            }
            _revealedSentenceIds.value = emptySet()
            libraryContentGateway.updateBookSourceLanguage(book.id, next.code)
        }
    }

    override fun playFromSentence(sentence: Sentence) {
        val st = readerState.value
        val book = st.book ?: return
        val chTitle = st.chapters.getOrNull(st.activeChapterIndex)?.title ?: "Chapitre ${st.activeChapterIndex + 1}"
        playbackGateway.playChapterFrom(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = st.activeChapterIndex,
            chapterTitle = chTitle,
            startSentenceIndex = sentence.sentenceIndex,
            languageCode = book.sourceLanguage
        )
    }

    override fun togglePlayCurrentChapter() {
        val pState = playbackState.value
        val st = readerState.value
        val book = st.book ?: return
        if (pState.bookId == book.id && pState.chapterIndex == st.activeChapterIndex) {
            playbackGateway.togglePlayPause()
        } else {
            val chTitle = st.chapters.getOrNull(st.activeChapterIndex)?.title ?: "Chapitre ${st.activeChapterIndex + 1}"
            playbackGateway.playChapterFrom(
                bookId = book.id,
                bookTitle = book.title,
                chapterIndex = st.activeChapterIndex,
                chapterTitle = chTitle,
                startSentenceIndex = book.currentSentenceIndex,
                languageCode = book.sourceLanguage
            )
        }
    }

    override fun toggleInlineTranslations() {
        inlineTranslationJob?.cancel()
        inlineTranslationJob = scope.launch {
            val next = !settings.value.showInlineTranslations
            settingsGateway.setShowInlineTranslations(next)
            if (!next) {
                _revealedSentenceIds.value = emptySet()
                _translatingParagraphIndices.value = emptySet()
            } else {
                val snap = readerState.value
                val book = snap.book ?: return@launch
                if (book.sourceLanguage.lowercase().startsWith("ar")) {
                    settingsGateway.setShowInlineTranslations(false)
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
                    val res = translationGateway.translateSentence(
                        sentenceId = -1L,
                        sourceLanguage = sourceLang,
                        originalText = paragraphText,
                        cachedTranslation = null
                    )
                    _translatingParagraphIndices.value = _translatingParagraphIndices.value - paragraphIndex
                    if (requestGeneration != readerSessionGeneration) break
                    val translated = res.getOrNull()
                    if (res.isSuccess && !translated.isNullOrBlank()) {
                        libraryContentGateway.saveParagraphTranslation(
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

    override fun toggleOrTranslateSentence(sentence: Sentence) {
        val globalShown = settings.value.showInlineTranslations
        val isIndividuallyRevealed = sentence.id in _revealedSentenceIds.value
        val hasCached = !sentence.cachedArabicTranslation.isNullOrBlank()

        if (!globalShown && isIndividuallyRevealed && hasCached) {
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
        scope.launch {
            translateSentenceInternal(sentence, sourceLang)
        }
    }

    private suspend fun translateSentenceInternal(sentence: Sentence, sourceLang: SourceLanguage) {
        _translationError.value = null
        _translatingSentenceIds.value = _translatingSentenceIds.value + sentence.id
        _revealedSentenceIds.value = _revealedSentenceIds.value + sentence.id
        val res = translationGateway.translateSentence(
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

    override fun translateOrToggleParagraph(paragraphIndex: Int, paragraphSentences: List<Sentence>) {
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
        scope.launch {
            _translationError.value = null
            _revealedSentenceIds.value = _revealedSentenceIds.value + sentenceIds
            _translatingParagraphIndices.value = _translatingParagraphIndices.value + paragraphIndex
            val res = translationGateway.translateSentence(
                sentenceId = -1L,
                sourceLanguage = sourceLang,
                originalText = paragraphText,
                cachedTranslation = null
            )
            if (requestGeneration != readerSessionGeneration) return@launch
            _translatingParagraphIndices.value = _translatingParagraphIndices.value - paragraphIndex
            res.onSuccess { translated ->
                if (translated.isNotBlank()) {
                    libraryContentGateway.saveParagraphTranslation(
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

    override fun onWordTapped(rawWord: String, sentence: Sentence) {
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
        scope.launch {
            val res = translationGateway.translateWord(sourceLang, clean)
            _wordLookupSheet.value = _wordLookupSheet.value?.copy(
                translation = res.getOrNull(),
                isLoading = false,
                error = res.exceptionOrNull()?.message
            )
        }
    }

    override fun pronounceSelectedWord(word: String) {
        val lang = _wordLookupSheet.value?.sourceLanguage
            ?: SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        playbackGateway.pronounceStandalone(
            frenchText = word,
            title = "نطق الكلمة",
            languageCode = lang.code
        )
    }

    override fun saveSelectedWordToVocab() {
        val sheet = _wordLookupSheet.value ?: return
        val arabic = sheet.translation ?: return
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        scope.launch {
            libraryContentGateway.saveVocabulary(
                frenchTerm = sheet.selectedWord,
                arabicTranslation = arabic,
                contextSentence = sheet.contextSentence.originalText,
                bookTitle = bookTitle
            )
            _wordLookupSheet.value = _wordLookupSheet.value?.copy(savedToVocab = true)
        }
    }

    override fun saveSentenceToVocab(sentence: Sentence, arabic: String) {
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        scope.launch {
            libraryContentGateway.saveVocabulary(
                frenchTerm = sentence.originalText,
                arabicTranslation = arabic,
                contextSentence = sentence.originalText,
                bookTitle = bookTitle
            )
        }
    }

    override fun saveParagraphToVocab(paragraphSentences: List<Sentence>, arabic: String) {
        if (paragraphSentences.isEmpty() || arabic.isBlank()) return
        val bookTitle = readerState.value.book?.title ?: "مكتبتي"
        val sourceParagraph = paragraphSentences.joinToString(" ") { it.originalText.trim() }
        scope.launch {
            libraryContentGateway.saveVocabulary(
                frenchTerm = sourceParagraph,
                arabicTranslation = arabic,
                contextSentence = sourceParagraph,
                bookTitle = bookTitle
            )
        }
    }

    override fun dismissWordLookup() {
        _wordLookupSheet.value = null
    }

    override fun dismissTranslationError() {
        _translationError.value = null
    }

    override fun toggleSearch() {
        _isSearchOpen.value = !_isSearchOpen.value
        if (!_isSearchOpen.value) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }

    override fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        val bookId = _bookId.value ?: return
        scope.launch {
            _searchResults.value = libraryContentGateway.searchInBook(bookId, query)
        }
    }

    override fun jumpToSearchResult(sentence: Sentence) {
        _chapterIndex.value = sentence.chapterIndex
        _isSearchOpen.value = false
        playFromSentence(sentence)
    }

    override fun setPlaybackSpeed(speed: Float) = playbackGateway.setPlaybackSpeed(speed)
    override fun setFontSize(sizeSp: Float) { scope.launch { settingsGateway.setReaderFontSize(sizeSp) } }
    override fun setLineHeight(mult: Float) { scope.launch { settingsGateway.setReaderLineHeight(mult) } }
    override fun setNightMode(enabled: Boolean) { scope.launch { settingsGateway.setNightMode(enabled) } }

    override fun downloadActiveBookTranslationPack() {
        val sourceLang = SourceLanguage.fromCode(readerState.value.book?.sourceLanguage)
        val direction = TranslationDirection.forSource(sourceLang)
        scope.launch {
            translationGateway.downloadPack(direction)
        }
    }
}
