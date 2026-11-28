package com.example.domain

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val sourceType: String,
    val sourceLanguage: String = "fr",
    val levelTag: String = "",
    val totalChapters: Int,
    val totalSentences: Int,
    val currentChapterIndex: Int = 0,
    val currentSentenceIndex: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis(),
    val mediaKind: String = "BOOK",
    val coverStyleId: String = "auto",
    val coverImagePath: String? = null,
    val sourceContentHash: String? = null,
    val fileSizeBytes: Long = 0L,
    val audioFilePath: String? = null,
    val audioDurationMs: Long = 0L,
    val isCompleted: Boolean = false,
    val lastPositionMs: Long = 0L
)

data class Chapter(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val sentenceCount: Int
)

data class Sentence(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val paragraphIndex: Int,
    val originalText: String,
    val spokenText: String,
    val cachedArabicTranslation: String? = null
)

data class ParagraphTranslation(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val sourceLanguage: String,
    val arabicTranslation: String,
    val updatedAt: Long = System.currentTimeMillis()
)

data class VocabularyItem(
    val id: Long = 0,
    val frenchTerm: String,
    val arabicTranslation: String,
    val contextSentence: String,
    val bookTitle: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ModelInstallRecord(
    val modelId: String,
    val status: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installedDirPath: String? = null,
    val verifiedSha256: Boolean = false,
    val verifiedLoadTest: Boolean = false,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class BenchmarkReport(
    val id: Long = 0,
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

data class TranslationJob(
    val id: String,
    val title: String,
    val originalFileName: String,
    val sourceType: String,
    val sourceLanguage: String,
    val targetLanguage: String = "ar",
    val status: String,
    val totalChunks: Int,
    val completedChunks: Int = 0,
    val jobEpoch: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class TranslationChunk(
    val id: Long = 0,
    val jobId: String,
    val chunkIndex: Int,
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val isHeading: Boolean = false,
    val originalText: String,
    val translatedText: String? = null,
    val isCompleted: Boolean = false
)

data class RecentActivity(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val targetRoute: String,
    val targetId: String,
    val progress: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserSettings(
    val activeModelId: String = "kokoro-82m-gpu-fp16",
    val activeVoiceId: String = "",
    val activeAsrModelId: String = "sherpa-asr-nemo-fast-conformer-fr-en",
    val acceleratorMode: AcceleratorMode = AcceleratorMode.AUTO_GPU_WITH_CPU_FALLBACK,
    val playbackSpeed: Float = 1.0f,
    val speedControlMode: SpeedControlMode = SpeedControlMode.AUDIO_TRACK_TIME_STRETCH,
    val readerFontSizeSp: Float = 20f,
    val readerLineHeightMultiplier: Float = 1.65f,
    val nightMode: Boolean = true,
    val showInlineTranslations: Boolean = false,
    val activeBookId: String? = null,
    val libraryViewMode: String = "GRID",
    val librarySortOrder: String = "RECENT",
    val lastRouteName: String = "Home"
)

data class ImportedBookDraft(
    val book: Book,
    val chapters: List<Chapter>,
    val sentences: List<Sentence>,
    val diagnostics: ExtractionDiagnostics? = null
)

data class BookImportOutcome(
    val book: Book,
    val wasDuplicate: Boolean,
    val warningMessageAr: String? = null
)

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
