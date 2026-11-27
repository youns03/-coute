package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPlaybackController
import com.example.audio.AudioTrackHandle
import com.example.audio.AudioTrackHandleFactory
import com.example.audio.PlaybackStatus
import com.example.data.AppDatabase
import com.example.data.BookEntity
import com.example.data.BookImporter
import com.example.data.ChapterEntity
import com.example.data.LibraryRepository
import com.example.data.SentenceEntity
import com.example.data.SettingsRepository
import com.example.domain.AcceleratorMode
import com.example.domain.EngineType
import com.example.domain.FrenchTextPreprocessor
import com.example.domain.ModelSpec
import com.example.domain.SpeechTextProcessor
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.runtime.LocalTaskCoordinator
import com.example.runtime.LocalTaskPriority
import com.example.runtime.LocalTaskStatus
import com.example.runtime.LocalTaskType
import com.example.runtime.RuntimeMemoryGovernor
import com.example.runtime.TaskControls
import com.example.transcription.AudioDecoder
import com.example.transcription.OfflineAsrSession
import com.example.transcription.SpeechTranscriptionManager
import com.example.tts.TtsEngine
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhaseFiveBackendAndUiTests {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storageManager: ModelStorageManager
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = ModelStorageManager(context)
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // 1. LocalTaskCoordinator tests
    // -------------------------------------------------------------------------
    @Test
    fun `task coordinator serializes heavy inference tasks and supports pause cancel retry clear`(): Unit = runBlocking {
        val coordinator = LocalTaskCoordinator()
        val concurrentCount = AtomicInteger(0)
        val maxObservedConcurrent = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()

        coordinator.launchManagedTask(
            id = "heavy_1",
            type = LocalTaskType.AUDIO_TRANSCRIPTION,
            title = "تفريغ أول",
            priority = LocalTaskPriority.HIGH,
            requiresHeavyInferenceLock = true
        ) {
            val active = concurrentCount.incrementAndGet()
            maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, active) }
            firstEntered.complete(Unit)
            firstGate.await()
            concurrentCount.decrementAndGet()
        }

        withTimeout(3_000) { firstEntered.await() }

        coordinator.launchManagedTask(
            id = "heavy_2",
            type = LocalTaskType.BENCHMARK,
            title = "اختبار أداء",
            priority = LocalTaskPriority.BACKGROUND,
            requiresHeavyInferenceLock = true
        ) {
            secondStarted.complete(Unit)
            val active = concurrentCount.incrementAndGet()
            maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, active) }
            delay(20L)
            concurrentCount.decrementAndGet()
            secondDone.complete(Unit)
        }

        // Give heavy_2 coroutine time to reach heavyInferenceMutex and verify it stays blocked
        withTimeout(3_000) {
            coordinator.tasks.first { list ->
                list.any { it.id == "heavy_1" && it.status == LocalTaskStatus.RUNNING } &&
                    list.any { it.id == "heavy_2" && it.status == LocalTaskStatus.QUEUED }
            }
        }
        delay(40L)
        assertFalse("Second heavy task must wait on heavyInferenceMutex", secondStarted.isCompleted)

        firstGate.complete(Unit)
        withTimeout(3_000) { secondDone.await() }
        assertEquals(1, maxObservedConcurrent.get())

        withTimeout(3_000) {
            coordinator.tasks.first { list ->
                list.firstOrNull { it.id == "heavy_1" }?.status == LocalTaskStatus.COMPLETED &&
                    list.firstOrNull { it.id == "heavy_2" }?.status == LocalTaskStatus.COMPLETED
            }
        }

        // Pause/Resume/Cancel/Retry external + managed tasks
        var pauseCalled = false
        var resumeCalled = false
        coordinator.upsertExternalTask(
            entry = com.example.runtime.LocalTaskEntry(
                id = "ext_tts",
                type = LocalTaskType.TTS_PLAYBACK,
                title = "قراءة فصل",
                status = LocalTaskStatus.RUNNING,
                canPause = true
            ),
            controls = TaskControls(
                onPause = { pauseCalled = true },
                onResume = { resumeCalled = true }
            )
        )
        coordinator.pauseTask("ext_tts")
        assertTrue(pauseCalled)
        assertEquals(LocalTaskStatus.PAUSED, coordinator.tasks.value.first { it.id == "ext_tts" }.status)

        coordinator.resumeTask("ext_tts")
        assertTrue(resumeCalled)

        coordinator.cancelTask("ext_tts")
        assertEquals(LocalTaskStatus.CANCELLED, coordinator.tasks.value.first { it.id == "ext_tts" }.status)

        coordinator.clearFinished()
        assertTrue(coordinator.tasks.value.none { it.isTerminal })
    }

    // -------------------------------------------------------------------------
    // 2. RuntimeMemoryGovernor tests
    // -------------------------------------------------------------------------
    @Test
    fun `memory governor unloads idle engines without interrupting busy inference`(): Unit = runBlocking {
        var now = 1_000L
        var ttsLoaded = true
        var ttsBusy = true
        var ttsReleases = 0
        var asrLoaded = true
        var asrBusy = false
        var asrReleases = 0

        val governor = RuntimeMemoryGovernor(
            context = context,
            idleTimeoutMs = 5_000L,
            nowProvider = { now },
            isTtsBusyProvider = { ttsBusy },
            isTtsLoadedProvider = { ttsLoaded },
            releaseTtsEngine = { ttsReleases++; ttsLoaded = false },
            isAsrBusyProvider = { asrBusy },
            isAsrLoadedProvider = { asrLoaded },
            releaseAsrEngine = { asrReleases++; asrLoaded = false }
        )
        governor.markTtsActivity()
        governor.markAsrActivity()

        // Advance past timeout: TTS is busy -> must NOT be unloaded; ASR is idle -> MUST unload
        now += 6_000L
        governor.evaluateAndEnforce()
        assertTrue("Busy TTS must not be interrupted", ttsLoaded)
        assertEquals(0, ttsReleases)
        assertFalse("Idle ASR must be unloaded after timeout", asrLoaded)
        assertEquals(1, asrReleases)

        // Now TTS finishes playback and OS signals TRIM_MEMORY_RUNNING_LOW
        ttsBusy = false
        governor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertFalse("Idle TTS must unload on TRIM_MEMORY_RUNNING_LOW", ttsLoaded)
        assertEquals(1, ttsReleases)
        assertNotNull(governor.snapshot.value.lastCleanupReason)
    }

    // -------------------------------------------------------------------------
    // 3. Room v4 -> v5 Migration test
    // -------------------------------------------------------------------------
    @Test
    fun `migration 4 to 5 preserves legacy books adds new columns and recent_activities table`(): Unit = runBlocking {
        val dbName = "migration_v4_to_v5_test.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `books` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `author` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL,
                                `sourceLanguage` TEXT NOT NULL,
                                `levelTag` TEXT NOT NULL,
                                `totalChapters` INTEGER NOT NULL,
                                `totalSentences` INTEGER NOT NULL,
                                `currentChapterIndex` INTEGER NOT NULL,
                                `currentSentenceIndex` INTEGER NOT NULL,
                                `addedAt` INTEGER NOT NULL,
                                `lastReadAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `chapters` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `bookId` TEXT NOT NULL,
                                `chapterIndex` INTEGER NOT NULL,
                                `title` TEXT NOT NULL,
                                `sentenceCount` INTEGER NOT NULL,
                                FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `sentences` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `bookId` TEXT NOT NULL,
                                `chapterIndex` INTEGER NOT NULL,
                                `sentenceIndex` INTEGER NOT NULL,
                                `paragraphIndex` INTEGER NOT NULL,
                                `originalText` TEXT NOT NULL,
                                `spokenText` TEXT NOT NULL,
                                `cachedArabicTranslation` TEXT,
                                FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sentences_bookId_chapterIndex_sentenceIndex` ON `sentences` (`bookId`, `chapterIndex`, `sentenceIndex`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `vocabulary` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `frenchTerm` TEXT NOT NULL,
                                `arabicTranslation` TEXT NOT NULL,
                                `contextSentence` TEXT NOT NULL,
                                `bookTitle` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE TABLE IF NOT EXISTS `model_records` (`modelId` TEXT NOT NULL, `status` TEXT NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `installedDirPath` TEXT, `verifiedSha256` INTEGER NOT NULL, `verifiedLoadTest` INTEGER NOT NULL, `lastError` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`modelId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `benchmark_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `modelId` TEXT NOT NULL, `modelDisplayName` TEXT NOT NULL, `engineType` TEXT NOT NULL, `acceleratorUsed` TEXT NOT NULL, `fellBackToCpu` INTEGER NOT NULL, `corpusItemId` TEXT NOT NULL, `corpusCategory` TEXT NOT NULL, `speedFactor` REAL NOT NULL, `modelLoadTimeMs` INTEGER NOT NULL, `ttfaMs` INTEGER NOT NULL, `totalSynthesisMs` INTEGER NOT NULL, `audioDurationMs` INTEGER NOT NULL, `realTimeFactor` REAL NOT NULL, `ramBeforeLoadMb` REAL NOT NULL, `ramAfterLoadMb` REAL NOT NULL, `ramPeakSynthesisMb` REAL NOT NULL, `deviceModel` TEXT NOT NULL, `socOrAbi` TEXT NOT NULL, `totalRamMb` INTEGER NOT NULL, `androidVersion` TEXT NOT NULL, `wavSamplePath` TEXT, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `translation_jobs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `originalFileName` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceLanguage` TEXT NOT NULL, `targetLanguage` TEXT NOT NULL, `status` TEXT NOT NULL, `totalChunks` INTEGER NOT NULL, `completedChunks` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `jobEpoch` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `translation_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, `paragraphIndex` INTEGER NOT NULL, `isHeading` INTEGER NOT NULL, `originalText` TEXT NOT NULL, `translatedText` TEXT, `isCompleted` INTEGER NOT NULL, FOREIGN KEY(`jobId`) REFERENCES `translation_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chunkIndex` ON `translation_chunks` (`jobId`, `chunkIndex`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `paragraph_translations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `paragraphIndex` INTEGER NOT NULL, `sourceLanguage` TEXT NOT NULL, `arabicTranslation` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_paragraph_translations_bookId_chapterIndex_paragraphIndex_sourceLanguage` ON `paragraph_translations` (`bookId`, `chapterIndex`, `paragraphIndex`, `sourceLanguage`)")

                        // Insert a legacy v4 book
                        db.execSQL(
                            """
                            INSERT INTO `books` (`id`, `title`, `author`, `sourceType`, `sourceLanguage`, `levelTag`, `totalChapters`, `totalSentences`, `currentChapterIndex`, `currentSentenceIndex`, `addedAt`, `lastReadAt`)
                            VALUES ('legacy_1', 'Le Petit Prince', 'Saint-Exupéry', 'EPUB', 'fr', 'B1', 2, 20, 1, 5, 1000, 2000)
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        // Open with Room v5 + MIGRATION_4_5
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedBook = migratedDb.bookDao().getBook("legacy_1")
            assertNotNull(migratedBook)
            assertEquals("Le Petit Prince", migratedBook!!.title)
            assertEquals("BOOK", migratedBook.mediaKind)
            assertEquals("auto", migratedBook.coverStyleId)
            assertEquals(0L, migratedBook.fileSizeBytes)
            assertFalse(migratedBook.isCompleted)
            assertEquals(0L, migratedBook.lastPositionMs)
            assertNull(migratedBook.coverImagePath)

            // Verify recent_activities table is usable
            migratedDb.recentActivityDao().upsert(
                com.example.data.RecentActivityEntity(
                    id = "act_1",
                    kind = "READ",
                    title = "Le Petit Prince",
                    subtitle = "متابعة القراءة",
                    targetRoute = "reader",
                    targetId = "legacy_1",
                    progress = 0.5f
                )
            )
            val activities = migratedDb.recentActivityDao().observeRecent().first()
            assertEquals(1, activities.size)
            assertEquals("legacy_1", activities.first().targetId)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    // -------------------------------------------------------------------------
    // 4. BookImporter & LibraryRepository (DOCX, Direct Text, SHA-256 Dedup, Covers/Rename)
    // -------------------------------------------------------------------------
    @Test
    fun `importer handles valid docx direct text sha256 deduplication and cover updates`(): Unit = runBlocking {
        val importer = BookImporter(context)
        val repo = LibraryRepository(
            bookDao = db.bookDao(),
            vocabularyDao = db.vocabularyDao(),
            bookImporter = importer,
            context = context,
            recentActivityDao = db.recentActivityDao()
        )

        // Build a minimal OOXML .docx archive
        val docxFile = File(context.cacheDir, "chapitre_sample.docx")
        ZipOutputStream(FileOutputStream(docxFile)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"></Types>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Bonjour à tous dans ce nouveau document.</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Nous testons l'extraction DOCX hors ligne.</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()
        }

        val uri = Uri.fromFile(docxFile)
        val firstOutcome = repo.importBookChecked(uri).getOrThrow()
        assertFalse(firstOutcome.wasDuplicate)
        assertEquals("DOCX", firstOutcome.book.sourceType)
        assertTrue(firstOutcome.book.totalSentences >= 2)
        assertFalse(firstOutcome.book.sourceContentHash.isNullOrBlank())

        // Duplicate import with exact same bytes must be detected without duplicating DB entry
        val secondOutcome = repo.importBookChecked(uri).getOrThrow()
        assertTrue(secondOutcome.wasDuplicate)
        assertEquals(firstOutcome.book.id, secondOutcome.book.id)

        // Direct text import + deduplication
        val directRes1 = repo.importDirectText(
            title = "ملاحظة فرنسية",
            body = "Paris est magnifique au printemps. Les cafés sont pleins de vie.",
            languageCode = "fr"
        ).getOrThrow()
        assertFalse(directRes1.wasDuplicate)
        assertEquals("TEXT", directRes1.book.sourceType)

        val directRes2 = repo.importDirectText(
            title = "ملاحظة مكررة",
            body = "Paris est magnifique au printemps. Les cafés sont pleins de vie.",
            languageCode = "fr"
        ).getOrThrow()
        assertTrue(directRes2.wasDuplicate)
        assertEquals(directRes1.book.id, directRes2.book.id)

        // Rename and update cover template
        repo.renameBook(directRes1.book.id, "مقهى باريس")
        repo.updateBookCoverTemplate(directRes1.book.id, "botanical_parchment")
        val updated = db.bookDao().getBook(directRes1.book.id)!!
        assertEquals("مقهى باريس", updated.title)
        assertEquals("botanical_parchment", updated.coverStyleId)

        docxFile.delete()
    }

    // -------------------------------------------------------------------------
    // 5. SpeechTextProcessor prosody & clause splitting tests
    // -------------------------------------------------------------------------
    @Test
    fun `preprocessor normalizes french roman numerals and splits long spoken clauses cleanly`() {
        val original = "Chapitre IV : Le voyage commence… avec prudence (sans précipitation) et courage."
        val spoken = FrenchTextPreprocessor.prepareForSpeech(original)

        assertTrue("Roman numeral IV should expand in French headings", spoken.contains("quatre"))
        assertTrue("Original text remains untouched", original.contains("IV"))
        assertTrue("Ellipsis and parentheses should normalize into clean prosody pauses", spoken.contains("commence, avec prudence, sans précipitation, et courage."))

        val longSpoken = (1..45).joinToString(", ") { "un passage très détaillé numéro $it" }
        val clauses = SpeechTextProcessor.splitLongSpokenClauses(longSpoken, maxChars = 220)
        assertTrue(clauses.size >= 3)
        clauses.forEach { chunk ->
            assertTrue("Clause length ${chunk.length} exceeded bound", chunk.length <= 220)
            assertTrue(chunk.isNotBlank())
        }
    }

    // -------------------------------------------------------------------------
    // 6. AudioPlaybackController Paragraph Jump, Seek ±15s, Sleep Timer, Voice Picker
    // -------------------------------------------------------------------------
    @Test
    fun `playback controller supports paragraph jump 15s seek sleep timer and voice selection`(): Unit = runBlocking {
        val fakeBookId = "pb_nav_book"
        val sentences = listOf(
            SentenceEntity(bookId = fakeBookId, chapterIndex = 0, sentenceIndex = 0, paragraphIndex = 0, originalText = "P0 S0.", spokenText = "P0 S0."),
            SentenceEntity(bookId = fakeBookId, chapterIndex = 0, sentenceIndex = 1, paragraphIndex = 0, originalText = "P0 S1.", spokenText = "P0 S1."),
            SentenceEntity(bookId = fakeBookId, chapterIndex = 0, sentenceIndex = 2, paragraphIndex = 0, originalText = "P0 S2.", spokenText = "P0 S2."),
            SentenceEntity(bookId = fakeBookId, chapterIndex = 0, sentenceIndex = 3, paragraphIndex = 1, originalText = "P1 S3.", spokenText = "P1 S3."),
            SentenceEntity(bookId = fakeBookId, chapterIndex = 0, sentenceIndex = 4, paragraphIndex = 2, originalText = "P2 S4.", spokenText = "P2 S4.")
        )
        db.bookDao().insertFullBook(
            book = BookEntity(
                id = fakeBookId,
                title = "Nav Book",
                author = "Author",
                sourceType = "TXT",
                sourceLanguage = "fr",
                totalChapters = 1,
                totalSentences = sentences.size
            ),
            chapters = listOf(ChapterEntity(bookId = fakeBookId, chapterIndex = 0, title = "Ch 1", sentenceCount = sentences.size)),
            sentences = sentences
        )

        val fakeEngineManager = TtsEngineManager(
            context = context,
            storageManager = storageManager,
            engineFactory = { spec ->
                object : TtsEngine {
                    override val engineType: EngineType = spec.engineType
                    override val modelId: String = spec.id
                    override val sampleRateHz: Int = 24_000
                    override var isLoaded: Boolean = false
                    override val activeAccelerator: String = "CPU Test"
                    override val fellBackToCpu: Boolean = false
                    override val fallbackReason: String? = null

                    override suspend fun initialize(spec: ModelSpec, modelDir: File, acceleratorMode: AcceleratorMode): Result<Long> {
                        isLoaded = true
                        return Result.success(1L)
                    }

                    override suspend fun synthesize(spokenText: String, voiceId: String, speed: Float, locale: String): Result<TtsSynthesisResult> {
                        return Result.success(
                            TtsSynthesisResult(
                                samples = FloatArray(4800) { 0.05f },
                                sampleRateHz = 24_000,
                                wordTimestamps = emptyList(),
                                synthesisTimeMs = 5L,
                                usedAccelerator = "CPU Test",
                                fellBackToCpu = false,
                                fallbackReason = null
                            )
                        )
                    }

                    override fun synthesizeStream(spokenText: String, voiceId: String, speed: Float, locale: String): Flow<TtsStreamChunk> = flow {
                        delay(80L)
                        emit(TtsStreamChunk(FloatArray(4800) { 0.05f }, 24_000, emptyList(), true))
                    }

                    override fun cancel() {}
                    override fun release() { isLoaded = false }
                }
            },
            packageVerifier = { spec -> spec.id == "piper-fr-siwis-medium" }
        )

        val fakeTrackFactory = AudioTrackHandleFactory {
            val handle = object : AudioTrackHandle {
                override val isInitialized: Boolean = true
                override var playState: Int = android.media.AudioTrack.PLAYSTATE_STOPPED
                override var playbackHeadPosition: Int = 0
                override fun play() { playState = android.media.AudioTrack.PLAYSTATE_PLAYING }
                override fun pause() { playState = android.media.AudioTrack.PLAYSTATE_PAUSED }
                override fun flush() {}
                override fun stop() { playState = android.media.AudioTrack.PLAYSTATE_STOPPED }
                override fun release() { playState = android.media.AudioTrack.PLAYSTATE_STOPPED }
                override fun setSpeed(speed: Float) {}
                override fun writeFloat(data: FloatArray, offset: Int, count: Int): Int {
                    playbackHeadPosition += count
                    return count
                }
                override fun writePcm16(data: ShortArray, offset: Int, count: Int): Int {
                    playbackHeadPosition += count
                    return count
                }
            }
            handle to false
        }

        val controller = AudioPlaybackController(
            context = context,
            bookDao = db.bookDao(),
            settingsRepository = settingsRepository,
            storageManager = storageManager,
            ttsEngineManager = fakeEngineManager,
            trackFactory = fakeTrackFactory,
            playbackDispatcher = Dispatchers.Default,
            recentActivityDao = db.recentActivityDao()
        )

        controller.playChapterFrom(fakeBookId, "Nav Book", 0, "Ch 1", 0, "fr")
        withTimeout(5_000) {
            controller.state.first { it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.BUFFERING }
        }

        // Next paragraph: from para 0 -> sentence index 3 (para 1)
        controller.nextParagraph()
        withTimeout(5_000) {
            controller.state.first { it.sentenceIndex == 3 && it.paragraphIndex == 1 }
        }

        // Next paragraph again -> sentence index 4 (para 2)
        controller.nextParagraph()
        withTimeout(5_000) {
            controller.state.first { it.sentenceIndex == 4 && it.paragraphIndex == 2 }
        }

        // Seek -15s (~3 sentences back) -> should jump near index 1
        controller.seekRelativeSeconds(-15)
        withTimeout(5_000) {
            controller.state.first { it.sentenceIndex <= 2 }
        }

        // Sleep timer set & cancel
        controller.startSleepTimerMinutes(15)
        assertTrue(controller.state.value.sleepTimerRemainingSec in 898..900)
        controller.cancelSleepTimer()
        assertEquals(0, controller.state.value.sleepTimerRemainingSec)

        // End of chapter timer flag
        controller.startEndOfChapterSleepTimer()
        assertTrue(controller.state.value.sleepTimerEndOfChapter)
        controller.cancelSleepTimer()

        // Active voice update
        controller.setActiveVoice("af_heart")
        assertEquals("af_heart", controller.state.value.activeVoiceId)

        controller.stop()
    }

    // -------------------------------------------------------------------------
    // 7. AudioDecoder + SpeechTranscriptionManager offline pipeline tests
    // -------------------------------------------------------------------------
    @Test
    fun `audio decoder handles stereo 44k wav and speech transcription persists audio book`(): Unit = runBlocking {
        // Generate synthetic 2-second 44.1kHz stereo PCM16 WAV file
        val wavFile = File(context.cacheDir, "sample_voice.wav")
        writeSyntheticPcm16Wav(wavFile, sampleRate = 44_100, channels = 2, durationSec = 2.0f)

        val decoded = AudioDecoder.decodeFileToMono16k(wavFile)
        assertEquals(16_000, decoded.sampleRateHz)
        assertEquals(44_100, decoded.originalSampleRateHz)
        assertEquals(2, decoded.originalChannels)
        assertTrue(decoded.samples16k.size in 31_000..33_000)
        assertTrue(decoded.durationMs in 1900L..2100L)

        // Segmentation splits 50 seconds of audio into bounded <=24s speech windows at quiet gaps
        val longWave = FloatArray(50 * 16_000) { idx ->
            val sec = idx / 16_000
            if (sec == 22 || sec == 45) 0.0001f else 0.25f * sin(2.0 * PI * 220.0 * idx / 16_000.0).toFloat()
        }
        val windows = SpeechTranscriptionManager.segmentSpeechWindows(longWave, 16_000)
        assertTrue("50s audio should segment into multiple chunks", windows.size >= 2)
        windows.forEach { win ->
            assertTrue("Window exceeded 24s cap", win.size <= 24 * 16_000)
        }

        val coordinator = LocalTaskCoordinator()

        // Case A: No ASR model installed -> clear failure message
        val managerNoModel = SpeechTranscriptionManager(
            context = context,
            storageManager = storageManager,
            bookDao = db.bookDao(),
            recentActivityDao = db.recentActivityDao(),
            taskCoordinator = coordinator,
            settingsRepository = settingsRepository,
            packageVerifier = { false }
        )
        val noModelRes = managerNoModel.importAndTranscribeBlocking(Uri.fromFile(wavFile), "fr")
        assertTrue(noModelRes.isFailure)
        assertTrue(noModelRes.exceptionOrNull()?.message.orEmpty().contains("التعرف على الكلام"))

        // Case B: Installed ASR model + fake OfflineAsrSession persists AUDIO book + recent activity
        val asrSpec = ModelCatalog.asrModels.first()
        val fakeAsrDir = storageManager.getInstalledModelDir(asrSpec.id).apply { mkdirs() }
        File(fakeAsrDir, "model.int8.onnx").writeBytes(ByteArray(32))
        File(fakeAsrDir, "tokens.txt").writeText("a 0\n")

        val managerWithModel = SpeechTranscriptionManager(
            context = context,
            storageManager = storageManager,
            bookDao = db.bookDao(),
            recentActivityDao = db.recentActivityDao(),
            taskCoordinator = coordinator,
            settingsRepository = settingsRepository,
            sessionFactory = { _, _, _ ->
                object : OfflineAsrSession {
                    override fun transcribeSegment(samples16k: FloatArray, sampleRateHz: Int): String =
                        "Bonjour Paris. Ce fichier audio a été transcrit localement."

                    override fun release() {}
                }
            },
            packageVerifier = { it.id == asrSpec.id }
        )

        val okRes = managerWithModel.importAndTranscribeBlocking(Uri.fromFile(wavFile), "fr")
        assertTrue(okRes.isSuccess)
        val audioBook = okRes.getOrThrow()
        assertEquals("AUDIO", audioBook.mediaKind)
        assertEquals("AUDIO", audioBook.sourceType)
        assertEquals("audio_waves", audioBook.coverStyleId)
        assertTrue(audioBook.totalSentences >= 2)
        assertNotNull(audioBook.audioFilePath)
        assertTrue(File(audioBook.audioFilePath!!).exists())

        val storedSentences = db.bookDao().getChapterSentences(audioBook.id, 0)
        assertTrue(storedSentences.any { it.originalText.contains("Bonjour Paris.") })

        val activities = db.recentActivityDao().observeRecent().first()
        assertTrue(activities.any { it.kind == "TRANSCRIPTION" && it.targetId == audioBook.id })

        // Duplicate audio hash returns existing book instead of re-transcribing
        val dupRes = managerWithModel.importAndTranscribeBlocking(Uri.fromFile(wavFile), "fr")
        assertTrue(dupRes.isSuccess)
        assertEquals(audioBook.id, dupRes.getOrThrow().id)

        storageManager.deleteModelFiles(asrSpec.id)
        File(audioBook.audioFilePath!!).delete()
        wavFile.delete()
    }

    private fun writeSyntheticPcm16Wav(
        file: File,
        sampleRate: Int,
        channels: Int,
        durationSec: Float
    ) {
        val totalFrames = (sampleRate * durationSec).toInt()
        val dataBytes = totalFrames * channels * 2
        val baos = ByteArrayOutputStream(44 + dataBytes)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        baos.write(header.array())
        val pcmBuf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until totalFrames) {
            val sample = (sin(2.0 * PI * 440.0 * f / sampleRate) * 12_000.0).toInt().toShort()
            repeat(channels) { pcmBuf.putShort(sample) }
        }
        baos.write(pcmBuf.array())
        file.writeBytes(baos.toByteArray())
    }

    // -------------------------------------------------------------------------
    // 8. Default Obsidian Emerald Theme & Home UI Strings Regression Test
    // -------------------------------------------------------------------------
    @Test
    fun `default user settings use obsidian dark theme and home arabic strings match editorial design`(): Unit = runBlocking {
        val defaultSettings = settingsRepository.settingsFlow.first()
        assertTrue("Obsidian dark mode must be enabled by default", defaultSettings.nightMode)
        assertEquals("مرحباً بك", context.getString(R.string.home_greeting_title))
        assertEquals("تابع رحلتك في القراءة والاستماع", context.getString(R.string.home_greeting_subtitle))
        assertEquals("متابعة القراءة", context.getString(R.string.home_continue_section))
        assertEquals("تابع من حيث توقفت", context.getString(R.string.home_continue_sublabel))
        assertEquals("65% مكتمل", context.getString(R.string.home_percent_complete, 65))
        assertEquals("مكتبتي", context.getString(R.string.home_shelf_section))
        assertEquals("عرض الكل", context.getString(R.string.home_view_all))
        assertEquals("آخر الأنشطة", context.getString(R.string.home_recent_section))
    }
}
