package com.example.data

import com.example.domain.BenchmarkReport
import com.example.domain.Book
import com.example.domain.Chapter
import com.example.domain.ModelInstallRecord
import com.example.domain.ParagraphTranslation
import com.example.domain.RecentActivity
import com.example.domain.Sentence
import com.example.domain.TranslationChunk
import com.example.domain.TranslationJob
import com.example.domain.VocabularyItem

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    sourceType = sourceType,
    sourceLanguage = sourceLanguage,
    levelTag = levelTag,
    totalChapters = totalChapters,
    totalSentences = totalSentences,
    currentChapterIndex = currentChapterIndex,
    currentSentenceIndex = currentSentenceIndex,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    mediaKind = mediaKind,
    coverStyleId = coverStyleId,
    coverImagePath = coverImagePath,
    sourceContentHash = sourceContentHash,
    fileSizeBytes = fileSizeBytes,
    audioFilePath = audioFilePath,
    audioDurationMs = audioDurationMs,
    isCompleted = isCompleted,
    lastPositionMs = lastPositionMs
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    sourceType = sourceType,
    sourceLanguage = sourceLanguage,
    levelTag = levelTag,
    totalChapters = totalChapters,
    totalSentences = totalSentences,
    currentChapterIndex = currentChapterIndex,
    currentSentenceIndex = currentSentenceIndex,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    mediaKind = mediaKind,
    coverStyleId = coverStyleId,
    coverImagePath = coverImagePath,
    sourceContentHash = sourceContentHash,
    fileSizeBytes = fileSizeBytes,
    audioFilePath = audioFilePath,
    audioDurationMs = audioDurationMs,
    isCompleted = isCompleted,
    lastPositionMs = lastPositionMs
)

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    sentenceCount = sentenceCount
)

fun Chapter.toEntity(): ChapterEntity = ChapterEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    sentenceCount = sentenceCount
)

fun SentenceEntity.toDomain(): Sentence = Sentence(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    sentenceIndex = sentenceIndex,
    paragraphIndex = paragraphIndex,
    originalText = originalText,
    spokenText = spokenText,
    cachedArabicTranslation = cachedArabicTranslation
)

fun Sentence.toEntity(): SentenceEntity = SentenceEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    sentenceIndex = sentenceIndex,
    paragraphIndex = paragraphIndex,
    originalText = originalText,
    spokenText = spokenText,
    cachedArabicTranslation = cachedArabicTranslation
)

fun ParagraphTranslationEntity.toDomain(): ParagraphTranslation = ParagraphTranslation(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    sourceLanguage = sourceLanguage,
    arabicTranslation = arabicTranslation,
    updatedAt = updatedAt
)

fun VocabularyEntity.toDomain(): VocabularyItem = VocabularyItem(
    id = id,
    frenchTerm = frenchTerm,
    arabicTranslation = arabicTranslation,
    contextSentence = contextSentence,
    bookTitle = bookTitle,
    createdAt = createdAt
)

fun ModelRecordEntity.toDomain(): ModelInstallRecord = ModelInstallRecord(
    modelId = modelId,
    status = status,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    installedDirPath = installedDirPath,
    verifiedSha256 = verifiedSha256,
    verifiedLoadTest = verifiedLoadTest,
    lastError = lastError,
    updatedAt = updatedAt
)

fun ModelInstallRecord.toEntity(): ModelRecordEntity = ModelRecordEntity(
    modelId = modelId,
    status = status,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    installedDirPath = installedDirPath,
    verifiedSha256 = verifiedSha256,
    verifiedLoadTest = verifiedLoadTest,
    lastError = lastError,
    updatedAt = updatedAt
)

fun BenchmarkReportEntity.toDomain(): BenchmarkReport = BenchmarkReport(
    id = id,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    engineType = engineType,
    acceleratorUsed = acceleratorUsed,
    fellBackToCpu = fellBackToCpu,
    corpusItemId = corpusItemId,
    corpusCategory = corpusCategory,
    speedFactor = speedFactor,
    modelLoadTimeMs = modelLoadTimeMs,
    ttfaMs = ttfaMs,
    totalSynthesisMs = totalSynthesisMs,
    audioDurationMs = audioDurationMs,
    realTimeFactor = realTimeFactor,
    ramBeforeLoadMb = ramBeforeLoadMb,
    ramAfterLoadMb = ramAfterLoadMb,
    ramPeakSynthesisMb = ramPeakSynthesisMb,
    deviceModel = deviceModel,
    socOrAbi = socOrAbi,
    totalRamMb = totalRamMb,
    androidVersion = androidVersion,
    wavSamplePath = wavSamplePath,
    notes = notes,
    createdAt = createdAt
)

fun BenchmarkReport.toEntity(): BenchmarkReportEntity = BenchmarkReportEntity(
    id = id,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    engineType = engineType,
    acceleratorUsed = acceleratorUsed,
    fellBackToCpu = fellBackToCpu,
    corpusItemId = corpusItemId,
    corpusCategory = corpusCategory,
    speedFactor = speedFactor,
    modelLoadTimeMs = modelLoadTimeMs,
    ttfaMs = ttfaMs,
    totalSynthesisMs = totalSynthesisMs,
    audioDurationMs = audioDurationMs,
    realTimeFactor = realTimeFactor,
    ramBeforeLoadMb = ramBeforeLoadMb,
    ramAfterLoadMb = ramAfterLoadMb,
    ramPeakSynthesisMb = ramPeakSynthesisMb,
    deviceModel = deviceModel,
    socOrAbi = socOrAbi,
    totalRamMb = totalRamMb,
    androidVersion = androidVersion,
    wavSamplePath = wavSamplePath,
    notes = notes,
    createdAt = createdAt
)

fun TranslationJobEntity.toDomain(): TranslationJob = TranslationJob(
    id = id,
    title = title,
    originalFileName = originalFileName,
    sourceType = sourceType,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    status = status,
    totalChunks = totalChunks,
    completedChunks = completedChunks,
    jobEpoch = jobEpoch,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TranslationJob.toEntity(): TranslationJobEntity = TranslationJobEntity(
    id = id,
    title = title,
    originalFileName = originalFileName,
    sourceType = sourceType,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    status = status,
    totalChunks = totalChunks,
    completedChunks = completedChunks,
    jobEpoch = jobEpoch,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TranslationChunkEntity.toDomain(): TranslationChunk = TranslationChunk(
    id = id,
    jobId = jobId,
    chunkIndex = chunkIndex,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    isHeading = isHeading,
    originalText = originalText,
    translatedText = translatedText,
    isCompleted = isCompleted
)

fun TranslationChunk.toEntity(): TranslationChunkEntity = TranslationChunkEntity(
    id = id,
    jobId = jobId,
    chunkIndex = chunkIndex,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    isHeading = isHeading,
    originalText = originalText,
    translatedText = translatedText,
    isCompleted = isCompleted
)

fun RecentActivityEntity.toDomain(): RecentActivity = RecentActivity(
    id = id,
    kind = kind,
    title = title,
    subtitle = subtitle,
    targetRoute = targetRoute,
    targetId = targetId,
    progress = progress,
    timestamp = timestamp
)

fun RecentActivity.toEntity(): RecentActivityEntity = RecentActivityEntity(
    id = id,
    kind = kind,
    title = title,
    subtitle = subtitle,
    targetRoute = targetRoute,
    targetId = targetId,
    progress = progress,
    timestamp = timestamp
)
