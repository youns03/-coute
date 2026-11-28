package com.example.contracts

import android.net.Uri
import com.example.domain.AcceleratorMode
import com.example.domain.BenchmarkReport
import com.example.domain.Book
import com.example.domain.BookImportOutcome
import com.example.domain.Chapter
import com.example.domain.DocumentExtractionOptions
import com.example.domain.FileInspectionPreview
import com.example.domain.ImportProgressEvent
import com.example.domain.ParagraphTranslation
import com.example.domain.PlaybackUiState
import com.example.domain.ReaderUiState
import com.example.domain.RecentActivity
import com.example.domain.Sentence
import com.example.domain.SpeedControlMode
import com.example.domain.TranslationManagerUiState
import com.example.domain.UserSettings
import com.example.domain.VocabularyItem
import com.example.domain.WordLookupSheetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RecentActivityGateway {
    fun observeRecent(): Flow<List<RecentActivity>>
    suspend fun upsert(entry: RecentActivity)
    suspend fun deleteByTargetId(targetId: String)
}

interface BenchmarkReportGateway {
    fun observeAll(): Flow<List<BenchmarkReport>>
    suspend fun insert(report: BenchmarkReport)
    suspend fun clearAll()
}

interface SettingsGateway {
    val settingsFlow: Flow<UserSettings>
    suspend fun setActiveModelId(modelId: String)
    suspend fun setActiveVoiceId(voiceId: String)
    suspend fun setActiveAsrModelId(modelId: String)
    suspend fun setAcceleratorMode(mode: AcceleratorMode)
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun setSpeedControlMode(mode: SpeedControlMode)
    suspend fun setReaderFontSize(sizeSp: Float)
    suspend fun setReaderLineHeight(multiplier: Float)
    suspend fun setNightMode(enabled: Boolean)
    suspend fun setShowInlineTranslations(show: Boolean)
    suspend fun setActiveBookId(bookId: String)
    suspend fun setLibraryViewMode(mode: String)
    suspend fun setLibrarySortOrder(order: String)
    suspend fun setLastRouteName(routeName: String)
}

interface LibraryContentGateway {
    fun observeAllBooks(): Flow<List<Book>>
    fun observeAllChapters(): Flow<List<Chapter>>
    fun observeBook(bookId: String): Flow<Book?>
    suspend fun getBook(bookId: String): Book?
    fun observeChapters(bookId: String): Flow<List<Chapter>>
    fun observeChapterSentences(bookId: String, chapterIndex: Int): Flow<List<Sentence>>
    fun observeParagraphTranslations(
        bookId: String,
        chapterIndex: Int,
        sourceLanguage: String
    ): Flow<List<ParagraphTranslation>>

    fun observeBookIdsWithTranslations(): Flow<List<String>>
    fun observeRecentActivities(): Flow<List<RecentActivity>>
    fun observeVocabulary(query: String): Flow<List<VocabularyItem>>

    suspend fun seedStarterBooksIfEmpty()
    suspend fun insertTranscribedBook(book: Book, chapters: List<Chapter>, sentences: List<Sentence>)
    suspend fun findBookByContentHash(hash: String): Book?
    suspend fun inspectImportUris(uris: List<Uri>): List<FileInspectionPreview>
    suspend fun importBook(uri: Uri): Result<Book>
    suspend fun importBookChecked(uri: Uri): Result<BookImportOutcome>
    suspend fun importBookChecked(
        uri: Uri,
        options: DocumentExtractionOptions,
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<BookImportOutcome>

    suspend fun importDirectText(
        title: String,
        body: String,
        languageCode: String? = null
    ): Result<BookImportOutcome>

    suspend fun renameBook(bookId: String, newTitle: String)
    suspend fun updateBookCoverTemplate(bookId: String, styleId: String)
    suspend fun updateBookCoverFromUri(bookId: String, imageUri: Uri): Result<String>
    suspend fun searchInBook(bookId: String, query: String): List<Sentence>
    suspend fun updateReadingPosition(bookId: String, chapterIndex: Int, sentenceIndex: Int)
    suspend fun cacheSentenceTranslation(sentenceId: Long, arabic: String)
    suspend fun saveParagraphTranslation(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        sourceLanguage: String,
        arabicTranslation: String
    )

    suspend fun updateBookSourceLanguage(bookId: String, sourceLanguage: String)
    suspend fun deleteBook(bookId: String)
    suspend fun saveVocabulary(
        frenchTerm: String,
        arabicTranslation: String,
        contextSentence: String,
        bookTitle: String
    )

    suspend fun deleteVocabulary(id: Long)
    fun calculateProgress(book: Book, chapters: List<Chapter>): Float
}

interface ReadingEngineContract {
    val readerState: StateFlow<ReaderUiState>
    val wordLookupSheet: StateFlow<WordLookupSheetState?>
    val settings: StateFlow<UserSettings>
    val playbackState: StateFlow<PlaybackUiState>
    val translationManagerState: StateFlow<TranslationManagerUiState>

    fun openBook(bookId: String)
    fun selectChapter(chapterIndex: Int)
    fun toggleBookSourceLanguage()
    fun playFromSentence(sentence: Sentence)
    fun togglePlayCurrentChapter()
    fun toggleInlineTranslations()
    fun toggleOrTranslateSentence(sentence: Sentence)
    fun translateOrToggleParagraph(paragraphIndex: Int, paragraphSentences: List<Sentence>)
    fun onWordTapped(rawWord: String, sentence: Sentence)
    fun pronounceSelectedWord(word: String)
    fun saveSelectedWordToVocab()
    fun saveSentenceToVocab(sentence: Sentence, arabic: String)
    fun saveParagraphToVocab(paragraphSentences: List<Sentence>, arabic: String)
    fun dismissWordLookup()
    fun dismissTranslationError()
    fun toggleSearch()
    fun updateSearchQuery(query: String)
    fun jumpToSearchResult(sentence: Sentence)
    fun setPlaybackSpeed(speed: Float)
    fun setFontSize(sizeSp: Float)
    fun setLineHeight(mult: Float)
    fun setNightMode(enabled: Boolean)
    fun downloadActiveBookTranslationPack()
}
