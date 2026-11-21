package com.example.data

import android.net.Uri
import com.example.domain.BenchmarkCorpus
import com.example.domain.SpeechTextProcessor
import kotlinx.coroutines.flow.Flow

class LibraryRepository(
    private val bookDao: BookDao,
    private val vocabularyDao: VocabularyDao,
    private val bookImporter: BookImporter
) {
    fun observeAllBooks(): Flow<List<BookEntity>> = bookDao.observeAllBooks()

    fun observeBook(bookId: String): Flow<BookEntity?> = bookDao.observeBook(bookId)

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = bookDao.observeChapters(bookId)

    fun observeChapterSentences(bookId: String, chapterIndex: Int): Flow<List<SentenceEntity>> =
        bookDao.observeChapterSentences(bookId, chapterIndex)

    fun observeVocabulary(query: String): Flow<List<VocabularyEntity>> =
        if (query.isBlank()) vocabularyDao.observeAll() else vocabularyDao.search(query.trim())

    suspend fun seedStarterBooksIfEmpty() {
        if (bookDao.countBooks() > 0) return
        for (seed in BenchmarkCorpus.starterBooks) {
            val chapters = mutableListOf<ChapterEntity>()
            val sentences = mutableListOf<SentenceEntity>()
            var totalSentences = 0
            seed.chapters.forEachIndexed { chIdx, (chTitle, rawBody) ->
                val processed = SpeechTextProcessor.splitAndPreprocess(rawBody, seed.sourceLanguage)
                chapters.add(
                    ChapterEntity(
                        bookId = seed.id,
                        chapterIndex = chIdx,
                        title = chTitle,
                        sentenceCount = processed.size
                    )
                )
                processed.forEach { s ->
                    sentences.add(
                        SentenceEntity(
                            bookId = seed.id,
                            chapterIndex = chIdx,
                            sentenceIndex = s.index,
                            paragraphIndex = s.paragraphIndex,
                            originalText = s.originalText,
                            spokenText = s.spokenText
                        )
                    )
                }
                totalSentences += processed.size
            }
            val book = BookEntity(
                id = seed.id,
                title = seed.title,
                author = seed.author,
                sourceType = "STARTER",
                sourceLanguage = seed.sourceLanguage,
                levelTag = seed.level,
                totalChapters = chapters.size,
                totalSentences = totalSentences
            )
            bookDao.insertFullBook(book, chapters, sentences)
        }
    }

    suspend fun importBook(uri: Uri): Result<BookEntity> {
        val parsed = bookImporter.importFromUri(uri)
        if (parsed.isFailure) return Result.failure(parsed.exceptionOrNull()!!)
        val payload = parsed.getOrThrow()
        bookDao.insertFullBook(payload.book, payload.chapters, payload.sentences)
        return Result.success(payload.book)
    }

    suspend fun searchInBook(bookId: String, query: String): List<SentenceEntity> {
        if (query.isBlank()) return emptyList()
        return bookDao.searchInBook(bookId, query.trim())
    }

    suspend fun updateReadingPosition(bookId: String, chapterIndex: Int, sentenceIndex: Int) {
        bookDao.updateReadingPosition(bookId, chapterIndex, sentenceIndex)
    }

    suspend fun cacheSentenceTranslation(sentenceId: Long, arabic: String) {
        bookDao.updateSentenceTranslation(sentenceId, arabic)
    }

    suspend fun updateBookSourceLanguage(bookId: String, sourceLanguage: String) {
        bookDao.updateBookSourceLanguage(bookId, sourceLanguage)
    }

    suspend fun deleteBook(bookId: String) = bookDao.deleteBook(bookId)

    suspend fun saveVocabulary(
        frenchTerm: String,
        arabicTranslation: String,
        contextSentence: String,
        bookTitle: String
    ) {
        val cleanTerm = frenchTerm.trim()
        if (cleanTerm.isEmpty() || arabicTranslation.isBlank()) return
        val existing = vocabularyDao.findByFrenchTerm(cleanTerm)
        vocabularyDao.insert(
            VocabularyEntity(
                id = existing?.id ?: 0L,
                frenchTerm = cleanTerm,
                arabicTranslation = arabicTranslation.trim(),
                contextSentence = contextSentence.trim(),
                bookTitle = bookTitle
            )
        )
    }

    suspend fun deleteVocabulary(id: Long) = vocabularyDao.deleteById(id)
}
