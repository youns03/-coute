package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun countBooks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentences(sentences: List<SentenceEntity>)

    @Transaction
    suspend fun insertFullBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        sentences: List<SentenceEntity>
    ) {
        insertBook(book)
        insertChapters(chapters)
        insertSentences(sentences)
    }

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query(
        "SELECT * FROM sentences WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY sentenceIndex ASC"
    )
    fun observeChapterSentences(bookId: String, chapterIndex: Int): Flow<List<SentenceEntity>>

    @Query(
        "SELECT * FROM sentences WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY sentenceIndex ASC"
    )
    suspend fun getChapterSentences(bookId: String, chapterIndex: Int): List<SentenceEntity>

    @Query(
        "SELECT * FROM sentences WHERE bookId = :bookId AND LOWER(originalText) LIKE '%' || LOWER(:query) || '%' ORDER BY chapterIndex ASC, sentenceIndex ASC LIMIT 100"
    )
    suspend fun searchInBook(bookId: String, query: String): List<SentenceEntity>

    @Query("UPDATE sentences SET cachedArabicTranslation = :translation WHERE id = :sentenceId")
    suspend fun updateSentenceTranslation(sentenceId: Long, translation: String)

    @Query(
        "UPDATE books SET currentChapterIndex = :chapterIndex, currentSentenceIndex = :sentenceIndex, lastReadAt = :timestamp WHERE id = :bookId"
    )
    suspend fun updateReadingPosition(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE books SET sourceLanguage = :sourceLanguage WHERE id = :bookId")
    suspend fun updateBookSourceLanguage(bookId: String, sourceLanguage: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query(
        "SELECT * FROM vocabulary WHERE LOWER(frenchTerm) LIKE '%' || LOWER(:query) || '%' OR arabicTranslation LIKE '%' || :query || '%' ORDER BY createdAt DESC"
    )
    fun search(query: String): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE LOWER(frenchTerm) = LOWER(:term) LIMIT 1")
    suspend fun findByFrenchTerm(term: String): VocabularyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ModelRecordDao {
    @Query("SELECT * FROM model_records")
    fun observeAll(): Flow<List<ModelRecordEntity>>

    @Query("SELECT * FROM model_records WHERE modelId = :modelId")
    suspend fun getRecord(modelId: String): ModelRecordEntity?

    @Query("SELECT * FROM model_records")
    suspend fun getAllRecords(): List<ModelRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ModelRecordEntity)

    @Query("DELETE FROM model_records WHERE modelId = :modelId")
    suspend fun delete(modelId: String)
}

@Dao
interface BenchmarkReportDao {
    @Query("SELECT * FROM benchmark_reports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchmarkReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: BenchmarkReportEntity)

    @Query("DELETE FROM benchmark_reports")
    suspend fun clearAll()
}

@Dao
interface TranslationJobDao {
    @Query("SELECT * FROM translation_jobs ORDER BY updatedAt DESC")
    fun observeAllJobs(): Flow<List<TranslationJobEntity>>

    @Query("SELECT * FROM translation_jobs WHERE id = :jobId")
    suspend fun getJob(jobId: String): TranslationJobEntity?

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    fun observeChunks(jobId: String): Flow<List<TranslationChunkEntity>>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    suspend fun getAllChunks(jobId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND isCompleted = 0 ORDER BY chunkIndex ASC")
    suspend fun getPendingChunks(jobId: String): List<TranslationChunkEntity>

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId AND isCompleted = 1")
    suspend fun countCompletedChunks(jobId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: TranslationJobEntity)

    @Update
    suspend fun updateJob(job: TranslationJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<TranslationChunkEntity>)

    @Transaction
    suspend fun insertJobWithChunks(job: TranslationJobEntity, chunks: List<TranslationChunkEntity>) {
        upsertJob(job)
        insertChunks(chunks)
    }

    @Query("UPDATE translation_chunks SET translatedText = :translatedText, isCompleted = 1 WHERE id = :chunkId")
    suspend fun markChunkTranslated(chunkId: Long, translatedText: String)

    @Query("UPDATE translation_chunks SET translatedText = NULL, isCompleted = 0 WHERE jobId = :jobId")
    suspend fun resetChunksForJob(jobId: String)

    @Query(
        "UPDATE translation_jobs SET completedChunks = :completedChunks, status = :status, lastError = :lastError, updatedAt = :updatedAt WHERE id = :jobId"
    )
    suspend fun updateJobProgress(
        jobId: String,
        completedChunks: Int,
        status: String,
        lastError: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE translation_jobs SET status = 'PAUSED' WHERE status = 'RUNNING'")
    suspend fun resetRunningJobsToPaused()

    @Query("DELETE FROM translation_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)
}

