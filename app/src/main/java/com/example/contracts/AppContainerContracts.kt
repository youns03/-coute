package com.example.contracts

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import com.example.domain.DeviceHardwareInfo
import kotlinx.coroutines.CoroutineScope

interface AppContainer {
    val settingsRepository: SettingsGateway
    val storageManager: ModelStorageGateway
    val modelCatalog: ModelCatalogProvider
    val modelRecordStore: ModelRecordStore
    val taskCoordinator: TaskCoordinatorGateway
    val ttsEngineManager: TtsRuntimeGateway
    val speechTranscriptionManager: AudioTranscriptionGateway
    val memoryGovernor: MemoryGovernorGateway
    val modelRepository: ModelRepositoryGateway
    val libraryRepository: LibraryContentGateway
    val translationEngineManager: TranslationGateway
    val translationJobStore: TranslationJobStore
    val fileTranslationRepository: FileTranslationGateway
    val playbackController: PlaybackGateway
    val benchmarkReportGateway: BenchmarkReportGateway
    val audioSamplePlayer: AudioSamplePlayerGateway
    val hardwareInfo: DeviceHardwareInfo
    val readingCoordinatorFactory: (CoroutineScope) -> ReadingEngineContract
    val viewModelFactory: ViewModelProvider.Factory

    fun createMainActivityLaunchIntent(): Intent
}

interface AppContainerProvider {
    val container: AppContainer
}
