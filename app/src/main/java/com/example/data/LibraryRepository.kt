package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.importengine.DocumentExtractionOptions
import com.example.data.importengine.FileInspectionPreview
import com.example.data.importengine.ImportProgressEvent
import com.example.domain.BenchmarkCorpus
import com.example.domain.SpeechTextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class BookImportOutcome(
    val book: BookEntity,
    val wasDuplicate: Boolean,
    val warningMessageAr: String? = null
)

class LibraryRepository(
    private val bookDao: BookDao,
    private val vocabularyDao: VocabularyDao,
    private val bookImporter: BookImporter,
    private val context: Context? = null,
    private val recentActivityDao: RecentActivityDao? = null
) {
    fun observeAllBooks(): Flow<List<BookEntity>> = bookDao.observeAllBooks()

    fun observeAllChapters(): Flow<List<ChapterEntity>> = bookDao.observeAllChapters()

    fun observeBook(bookId: String): Flow<BookEntity?> = bookDao.observeBook(bookId)

    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = bookDao.observeChapters(bookId)

    fun observeChapterSentences(bookId: String, chapterIndex: Int): Flow<List<SentenceEntity>> =
        bookDao.observeChapterSentences(bookId, chapterIndex)

    fun observeParagraphTranslations(
        bookId: String,
        chapterIndex: Int,
        sourceLanguage: String
    ): Flow<List<ParagraphTranslationEntity>> =
        bookDao.observeParagraphTranslations(bookId, chapterIndex, sourceLanguage)

    fun observeBookIdsWithTranslations(): Flow<List<String>> =
        bookDao.observeBookIdsWithParagraphTranslations()

    fun observeRecentActivities(): Flow<List<RecentActivityEntity>> =
        recentActivityDao?.observeRecent() ?: flowOf(emptyList())

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
                totalSentences = totalSentences,
                coverStyleId = if (seed.sourceLanguage == "en") "botanical_parchment" else "atelier_classic"
            )
            bookDao.insertFullBook(book, chapters, sentences)
        }
    }

    suspend fun inspectImportUris(uris: List<Uri>): List<FileInspectionPreview> =
        bookImporter.inspectUris(uris)

    suspend fun importBook(uri: Uri): Result<BookEntity> =
        importBookChecked(uri).map { it.book }

    suspend fun importBookChecked(uri: Uri): Result<BookImportOutcome> =
        importBookChecked(uri, DocumentExtractionOptions()) {}

    suspend fun importBookChecked(
        uri: Uri,
        options: DocumentExtractionOptions,
        onProgress: (ImportProgressEvent) -> Unit = {}
    ): Result<BookImportOutcome> {
        val parsed = bookImporter.importFromUri(uri, options, onProgress)
        if (parsed.isFailure) return Result.failure(parsed.exceptionOrNull()!!)
        val payload = parsed.getOrThrow()
        val hash = payload.book.sourceContentHash
        if (!hash.isNullOrBlank()) {
            val existing = bookDao.findByContentHash(hash)
            if (existing != null) {
                return Result.success(
                    BookImportOutcome(
                        book = existing,
                        wasDuplicate = true,
                        warningMessageAr = payload.diagnostics?.warningMessageAr
                    )
                )
            }
        }
        bookDao.insertFullBook(payload.book, payload.chapters, payload.sentences)
        recentActivityDao?.upsert(
            RecentActivityEntity(
                id = "act_book_${payload.book.id}",
                kind = "READ",
                title = payload.book.title,
                subtitle = "تمت إضافته للمكتبة (${payload.book.sourceType})",
                targetRoute = "reader",
                targetId = payload.book.id,
                progress = 0f
            )
        )
        return Result.success(
            BookImportOutcome(
                book = payload.book,
                wasDuplicate = false,
                warningMessageAr = payload.diagnostics?.warningMessageAr
            )
        )
    }

    suspend fun importDirectText(
        title: String,
        body: String,
        languageCode: String? = null
    ): Result<BookImportOutcome> {
        val parsed = bookImporter.importDirectText(title, body, languageCode)
        if (parsed.isFailure) return Result.failure(parsed.exceptionOrNull()!!)
        val payload = parsed.getOrThrow()
        val hash = payload.book.sourceContentHash
        if (!hash.isNullOrBlank()) {
            val existing = bookDao.findByContentHash(hash)
            if (existing != null) {
                return Result.success(BookImportOutcome(book = existing, wasDuplicate = true))
            }
        }
        bookDao.insertFullBook(payload.book, payload.chapters, payload.sentences)
        recentActivityDao?.upsert(
            RecentActivityEntity(
                id = "act_book_${payload.book.id}",
                kind = "READ",
                title = payload.book.title,
                subtitle = "نص مضاف يدوياً (${payload.book.totalSentences} جملة)",
                targetRoute = "reader",
                targetId = payload.book.id,
                progress = 0f
            )
        )
        return Result.success(BookImportOutcome(book = payload.book, wasDuplicate = false))
    }

    suspend fun renameBook(bookId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isNotEmpty()) {
            bookDao.renameBook(bookId, clean)
        }
    }

    suspend fun updateBookCoverTemplate(bookId: String, styleId: String) {
        val existing = bookDao.getBook(bookId)
        existing?.coverImagePath?.let { oldPath ->
            runCatching { File(oldPath).delete() }
        }
        bookDao.updateBookCover(bookId, styleId, null)
    }

    suspend fun updateBookCoverFromUri(bookId: String, imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val appCtx = context ?: error("سياق التطبيق غير متوفر لحفظ الغلاف.")
            val coversDir = File(appCtx.filesDir, "covers").apply { mkdirs() }
            val destFile = File(coversDir, "${bookId}_${System.currentTimeMillis()}.jpg")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appCtx.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: error("تعذر قراءة الصورة المختارة.")

            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (maxDim / sampleSize > 720) sampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = appCtx.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: error("تعذر فك ترميز الصورة المختارة.")

            val scaled = scaleDownBitmapIfNeeded(decoded, 512)
            FileOutputStream(destFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()

            val oldPath = bookDao.getBook(bookId)?.coverImagePath
            if (oldPath != null && oldPath != destFile.absolutePath) {
                runCatching { File(oldPath).delete() }
            }
            bookDao.updateBookCover(bookId, "custom_image", destFile.absolutePath)
            destFile.absolutePath
        }
    }

    private fun scaleDownBitmapIfNeeded(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxEdge && h <= maxEdge) return src
        val ratio = maxEdge.toFloat() / maxOf(w, h).toFloat()
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
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

    suspend fun saveParagraphTranslation(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        sourceLanguage: String,
        arabicTranslation: String
    ) {
        if (arabicTranslation.isBlank()) return
        bookDao.upsertParagraphTranslation(
            ParagraphTranslationEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                sourceLanguage = sourceLanguage,
                arabicTranslation = arabicTranslation.trim()
            )
        )
    }

    suspend fun updateBookSourceLanguage(bookId: String, sourceLanguage: String) {
        bookDao.updateBookSourceLanguage(bookId, sourceLanguage)
    }

    suspend fun deleteBook(bookId: String) {
        val book = bookDao.getBook(bookId)
        book?.coverImagePath?.let { path -> runCatching { File(path).delete() } }
        book?.audioFilePath?.let { path -> runCatching { File(path).delete() } }
        recentActivityDao?.deleteByTargetId(bookId)
        bookDao.deleteBook(bookId)
    }

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

    companion object {
        fun calculateBookProgress(book: BookEntity, bookChapters: List<ChapterEntity>): Float {
            if (book.isCompleted) return 1f
            val total = book.totalSentences.coerceAtLeast(bookChapters.sumOf { it.sentenceCount })
            if (total <= 0) return 0f
            val hasStarted = book.currentChapterIndex > 0 || book.currentSentenceIndex > 0
            if (!hasStarted) return 0f
            val priorChapterSentences = bookChapters
                .filter { it.chapterIndex < book.currentChapterIndex }
                .sumOf { it.sentenceCount }
            val currentOffset = (priorChapterSentences + book.currentSentenceIndex + 1)
                .coerceIn(0, total)
            return (currentOffset.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }
}

