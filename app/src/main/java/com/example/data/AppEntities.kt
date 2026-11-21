package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val sourceType: String, // "STARTER", "TXT", "EPUB"
    val sourceLanguage: String = "fr", // "fr" or "en"
    val levelTag: String = "",
    val totalChapters: Int,
    val totalSentences: Int,
    val currentChapterIndex: Int = 0,
    val currentSentenceIndex: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val sentenceCount: Int
)

@Entity(
    tableName = "sentences",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId", "chapterIndex", "sentenceIndex")]
)
data class SentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val paragraphIndex: Int,
    val originalText: String,
    val spokenText: String,
    val cachedArabicTranslation: String? = null
)

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val frenchTerm: String,
    val arabicTranslation: String,
    val contextSentence: String,
    val bookTitle: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_records")
data class ModelRecordEntity(
    @PrimaryKey val modelId: String,
    val status: String, // ModelInstallStatus.name
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installedDirPath: String? = null,
    val verifiedSha256: Boolean = false,
    val verifiedLoadTest: Boolean = false,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "benchmark_reports")
data class BenchmarkReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    val modelDisplayName: String,
    val engineType: String,
    val acceleratorUsed: String,
    val fellBackToCpu: Boolean,
    val corpusItemId: String,
    val corpusCategory: String,
    val speedFactor: Float,
    val modelLoadTimeMs: Long,
    val ttfaMs: Long,
    val totalSynthesisMs: Long,
    val audioDurationMs: Long,
    val realTimeFactor: Float,
    val ramBeforeLoadMb: Float,
    val ramAfterLoadMb: Float,
    val ramPeakSynthesisMb: Float,
    val deviceModel: String,
    val socOrAbi: String,
    val totalRamMb: Long,
    val androidVersion: String,
    val wavSamplePath: String?,
    val notes: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "translation_jobs")
data class TranslationJobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalFileName: String,
    val sourceType: String, // "TXT", "EPUB"
    val sourceLanguage: String, // "fr" or "en"
    val targetLanguage: String = "ar",
    val status: String, // "PENDING", "RUNNING", "PAUSED", "COMPLETED", "FAILED"
    val totalChunks: Int,
    val completedChunks: Int = 0,
    val jobEpoch: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "translation_chunks",
    foreignKeys = [
        ForeignKey(
            entity = TranslationJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("jobId", "chunkIndex")]
)
data class TranslationChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val chunkIndex: Int,
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val isHeading: Boolean = false,
    val originalText: String,
    val translatedText: String? = null,
    val isCompleted: Boolean = false
)

