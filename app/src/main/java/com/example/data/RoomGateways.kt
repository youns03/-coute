package com.example.data

import com.example.contracts.BenchmarkReportGateway
import com.example.contracts.ModelRecordStore
import com.example.contracts.PlaybackPersistenceGateway
import com.example.contracts.RecentActivityGateway
import com.example.contracts.SentenceTranslationCacheStore
import com.example.contracts.TranslationJobStore
import com.example.contracts.VocabularyLookupStore
import com.example.domain.BenchmarkReport
import com.example.domain.Book
import com.example.domain.ModelInstallRecord
import com.example.domain.RecentActivity
import com.example.domain.Sentence
import com.example.domain.TranslationChunk
import com.example.domain.TranslationJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomModelRecordStore(
    private val dao: ModelRecordDao
) : ModelRecordStore {
    override fun observeAll(): Flow<List<ModelInstallRecord>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getRecord(modelId: String): ModelInstallRecord? =
        dao.getRecord(modelId)?.toDomain()

    override suspend fun upsert(record: ModelInstallRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun delete(modelId: String) {
        dao.delete(modelId)
    }
}

class RoomSentenceTranslationCacheStore(
    private val bookDao: BookDao
) : SentenceTranslationCacheStore {
    override suspend fun updateSentenceTranslation(sentenceId: Long, translation: String) {
        bookDao.updateSentenceTranslation(sentenceId, translation)
    }
}

class RoomVocabularyLookupStore(
    private val vocabularyDao: VocabularyDao
) : VocabularyLookupStore {
    override suspend fun findArabicTranslation(term: String): String? =
        vocabularyDao.findByFrenchTerm(term)?.arabicTranslation
}

class RoomTranslationJobStore(
    private val jobDao: TranslationJobDao
) : TranslationJobStore {
    override fun observeAllJobs(): Flow<List<TranslationJob>> =
        jobDao.observeAllJobs().map { list -> list.map { it.toDomain() } }

    override suspend fun getJob(jobId: String): TranslationJob? =
        jobDao.getJob(jobId)?.toDomain()

    override fun observeChunks(jobId: String): Flow<List<TranslationChunk>> =
        jobDao.observeChunks(jobId).map { list -> list.map { it.toDomain() } }

    override suspend fun getAllChunks(jobId: String): List<TranslationChunk> =
        jobDao.getAllChunks(jobId).map { it.toDomain() }

    override suspend fun getPendingChunks(jobId: String): List<TranslationChunk> =
        jobDao.getPendingChunks(jobId).map { it.toDomain() }

    override suspend fun countCompletedChunks(jobId: String): Int =
        jobDao.countCompletedChunks(jobId)

    override suspend fun updateJob(job: TranslationJob) {
        jobDao.updateJob(job.toEntity())
    }

    override suspend fun insertJobWithChunks(job: TranslationJob, chunks: List<TranslationChunk>) {
        jobDao.insertJobWithChunks(job.toEntity(), chunks.map { it.toEntity() })
    }

    override suspend fun markChunkTranslatedIfEpochMatches(
        chunkId: Long,
        jobId: String,
        expectedEpoch: Int,
        translatedText: String
    ): Int = jobDao.markChunkTranslatedIfEpochMatches(chunkId, jobId, expectedEpoch, translatedText)

    override suspend fun resetJobAtomically(jobId: String, sourceLanguage: String) {
        jobDao.resetJobAtomically(jobId, sourceLanguage)
    }

    override suspend fun updateJobProgress(
        jobId: String,
        completedChunks: Int,
        status: String,
        lastError: String?
    ) {
        jobDao.updateJobProgress(jobId, completedChunks, status, lastError)
    }

    override suspend fun updateJobProgressIfEpochMatches(
        jobId: String,
        expectedEpoch: Int,
        completedChunks: Int,
        status: String,
        lastError: String?
    ): Int = jobDao.updateJobProgressIfEpochMatches(
        jobId = jobId,
        expectedEpoch = expectedEpoch,
        completedChunks = completedChunks,
        status = status,
        lastError = lastError
    )

    override suspend fun resetRunningJobsToPaused() {
        jobDao.resetRunningJobsToPaused()
    }

    override suspend fun deleteJob(jobId: String) {
        jobDao.deleteJob(jobId)
    }
}

class RoomBenchmarkReportGateway(
    private val dao: BenchmarkReportDao
) : BenchmarkReportGateway {
    override fun observeAll(): Flow<List<BenchmarkReport>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun insert(report: BenchmarkReport) {
        dao.insert(report.toEntity())
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }
}

class RoomRecentActivityGateway(
    private val dao: RecentActivityDao
) : RecentActivityGateway {
    override fun observeRecent(): Flow<List<RecentActivity>> =
        dao.observeRecent().map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(entry: RecentActivity) {
        dao.upsert(entry.toEntity())
    }

    override suspend fun deleteByTargetId(targetId: String) {
        dao.deleteByTargetId(targetId)
    }
}

class RoomPlaybackPersistenceGateway(
    private val bookDao: BookDao
) : PlaybackPersistenceGateway {
    override suspend fun getBook(bookId: String): Book? =
        bookDao.getBook(bookId)?.toDomain()

    override suspend fun getChapterSentences(bookId: String, chapterIndex: Int): List<Sentence> =
        bookDao.getChapterSentences(bookId, chapterIndex).map { it.toDomain() }

    override suspend fun updateReadingPosition(bookId: String, chapterIndex: Int, sentenceIndex: Int) {
        bookDao.updateReadingPosition(bookId, chapterIndex, sentenceIndex)
    }

    override suspend fun setBookCompleted(bookId: String, completed: Boolean) {
        bookDao.setBookCompleted(bookId, completed)
    }
}
