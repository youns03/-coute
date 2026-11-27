package com.example

import android.app.Application
import com.example.audio.AudioChunkCache
import com.example.audio.AudioPlaybackController
import com.example.audio.PlaybackStatus
import com.example.data.AppDatabase
import com.example.data.BookImporter
import com.example.data.LibraryRepository
import com.example.data.SettingsRepository
import com.example.data.importengine.PdfDocumentParser
import com.example.models.ModelRepository
import com.example.models.ModelStorageManager
import com.example.runtime.LocalTaskCoordinator
import com.example.runtime.RuntimeMemoryGovernor
import com.example.transcription.SpeechTranscriptionManager
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
    lateinit var taskCoordinator: LocalTaskCoordinator
        private set
    lateinit var ttsEngineManager: TtsEngineManager
        private set
    lateinit var speechTranscriptionManager: SpeechTranscriptionManager
        private set
    lateinit var memoryGovernor: RuntimeMemoryGovernor
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
        taskCoordinator = LocalTaskCoordinator(appScope)
        PdfDocumentParser.ensurePdfBoxInitialized(this)
        val bookImporter = BookImporter(this)
        ttsEngineManager = TtsEngineManager(this, storageManager)

        speechTranscriptionManager = SpeechTranscriptionManager(
            context = this,
            storageManager = storageManager,
            bookDao = database.bookDao(),
            recentActivityDao = database.recentActivityDao(),
            taskCoordinator = taskCoordinator,
            settingsRepository = settingsRepository
        )

        modelRepository = ModelRepository(
            context = this,
            modelRecordDao = database.modelRecordDao(),
            storageManager = storageManager,
            ttsEngineManager = ttsEngineManager,
            speechTranscriptionManager = speechTranscriptionManager,
            taskCoordinator = taskCoordinator
        )
        libraryRepository = LibraryRepository(
            bookDao = database.bookDao(),
            vocabularyDao = database.vocabularyDao(),
            bookImporter = bookImporter,
            context = this,
            recentActivityDao = database.recentActivityDao()
        )
        translationEngineManager = TranslationEngineManager(
            bookDao = database.bookDao(),
            vocabularyDao = database.vocabularyDao()
        )
        fileTranslationRepository = FileTranslationRepository(
            context = this,
            jobDao = database.translationJobDao(),
            bookImporter = bookImporter,
            recentActivityDao = database.recentActivityDao(),
            taskCoordinator = taskCoordinator
        )
        playbackController = AudioPlaybackController(
            context = this,
            bookDao = database.bookDao(),
            settingsRepository = settingsRepository,
            storageManager = storageManager,
            ttsEngineManager = ttsEngineManager,
            recentActivityDao = database.recentActivityDao(),
            taskCoordinator = taskCoordinator
        )

        memoryGovernor = RuntimeMemoryGovernor(
            context = this,
            scope = appScope,
            isTtsBusyProvider = {
                val st = playbackController.state.value.status
                st == PlaybackStatus.PLAYING || st == PlaybackStatus.BUFFERING || ttsEngineManager.runtimeState.value.isLoading
            },
            isTtsLoadedProvider = { ttsEngineManager.isEngineLoaded },
            releaseTtsEngine = { ttsEngineManager.releaseActiveEngine() },
            isAsrBusyProvider = { speechTranscriptionManager.isTranscribing },
            isAsrLoadedProvider = { speechTranscriptionManager.isAsrLoaded },
            releaseAsrEngine = { speechTranscriptionManager.releaseActiveEngine() }
        )
        ttsEngineManager.setOnActivityCallback { memoryGovernor.markTtsActivity() }
        speechTranscriptionManager.setOnActivityCallback { memoryGovernor.markAsrActivity() }
        memoryGovernor.startMonitoring()

        appScope.launch {
            AudioChunkCache.pruneIfOversized(storageManager.audioCacheRoot)
            libraryRepository.seedStarterBooksIfEmpty()
            modelRepository.reconcileStorageOnStartup()
            fileTranslationRepository.reconcileOnStartup()
            translationEngineManager.refreshAllStatuses()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::memoryGovernor.isInitialized) {
            appScope.launch {
                memoryGovernor.onTrimMemory(level)
            }
        }
    }
}

