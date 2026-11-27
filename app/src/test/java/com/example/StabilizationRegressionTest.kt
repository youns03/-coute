package com.example

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
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
import com.example.data.ParagraphTranslationEntity
import com.example.data.SentenceEntity
import com.example.data.SettingsRepository
import com.example.domain.AcceleratorMode
import com.example.domain.ArchiveType
import com.example.domain.ModelSpec
import com.example.domain.TtsStreamChunk
import com.example.domain.TtsSynthesisResult
import com.example.models.ModelCatalog
import com.example.models.ModelStorageManager
import com.example.translation.TranslationDirection
import com.example.translation.TranslationEngine
import com.example.tts.PiperNativeSession
import com.example.tts.PiperSessionFactory
import com.example.tts.PiperSherpaTtsEngine
import com.example.tts.TtsEngine
import com.example.tts.TtsEngineManager
import com.example.ui.viewmodel.PlayerAndLabViewModel
import com.example.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StabilizationRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var storageManager: ModelStorageManager
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Shadows.shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = ModelStorageManager(context)
        settingsRepo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
        storageManager.clearAudioCache()
    }

    private suspend fun realDelay(ms: Long) {
        withContext(Dispatchers.IO) { delay(ms) }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            realDelay(20L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private class RecordingAudioTrackHandle : AudioTrackHandle {
        @Volatile
        var released: Boolean = false
        @Volatile
        var playing: Boolean = false
        @Volatile
        var paused: Boolean = false
        @Volatile
        var writtenFrames: Int = 0

        override val isInitialized: Boolean get() = !released
        override val playState: Int
            get() = when {
                released -> AudioTrack.PLAYSTATE_STOPPED
                paused -> AudioTrack.PLAYSTATE_PAUSED
                playing -> AudioTrack.PLAYSTATE_PLAYING
                else -> AudioTrack.PLAYSTATE_STOPPED
            }
        override val playbackHeadPosition: Int get() = writtenFrames

        override fun play() {
            if (!released) {
                playing = true
                paused = false
            }
        }

        override fun pause() {
            if (!released) {
                paused = true
                playing = false
            }
        }

        override fun flush() {}
        override fun stop() {
            playing = false
        }

        override fun release() {
            released = true
            playing = false
        }

        override fun setSpeed(speed: Float) {}

        override fun writeFloat(data: FloatArray, offset: Int, count: Int): Int {
            if (released) return -1
            writtenFrames += count
            return count
        }

        override fun writePcm16(data: ShortArray, offset: Int, count: Int): Int {
            if (released) return -1
            writtenFrames += count
            return count
        }
    }

    @Test
    @Config(sdk = [24])
    fun `audio playback pauses on transient focus loss and abandons legacy audio focus on API 24`(): Unit = runBlocking {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudio = Shadows.shadowOf(audioManager)
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val streamGate = CompletableDeferred<Unit>()
        val firstChunkWritten = CompletableDeferred<Unit>()
        val piperSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!
        settingsRepo.setActiveModelId(piperSpec.id)

        val ttsManager = TtsEngineManager(
            context = context,
            storageManager = storageManager,
            engineFactory = { spec ->
                object : TtsEngine {
                    override val engineType = spec.engineType
                    override var modelId = spec.id
                    override val sampleRateHz = 22050
                    override var isLoaded = false
                    override var activeAccelerator = "TEST"
                    override val fellBackToCpu = false
                    override val fallbackReason: String? = null
                    override suspend fun initialize(spec: ModelSpec, modelDir: File, acceleratorMode: AcceleratorMode): Result<Long> {
                        isLoaded = true
                        return Result.success(1L)
                    }
                    override suspend fun synthesize(spokenText: String, voiceId: String, speed: Float, locale: String) =
                        Result.success(TtsSynthesisResult(FloatArray(256) { 0.1f }, 22050, emptyList(), 1L, "TEST", false))
                    override fun synthesizeStream(spokenText: String, voiceId: String, speed: Float, locale: String): Flow<TtsStreamChunk> = flow {
                        emit(TtsStreamChunk(FloatArray(256) { 0.2f }, 22050, isFinal = false))
                        firstChunkWritten.complete(Unit)
                        streamGate.await()
                        emit(TtsStreamChunk(FloatArray(256) { 0.2f }, 22050, isFinal = true))
                    }
                    override fun cancel() {}
                    override fun release() { isLoaded = false }
                }
            },
            packageVerifier = { it.id == piperSpec.id }
        )

        db.bookDao().insertFullBook(
            book = BookEntity(
                id = "focus_api24",
                title = "Focus Book",
                author = "Author",
                sourceType = "TXT",
                sourceLanguage = "fr",
                totalChapters = 1,
                totalSentences = 1
            ),
            chapters = listOf(ChapterEntity(bookId = "focus_api24", chapterIndex = 0, title = "Ch 1", sentenceCount = 1)),
            sentences = listOf(
                SentenceEntity(
                    id = 1L,
                    bookId = "focus_api24",
                    chapterIndex = 0,
                    sentenceIndex = 0,
                    paragraphIndex = 0,
                    originalText = "Bonjour focus.",
                    spokenText = "Bonjour focus."
                )
            )
        )

        val trackHandle = RecordingAudioTrackHandle()
        val controller = AudioPlaybackController(
            context = context,
            bookDao = db.bookDao(),
            settingsRepository = settingsRepo,
            storageManager = storageManager,
            ttsEngineManager = ttsManager,
            trackFactory = AudioTrackHandleFactory { trackHandle to false },
            playbackDispatcher = Dispatchers.IO
        )

        controller.playChapterFrom("focus_api24", "Focus Book", 0, "Ch 1", 0, "fr")
        withTimeout(5_000L) { firstChunkWritten.await() }
        awaitUntil { controller.state.value.status == PlaybackStatus.PLAYING }
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)

        val legacyListener = shadowAudio.lastAudioFocusRequest?.listener
        assertNotNull("Legacy OnAudioFocusChangeListener must be registered on API 24", legacyListener)
        legacyListener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        awaitUntil { controller.state.value.status == PlaybackStatus.PAUSED }
        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)

        streamGate.complete(Unit)
        controller.stop()
        awaitUntil { controller.state.value.status == PlaybackStatus.IDLE }
        assertEquals(legacyListener, shadowAudio.lastAbandonedAudioFocusListener)
    }

    @Test
    fun `piper sherpa engine aborts callback generation cleanly on cancel and coroutine cancellation`() = runTest {
        val piperSpec = ModelCatalog.findById("piper-fr-siwis-medium")!!
        val modelDir = storageManager.getInstalledModelDir(piperSpec.id).apply { mkdirs() }
        val espeakDir = storageManager.getInstalledModelDir(ModelCatalog.espeakNgDataSpec.id).apply { mkdirs() }
        File(modelDir, "model.onnx").writeBytes(ByteArray(32) { 1 })
        File(modelDir, "tokens.txt").writeText("a 1\n")
        File(espeakDir, "phontab").writeBytes(ByteArray(16) { 2 })

        val chunksEmitted = AtomicInteger(0)
        val firstChunkObserved = CompletableDeferred<Unit>()
        lateinit var engine: PiperSherpaTtsEngine

        val sessionFactory = PiperSessionFactory { _, _, _, _ ->
            object : PiperNativeSession {
                override val sampleRateHz: Int = 22050
                override fun generate(text: String, sid: Int, speed: Float): FloatArray = FloatArray(128)
                override fun generateWithCallback(text: String, sid: Int, speed: Float, callback: (FloatArray) -> Int) {
                    for (i in 1..10) {
                        chunksEmitted.incrementAndGet()
                        val cont = callback(FloatArray(64) { 0.1f })
                        if (i == 1) firstChunkObserved.complete(Unit)
                        if (cont == 0) break
                        Thread.sleep(30L)
                    }
                }
                override fun release() {}
            }
        }

        engine = PiperSherpaTtsEngine(storageManager, sessionFactory)
        engine.initialize(piperSpec, modelDir, AcceleratorMode.CPU_ONLY).getOrThrow()
        assertTrue(engine.isLoaded)

        val synthJob = launch(Dispatchers.IO) {
            try {
                engine.synthesize("Bonjour monde.", "siwis", 1.0f, "fr-FR")
            } catch (_: CancellationException) {
                // Expected on coroutine cancellation
            }
        }
        firstChunkObserved.await()
        synthJob.cancel()
        synthJob.join()

        assertTrue("Callback loop must abort early when cancelled, emitted=${chunksEmitted.get()}", chunksEmitted.get() < 10)

        engine.release()
        assertFalse(engine.isLoaded)
        assertEquals("RELEASED", engine.activeAccelerator)
        storageManager.deleteModelFiles(piperSpec.id)
        storageManager.deleteModelFiles(ModelCatalog.espeakNgDataSpec.id)
    }

    @Test
    fun `migration 3 to 4 creates paragraph_translations table with cascade delete and unique key`() = runTest {
        val dbFile = context.getDatabasePath("migration_v3_v4_test.db")
        dbFile.delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `books` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceLanguage` TEXT NOT NULL, `levelTag` TEXT NOT NULL, `totalChapters` INTEGER NOT NULL, `totalSentences` INTEGER NOT NULL, `currentChapterIndex` INTEGER NOT NULL, `currentSentenceIndex` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `title` TEXT NOT NULL, `sentenceCount` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `sentences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `sentenceIndex` INTEGER NOT NULL, `paragraphIndex` INTEGER NOT NULL, `originalText` TEXT NOT NULL, `spokenText` TEXT NOT NULL, `cachedArabicTranslation` TEXT, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sentences_bookId_chapterIndex_sentenceIndex` ON `sentences` (`bookId`, `chapterIndex`, `sentenceIndex`)")
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `vocabulary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `frenchTerm` TEXT NOT NULL, `arabicTranslation` TEXT NOT NULL, `contextSentence` TEXT NOT NULL, `bookTitle` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `model_records` (`modelId` TEXT NOT NULL, `status` TEXT NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `installedDirPath` TEXT, `verifiedSha256` INTEGER NOT NULL, `verifiedLoadTest` INTEGER NOT NULL, `lastError` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`modelId`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `benchmark_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `modelId` TEXT NOT NULL, `modelDisplayName` TEXT NOT NULL, `engineType` TEXT NOT NULL, `acceleratorUsed` TEXT NOT NULL, `fellBackToCpu` INTEGER NOT NULL, `corpusItemId` TEXT NOT NULL, `corpusCategory` TEXT NOT NULL, `speedFactor` REAL NOT NULL, `modelLoadTimeMs` INTEGER NOT NULL, `ttfaMs` INTEGER NOT NULL, `totalSynthesisMs` INTEGER NOT NULL, `audioDurationMs` INTEGER NOT NULL, `realTimeFactor` REAL NOT NULL, `ramBeforeLoadMb` REAL NOT NULL, `ramAfterLoadMb` REAL NOT NULL, `ramPeakSynthesisMb` REAL NOT NULL, `deviceModel` TEXT NOT NULL, `socOrAbi` TEXT NOT NULL, `totalRamMb` INTEGER NOT NULL, `androidVersion` TEXT NOT NULL, `wavSamplePath` TEXT, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `translation_jobs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `originalFileName` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceLanguage` TEXT NOT NULL, `targetLanguage` TEXT NOT NULL, `status` TEXT NOT NULL, `totalChunks` INTEGER NOT NULL, `completedChunks` INTEGER NOT NULL, `jobEpoch` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `translation_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL, `paragraphIndex` INTEGER NOT NULL, `isHeading` INTEGER NOT NULL, `originalText` TEXT NOT NULL, `translatedText` TEXT, `isCompleted` INTEGER NOT NULL, FOREIGN KEY(`jobId`) REFERENCES `translation_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chunkIndex` ON `translation_chunks` (`jobId`, `chunkIndex`)")
                        db.execSQL(
                            """
                            INSERT INTO `books` VALUES (
                                'b_mig', 'Migrated Book', 'Author', 'TXT', 'fr', 'B1', 1, 1, 0, 0, 100, 100
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        try {
            val book = migratedDb.bookDao().getBook("b_mig")
            assertNotNull(book)

            migratedDb.bookDao().upsertParagraphTranslation(
                ParagraphTranslationEntity(
                    bookId = "b_mig",
                    chapterIndex = 0,
                    paragraphIndex = 0,
                    sourceLanguage = "fr",
                    arabicTranslation = "أول ترجمة"
                )
            )
            // Upsert same (bookId, chapterIndex, paragraphIndex, sourceLanguage) updates without duplicating
            migratedDb.bookDao().upsertParagraphTranslation(
                ParagraphTranslationEntity(
                    bookId = "b_mig",
                    chapterIndex = 0,
                    paragraphIndex = 0,
                    sourceLanguage = "fr",
                    arabicTranslation = "ترجمة محدثة"
                )
            )
            val saved = migratedDb.bookDao().observeParagraphTranslations("b_mig", 0, "fr").first()
            assertEquals(1, saved.size)
            assertEquals("ترجمة محدثة", saved.first().arabicTranslation)

            migratedDb.bookDao().deleteBook("b_mig")
            val afterBookDelete = migratedDb.bookDao().observeParagraphTranslations("b_mig", 0, "fr").first()
            assertTrue("CASCADE delete must remove paragraph translations when book is deleted", afterBookDelete.isEmpty())
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun `calculateBookProgress tracks multi-chapter reading progress accurately`() {
        val chapters = listOf(
            ChapterEntity(id = 1, bookId = "multi", chapterIndex = 0, title = "Ch 1", sentenceCount = 4),
            ChapterEntity(id = 2, bookId = "multi", chapterIndex = 1, title = "Ch 2", sentenceCount = 4),
            ChapterEntity(id = 3, bookId = "multi", chapterIndex = 2, title = "Ch 3", sentenceCount = 4)
        )
        val untouched = BookEntity(
            id = "multi",
            title = "Multi",
            author = "Author",
            sourceType = "EPUB",
            sourceLanguage = "fr",
            totalChapters = 3,
            totalSentences = 12,
            currentChapterIndex = 0,
            currentSentenceIndex = 0
        )
        assertEquals(0f, LibraryRepository.calculateBookProgress(untouched, chapters), 0.0001f)

        val chapter0Mid = untouched.copy(currentChapterIndex = 0, currentSentenceIndex = 1)
        assertEquals(2f / 12f, LibraryRepository.calculateBookProgress(chapter0Mid, chapters), 0.0001f)

        val chapter2Mid = untouched.copy(currentChapterIndex = 2, currentSentenceIndex = 2)
        assertEquals(11f / 12f, LibraryRepository.calculateBookProgress(chapter2Mid, chapters), 0.0001f)
    }

    @Test
    fun `playerAndLabViewModel rejects invalid WAV headers gracefully with lastError`(): Unit = runBlocking {
        val app = context as EcouteApplication
        val vm = PlayerAndLabViewModel(app)

        val nonRiff = File(context.cacheDir, "corrupt_non_riff.wav").apply {
            writeBytes(ByteArray(64) { 0x55 })
        }
        vm.playSavedWavSample(nonRiff.absolutePath)
        awaitUntil { vm.labState.value.lastError != null && vm.labState.value.playingSamplePath == null }
        assertEquals("تنسيق WAV غير صالح.", vm.labState.value.lastError)
        assertNull(vm.labState.value.playingSamplePath)

        // Valid RIFF/WAVE header but invalid 32-bit PCM bitsPerSample
        val badPcmBytes = ByteArray(44).also { bytes ->
            "RIFF".toByteArray().copyInto(bytes, 0)
            "WAVE".toByteArray().copyInto(bytes, 8)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(20, 1) // PCM format
            buf.putShort(22, 1) // mono
            buf.putInt(24, 24000) // sample rate
            buf.putShort(34, 32) // 32-bit unsupported
        }
        val badPcmFile = File(context.cacheDir, "bad_pcm.wav").apply { writeBytes(badPcmBytes) }
        vm.playSavedWavSample(badPcmFile.absolutePath)
        awaitUntil {
            vm.labState.value.lastError?.contains("مواصفات PCM غير مدعومة") == true &&
                vm.labState.value.playingSamplePath == null
        }
        assertTrue(vm.labState.value.lastError!!.contains("مواصفات PCM غير مدعومة"))
        assertNull(vm.labState.value.playingSamplePath)

        vm.playSavedWavSample(File(context.cacheDir, "missing_sample.wav").absolutePath)
        assertEquals("ملف العينة الصوتية غير موجود.", vm.labState.value.lastError)

        nonRiff.delete()
        badPcmFile.delete()
    }

    @Test
    fun `readerViewModel persists inline translations beyond 15 paragraphs, cancels on chapter switch, and bookmarks paragraph`(): Unit = runBlocking {
        val app = context as EcouteApplication
        val chapter0Gate = CompletableDeferred<Unit>()
        val fakeFrEngine = object : TranslationEngine {
            override val direction = TranslationDirection.FR_TO_AR
            override val engineName: String = "FakeTestEngine"
            override suspend fun isModelReady(): Boolean = true
            override suspend fun downloadModel(): Result<Unit> = Result.success(Unit)
            override suspend fun deleteSourcePack(deleteSharedArabicTarget: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun translateText(text: String): Result<String> {
                if (text.contains("Paragraphe_cancel")) {
                    chapter0Gate.await()
                }
                return Result.success("AR:$text")
            }
            override fun close() {}
        }
        ReflectionHelpers.setField(
            app.translationEngineManager,
            "engines",
            mapOf(
                TranslationDirection.FR_TO_AR to fakeFrEngine,
                TranslationDirection.EN_TO_AR to fakeFrEngine
            )
        )

        val sentences = (0 until 18).map { idx ->
            SentenceEntity(
                id = 5000L + idx,
                bookId = "long_para_book",
                chapterIndex = 0,
                sentenceIndex = idx,
                paragraphIndex = idx,
                originalText = "Paragraphe $idx.",
                spokenText = "Paragraphe $idx."
            )
        } + SentenceEntity(
            id = 5100L,
            bookId = "long_para_book",
            chapterIndex = 1,
            sentenceIndex = 0,
            paragraphIndex = 0,
            originalText = "Paragraphe_cancel.",
            spokenText = "Paragraphe_cancel."
        )

        app.database.bookDao().insertFullBook(
            book = BookEntity(
                id = "long_para_book",
                title = "Long Paragraph Book",
                author = "Tester",
                sourceType = "TXT",
                sourceLanguage = "fr",
                totalChapters = 2,
                totalSentences = 19
            ),
            chapters = listOf(
                ChapterEntity(bookId = "long_para_book", chapterIndex = 0, title = "Ch 0", sentenceCount = 18),
                ChapterEntity(bookId = "long_para_book", chapterIndex = 1, title = "Ch 1", sentenceCount = 1)
            ),
            sentences = sentences
        )
        app.settingsRepository.setShowInlineTranslations(false)

        val vm = ReaderViewModel(app)
        val collectJob = launch { vm.readerState.collect {} }
        vm.openBook("long_para_book")
        awaitUntil { vm.readerState.value.book?.id == "long_para_book" && vm.readerState.value.sentences.size == 18 }

        // Turn ON inline translations -> must translate all 18 paragraphs (previously capped at 15)
        vm.toggleInlineTranslations()
        awaitUntil(timeoutMs = 8000L) { vm.readerState.value.paragraphTranslations.size == 18 }
        assertEquals(18, vm.readerState.value.paragraphTranslations.size)
        assertEquals("AR:Paragraphe 17.", vm.readerState.value.paragraphTranslations[17])

        // Re-opening via fresh ViewModel restores persisted paragraph translations from Room
        val freshVm = ReaderViewModel(app)
        val freshCollectJob = launch { freshVm.readerState.collect {} }
        freshVm.openBook("long_para_book")
        awaitUntil { freshVm.readerState.value.paragraphTranslations.size == 18 }
        assertEquals("AR:Paragraphe 17.", freshVm.readerState.value.paragraphTranslations[17])

        // Save paragraph to vocabulary
        val firstPara = listOf(sentences.first())
        freshVm.saveParagraphToVocab(firstPara, "AR:Paragraphe 0.")
        awaitUntil {
            runBlocking { app.database.vocabularyDao().findByFrenchTerm("Paragraphe 0.") != null }
        }
        val savedVocab = app.database.vocabularyDao().findByFrenchTerm("Paragraphe 0.")
        assertNotNull(savedVocab)
        assertEquals("AR:Paragraphe 0.", savedVocab!!.arabicTranslation)

        // Start paragraph translation on chapter 1 then immediately switch back to chapter 0 -> stale chapter 1 result must NOT leak
        freshVm.selectChapter(1)
        awaitUntil { freshVm.readerState.value.activeChapterIndex == 1 && freshVm.readerState.value.sentences.size == 1 }
        val ch1Sentences = freshVm.readerState.value.sentences
        freshVm.translateOrToggleParagraph(0, ch1Sentences)
        freshVm.selectChapter(0)
        awaitUntil { freshVm.readerState.value.activeChapterIndex == 0 }
        chapter0Gate.complete(Unit)
        realDelay(150L)

        val ch1Translations = app.database.bookDao().observeParagraphTranslations("long_para_book", 1, "fr").first()
        assertTrue("Stale translation after chapter switch must not be persisted", ch1Translations.isEmpty())

        collectJob.cancel()
        freshCollectJob.cancel()
        app.database.bookDao().deleteBook("long_para_book")
        app.settingsRepository.setShowInlineTranslations(false)
    }
}
