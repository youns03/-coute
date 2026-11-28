package com.example.contracts

import android.net.Uri
import com.example.domain.DocumentExtractionOptions
import com.example.domain.ExportMode
import com.example.domain.FileInspectionPreview
import com.example.domain.ImportProgressEvent
import com.example.domain.ImportedBookDraft
import com.example.domain.ParsedSourceDocument
import com.example.domain.Sentence
import com.example.domain.SourceLanguage
import com.example.domain.TranslationChunk
import com.example.domain.TranslationDirection
import com.example.domain.TranslationJob
import com.example.domain.TranslationManagerUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DocumentImportGateway {
    suspend fun inspectUri(uri: Uri): FileInspectionPreview
    suspend fun inspectUris(uris: List<Uri>): List<FileInspectionPreview>
    suspend fun parseDocumentFromUri(
        uri: Uri,
        options: DocumentExtractionOptions = DocumentExtractionOptions(),
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<ParsedSourceDocument>

    suspend fun importFromUri(
        uri: Uri,
        options: DocumentExtractionOptions = DocumentExtractionOptions(),
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<ImportedBookDraft>

    suspend fun importDirectText(
        title: String,
        rawBody: String,
        languageCode: String? = null
    ): Result<ImportedBookDraft>
}

interface TranslationEngine {
    val direction: TranslationDirection
    val engineName: String
    suspend fun isModelReady(): Boolean
    suspend fun downloadModel(): Result<Unit>
    suspend fun deleteSourcePack(deleteSharedArabicTarget: Boolean): Result<Unit>
    suspend fun translateText(text: String): Result<String>
    fun close()
}

interface SentenceTranslationCacheStore {
    suspend fun updateSentenceTranslation(sentenceId: Long, translation: String)
}

interface VocabularyLookupStore {
    suspend fun findArabicTranslation(term: String): String?
}

interface TranslationJobStore {
    fun observeAllJobs(): Flow<List<TranslationJob>>
    suspend fun getJob(jobId: String): TranslationJob?
    fun observeChunks(jobId: String): Flow<List<TranslationChunk>>
    suspend fun getAllChunks(jobId: String): List<TranslationChunk>
    suspend fun getPendingChunks(jobId: String): List<TranslationChunk>
    suspend fun countCompletedChunks(jobId: String): Int
    suspend fun updateJob(job: TranslationJob)
    suspend fun insertJobWithChunks(job: TranslationJob, chunks: List<TranslationChunk>)
    suspend fun markChunkTranslatedIfEpochMatches(
        chunkId: Long,
        jobId: String,
        expectedEpoch: Int,
        translatedText: String
    ): Int

    suspend fun resetJobAtomically(jobId: String, sourceLanguage: String)
    suspend fun updateJobProgress(
        jobId: String,
        completedChunks: Int,
        status: String,
        lastError: String? = null
    )

    suspend fun updateJobProgressIfEpochMatches(
        jobId: String,
        expectedEpoch: Int,
        completedChunks: Int,
        status: String,
        lastError: String? = null
    ): Int

    suspend fun resetRunningJobsToPaused()
    suspend fun deleteJob(jobId: String)
}

interface TranslationGateway {
    val state: StateFlow<TranslationManagerUiState>

    fun getEngine(direction: TranslationDirection): TranslationEngine
    suspend fun refreshAllStatuses()
    suspend fun downloadPack(direction: TranslationDirection): Result<Unit>
    suspend fun deletePack(direction: TranslationDirection): Result<Unit>
    suspend fun isDirectionReady(direction: TranslationDirection): Boolean
    suspend fun translateRaw(direction: TranslationDirection, text: String): Result<String>
    suspend fun translateSentence(
        sentenceId: Long,
        sourceLanguage: SourceLanguage,
        originalText: String,
        cachedTranslation: String?
    ): Result<String>

    suspend fun translateParagraph(
        sentences: List<Sentence>,
        sourceLanguage: SourceLanguage
    ): Result<Int>

    suspend fun translateWord(sourceLanguage: SourceLanguage, rawWord: String): Result<String>
    fun closeAll()
}

interface FileTranslationGateway {
    fun observeAllJobs(): Flow<List<TranslationJob>>
    fun observeChunks(jobId: String): Flow<List<TranslationChunk>>
    suspend fun reconcileOnStartup()
    suspend fun inspectImportUris(uris: List<Uri>): List<FileInspectionPreview>
    suspend fun createJobFromUri(uri: Uri, selectedSourceLanguage: SourceLanguage?): Result<TranslationJob>
    suspend fun createJobFromUri(
        uri: Uri,
        selectedSourceLanguage: SourceLanguage?,
        options: DocumentExtractionOptions,
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<TranslationJob>

    suspend fun updateJobSourceLanguageIfEmpty(jobId: String, language: SourceLanguage): Boolean
    suspend fun resetAndChangeJobSourceLanguage(jobId: String, language: SourceLanguage)
    suspend fun startOrResumeJob(jobId: String)
    suspend fun pauseJob(jobId: String)
    suspend fun deleteJob(jobId: String)
    suspend fun exportJobToUri(jobId: String, targetUri: Uri, mode: ExportMode): Result<String>
}
