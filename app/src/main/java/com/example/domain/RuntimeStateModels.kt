package com.example.domain

enum class LocalTaskType(val labelAr: String) {
    TTS_PLAYBACK("استماع صوتي"),
    FILE_TRANSLATION("ترجمة ملف"),
    AUDIO_TRANSCRIPTION("تفريغ صوتي"),
    MODEL_DOWNLOAD("تنزيل نموذج"),
    BENCHMARK("اختبار الأداء")
}

enum class LocalTaskPriority(val rank: Int) {
    INTERACTIVE(0),
    HIGH(1),
    NORMAL(2),
    BACKGROUND(3)
}

enum class LocalTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class LocalTaskEntry(
    val id: String,
    val type: LocalTaskType,
    val title: String,
    val subtitle: String = "",
    val progress: Float = 0f,
    val status: LocalTaskStatus = LocalTaskStatus.QUEUED,
    val priority: LocalTaskPriority = LocalTaskPriority.NORMAL,
    val errorMessage: String? = null,
    val canPause: Boolean = false,
    val canCancel: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = status == LocalTaskStatus.QUEUED || status == LocalTaskStatus.RUNNING

    val isTerminal: Boolean
        get() = status == LocalTaskStatus.COMPLETED ||
            status == LocalTaskStatus.FAILED ||
            status == LocalTaskStatus.CANCELLED
}

data class MemoryGovernorSnapshot(
    val processPssMb: Float = 0f,
    val availableRamMb: Long = 0L,
    val isTtsEngineLoaded: Boolean = false,
    val isAsrEngineLoaded: Boolean = false,
    val lastCleanupReason: String? = null,
    val lastCleanupAtMs: Long = 0L
)

data class ModelUiEntry(
    val spec: ModelSpec,
    val status: ModelInstallStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val verifiedSha256: Boolean,
    val verifiedLoadTest: Boolean,
    val lastError: String?,
    val isActive: Boolean,
    val compatibility: ModelCompatibilityReport = ModelCompatibilityReport(
        status = ModelCompatibilityStatus.COMPATIBLE,
        isSupported = true,
        summaryAr = "متوافق"
    )
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val remainingBytes: Long
        get() = (totalBytes - downloadedBytes).coerceAtLeast(0L)
}

data class EngineRuntimeState(
    val loadedModelId: String? = null,
    val loadedModelName: String? = null,
    val engineType: EngineType? = null,
    val acceleratorLabel: String = "غير محمل",
    val fellBackToCpu: Boolean = false,
    val fallbackReason: String? = null,
    val lastLoadTimeMs: Long = 0L,
    val ramBeforeLoadMb: Float = 0f,
    val ramAfterLoadMb: Float = 0f,
    val ramPeakSynthesisMb: Float = 0f,
    val isLoading: Boolean = false,
    val lastError: String? = null
)

data class TranscriptionRuntimeState(
    val isTranscribing: Boolean = false,
    val loadedAsrModelId: String? = null,
    val activeFileTitle: String = "",
    val progress: Float = 0f,
    val statusMessage: String = "",
    val lastError: String? = null,
    val lastCompletedBookId: String? = null
)

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

data class PlaybackUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val sentenceIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val totalSentencesInChapter: Int = 0,
    val currentSentenceText: String = "",
    val activeWordStartChar: Int = -1,
    val activeWordEndChar: Int = -1,
    val playbackSpeed: Float = 1.0f,
    val repeatCurrentSentence: Boolean = false,
    val bufferedAheadCount: Int = 0,
    val activeModelName: String = "",
    val activeVoiceId: String = "",
    val activeLanguageCode: String = "fr",
    val sleepTimerRemainingSec: Int = 0,
    val sleepTimerEndOfChapter: Boolean = false,
    val errorMessage: String? = null
)

data class BenchmarkLabUiState(
    val isRunning: Boolean = false,
    val currentStepLabel: String = "",
    val lastError: String? = null,
    val playingSamplePath: String? = null
)

data class WordLookupSheetState(
    val selectedWord: String,
    val contextSentence: Sentence,
    val sourceLanguage: SourceLanguage,
    val translation: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedToVocab: Boolean = false
)

data class ReaderUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val activeChapterIndex: Int = 0,
    val sentences: List<Sentence> = emptyList(),
    val searchResults: List<Sentence> = emptyList(),
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val translatingSentenceIds: Set<Long> = emptySet(),
    val translatingParagraphIndices: Set<Int> = emptySet(),
    val paragraphTranslations: Map<Int, String> = emptyMap(),
    val revealedSentenceIds: Set<Long> = emptySet(),
    val translationError: String? = null
)
