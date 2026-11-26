package com.example

import android.app.Application
import com.example.audio.AudioChunkCache
import com.example.audio.AudioPlaybackController
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.data.SettingsRepository
import com.example.models.ModelRepository
import com.example.models.ModelStorageManager
import com.example.translation.FileTranslationRepository
import com.example.translation.TranslationEngineManager
import com.example.tts.TtsEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EcouteApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var storageManager: ModelStorageManager
        private set
    lateinit var ttsEngineManager: TtsEngineManager
        private set
    lateinit var modelRepository: ModelRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var translationEngineManager: TranslationEngineManager
        private set
    lateinit var fileTranslationRepository: FileTranslationRepository
        private set
    lateinit var playbackController: AudioPlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        storageManager = ModelStorageManager(this)
        val bookImporter = BookImporter(this)
        ttsEngineManager = TtsEngineManager(this, storageManager)
        modelRepository = ModelRepository(
            context = this,
            modelRecordDao = database.modelRecordDao(),
            storageManager = storageManager,
            ttsEngineManager = ttsEngineManager
        )
        libraryRepository = LibraryRepository(
            bookDao = database.bookDao(),
            vocabularyDao = database.vocabularyDao(),
            bookImporter = bookImporter
        )
        translationEngineManager = TranslationEngineManager(
            bookDao = database.bookDao(),
            vocabularyDao = database.vocabularyDao()
        )
        fileTranslationRepository = FileTranslationRepository(
            context = this,
            jobDao = database.translationJobDao(),
            bookImporter = bookImporter
        )
        playbackController = AudioPlaybackController(
            context = this,
            bookDao = database.bookDao(),
            settingsRepository = settingsRepository,
            storageManager = storageManager,
            ttsEngineManager = ttsEngineManager
        )

        appScope.launch {
            AudioChunkCache.pruneIfOversized(storageManager.audioCacheRoot)
            libraryRepository.seedStarterBooksIfEmpty()
            modelRepository.reconcileStorageOnStartup()
            fileTranslationRepository.reconcileOnStartup()
            translationEngineManager.refreshAllStatuses()
        }
    }
}
