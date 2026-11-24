package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.domain.BenchmarkCorpus
import com.example.ui.navigation.AppRoute
import com.example.ui.components.GlobalMiniPlayer
import com.example.ui.components.shouldShowGlobalMiniPlayer
import com.example.ui.screens.FileTranslatorScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.PlayerAndLabScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VocabularyScreen
import com.example.ui.theme.EcouteTheme
import com.example.ui.viewmodel.FileTranslatorViewModel
import com.example.ui.viewmodel.LibraryAndVocabViewModel
import com.example.ui.viewmodel.ModelsAndSettingsViewModel
import com.example.ui.viewmodel.PlayerAndLabViewModel
import com.example.ui.viewmodel.ReaderViewModel

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isExpandedNav = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
            EcouteAppRoot(isExpandedNav = isExpandedNav)
        }
    }
}

private data class TopLevelNavItem(
    val label: String,
    val icon: ImageVector,
    val testTag: String,
    val route: AppRoute
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcouteAppRoot(isExpandedNav: Boolean) {
    val navController = rememberNavController()
    val libraryVm: LibraryAndVocabViewModel = viewModel()
    val readerVm: ReaderViewModel = viewModel()
    val translatorVm: FileTranslatorViewModel = viewModel()
    val playerVm: PlayerAndLabViewModel = viewModel()
    val modelsVm: ModelsAndSettingsViewModel = viewModel()

    val settings by modelsVm.settings.collectAsStateWithLifecycle()
    val libraryState by libraryVm.libraryState.collectAsStateWithLifecycle()
    val globalPlaybackState by playerVm.playbackState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isReaderDestination = currentDest?.hasRoute<AppRoute.Reader>() == true
    val isFullPlayerDestination = currentDest?.hasRoute<AppRoute.PlayerLab>() == true
    val showGlobalMiniPlayer = shouldShowGlobalMiniPlayer(
        status = globalPlaybackState.status,
        isReaderDestination = isReaderDestination,
        isFullPlayerDestination = isFullPlayerDestination
    )

    val defaultBookId = settings.activeBookId
        ?: libraryState.books.firstOrNull()?.id
        ?: BenchmarkCorpus.starterBooks.first().id

    val primaryTabs = listOf(
        TopLevelNavItem("المكتبة", Icons.Rounded.LibraryBooks, "nav_library", AppRoute.Library),
        TopLevelNavItem("القارئ", Icons.Rounded.AutoStories, "nav_reader", AppRoute.Reader(defaultBookId)),
        TopLevelNavItem("المترجم", Icons.Rounded.Translate, "nav_file_translator", AppRoute.FileTranslator),
        TopLevelNavItem("المشغّل", Icons.Rounded.GraphicEq, "nav_player", AppRoute.PlayerLab),
        TopLevelNavItem("النماذج", Icons.Rounded.CloudDownload, "nav_models", AppRoute.Models)
    )

    val navigateTopLevel: (AppRoute) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val currentTitle = when {
        currentDest?.hasRoute<AppRoute.Library>() == true -> "المكتبة"
        currentDest?.hasRoute<AppRoute.Reader>() == true -> "القارئ المتزامن"
        currentDest?.hasRoute<AppRoute.FileTranslator>() == true -> "مترجم الملفات المحلي"
        currentDest?.hasRoute<AppRoute.PlayerLab>() == true -> "المشغّل ومختبر الصوت"
        currentDest?.hasRoute<AppRoute.Models>() == true -> "إدارة النماذج"
        currentDest?.hasRoute<AppRoute.Vocabulary>() == true -> "قاموس المفردات"
        currentDest?.hasRoute<AppRoute.Settings>() == true -> "الإعدادات والتراخيص"
        else -> "Écoute"
    }

    EcouteTheme(darkTheme = settings.nightMode) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = {
                            IconButton(
                                onClick = { navigateTopLevel(AppRoute.Vocabulary) },
                                modifier = Modifier.testTag("nav_vocabulary")
                            ) {
                                Icon(Icons.Rounded.Bookmarks, contentDescription = "قاموس المفردات")
                            }
                            IconButton(
                                onClick = { navigateTopLevel(AppRoute.Settings) },
                                modifier = Modifier.testTag("nav_settings")
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات والتراخيص")
                            }
                        }
                    )
                },
                bottomBar = {
                    Column {
                        if (showGlobalMiniPlayer) {
                            GlobalMiniPlayer(
                                playbackState = globalPlaybackState,
                                onTogglePlayPause = playerVm::togglePlayPause,
                                onPrevious = playerVm::previousSentence,
                                onNext = playerVm::nextSentence,
                                onOpen = { navigateTopLevel(AppRoute.PlayerLab) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        if (!isExpandedNav) {
                            NavigationBar {
                            primaryTabs.forEach { tab ->
                                val selected = when (tab.route) {
                                    is AppRoute.Library -> currentDest?.hasRoute<AppRoute.Library>() == true
                                    is AppRoute.Reader -> currentDest?.hasRoute<AppRoute.Reader>() == true
                                    is AppRoute.FileTranslator -> currentDest?.hasRoute<AppRoute.FileTranslator>() == true
                                    is AppRoute.PlayerLab -> currentDest?.hasRoute<AppRoute.PlayerLab>() == true
                                    is AppRoute.Models -> currentDest?.hasRoute<AppRoute.Models>() == true
                                    is AppRoute.Vocabulary -> currentDest?.hasRoute<AppRoute.Vocabulary>() == true
                                    is AppRoute.Settings -> currentDest?.hasRoute<AppRoute.Settings>() == true
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpandedNav) {
                        NavigationRail {
                            primaryTabs.forEach { tab ->
                                val selected = when (tab.route) {
                                    is AppRoute.Library -> currentDest?.hasRoute<AppRoute.Library>() == true
                                    is AppRoute.Reader -> currentDest?.hasRoute<AppRoute.Reader>() == true
                                    is AppRoute.FileTranslator -> currentDest?.hasRoute<AppRoute.FileTranslator>() == true
                                    is AppRoute.PlayerLab -> currentDest?.hasRoute<AppRoute.PlayerLab>() == true
                                    is AppRoute.Models -> currentDest?.hasRoute<AppRoute.Models>() == true
                                    is AppRoute.Vocabulary -> currentDest?.hasRoute<AppRoute.Vocabulary>() == true
                                    is AppRoute.Settings -> currentDest?.hasRoute<AppRoute.Settings>() == true
                                }
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                            NavigationRailItem(
                                selected = currentDest?.hasRoute<AppRoute.Vocabulary>() == true,
                                onClick = { navigateTopLevel(AppRoute.Vocabulary) },
                                icon = { Icon(Icons.Rounded.Bookmarks, contentDescription = "المفردات") },
                                label = { Text("المفردات") }
                            )
                            NavigationRailItem(
                                selected = currentDest?.hasRoute<AppRoute.Settings>() == true,
                                onClick = { navigateTopLevel(AppRoute.Settings) },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = "الإعدادات") },
                                label = { Text("الإعدادات") }
                            )
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.Library,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable<AppRoute.Library> {
                            LibraryScreen(
                                state = libraryState,
                                onSearchChange = libraryVm::setBookSearchQuery,
                                onImportUri = { uri ->
                                    libraryVm.importBook(uri) { importedId ->
                                        navController.navigate(AppRoute.Reader(importedId))
                                    }
                                },
                                onOpenBook = { bookId ->
                                    navController.navigate(AppRoute.Reader(bookId))
                                },
                                onDeleteBook = libraryVm::deleteBook,
                                onDismissBanner = libraryVm::dismissBanner,
                                onOpenModels = { navigateTopLevel(AppRoute.Models) }
                            )
                        }

                        composable<AppRoute.Reader> { entry ->
                            val route = entry.toRoute<AppRoute.Reader>()
                            LaunchedEffect(route.bookId) {
                                readerVm.openBook(route.bookId)
                            }
                            val readerState by readerVm.readerState.collectAsStateWithLifecycle()
                            val readerSettings by readerVm.settings.collectAsStateWithLifecycle()
                            val playbackState by readerVm.playbackState.collectAsStateWithLifecycle()
                            val translationManagerState by readerVm.translationManagerState.collectAsStateWithLifecycle()
                            val wordSheet by readerVm.wordLookupSheet.collectAsStateWithLifecycle()

                            ReaderScreen(
                                state = readerState,
                                settings = readerSettings,
                                playbackState = playbackState,
                                translationManagerState = translationManagerState,
                                wordLookupSheet = wordSheet,
                                onSelectChapter = readerVm::selectChapter,
                                onToggleBookLanguage = readerVm::toggleBookSourceLanguage,
                                onPlayFromSentence = readerVm::playFromSentence,
                                onTogglePlayChapter = readerVm::togglePlayCurrentChapter,
                                onNextSentence = playerVm::nextSentence,
                                onPrevSentence = playerVm::previousSentence,
                                onSpeedSelected = readerVm::setPlaybackSpeed,
                                onToggleInlineTranslations = readerVm::toggleInlineTranslations,
                                onTranslateOrToggleSentence = readerVm::toggleOrTranslateSentence,
                                onTranslateOrToggleParagraph = readerVm::translateOrToggleParagraph,
                                onSaveSentenceVocab = readerVm::saveSentenceToVocab,
                                onWordTapped = readerVm::onWordTapped,
                                onPronounceWord = readerVm::pronounceSelectedWord,
                                onSaveWordVocab = readerVm::saveSelectedWordToVocab,
                                onDismissWordSheet = readerVm::dismissWordLookup,
                                onToggleSearch = readerVm::toggleSearch,
                                onSearchQueryChange = readerVm::updateSearchQuery,
                                onJumpToSearchResult = readerVm::jumpToSearchResult,
                                onFontSizeChange = readerVm::setFontSize,
                                onLineHeightChange = readerVm::setLineHeight,
                                onNightModeChange = readerVm::setNightMode,
                                onDownloadTranslationPack = readerVm::downloadActiveBookTranslationPack,
                                onDismissTranslationError = readerVm::dismissTranslationError,
                                onOpenFullPlayer = { navigateTopLevel(AppRoute.PlayerLab) }
                            )
                        }

                        composable<AppRoute.FileTranslator> {
                            LaunchedEffect(Unit) { translatorVm.refreshPackStatuses() }
                            val selectedLang by translatorVm.selectedSourceLanguage.collectAsStateWithLifecycle()
                            val tmState by translatorVm.translationManagerState.collectAsStateWithLifecycle()
                            val jobs by translatorVm.jobs.collectAsStateWithLifecycle()
                            val previewId by translatorVm.previewJobId.collectAsStateWithLifecycle()
                            val previewChunks by translatorVm.previewChunks.collectAsStateWithLifecycle()
                            val isImporting by translatorVm.isImporting.collectAsStateWithLifecycle()
                            val banner by translatorVm.statusBanner.collectAsStateWithLifecycle()

                            FileTranslatorScreen(
                                selectedSourceLanguage = selectedLang,
                                translationManagerState = tmState,
                                jobs = jobs,
                                previewJobId = previewId,
                                previewChunks = previewChunks,
                                isImporting = isImporting,
                                statusBanner = banner,
                                onSelectSourceLanguage = translatorVm::selectSourceLanguage,
                                onImportFile = { uri -> translatorVm.importFile(uri, useAutoDetect = false) },
                                onStartOrResumeJob = translatorVm::startOrResumeJob,
                                onPauseJob = translatorVm::pauseJob,
                                onDeleteJob = translatorVm::deleteJob,
                                onChangeJobLanguage = translatorVm::changeJobLanguage,
                                onResetAndChangeJobLanguage = translatorVm::resetAndChangeJobLanguage,
                                onOpenPreview = translatorVm::openPreview,
                                onClosePreview = translatorVm::closePreview,
                                onExportJob = translatorVm::exportJob,
                                onDownloadPack = translatorVm::downloadPack,
                                onDismissBanner = translatorVm::dismissBanner
                            )
                        }

                        composable<AppRoute.PlayerLab> {
                            val playbackState by playerVm.playbackState.collectAsStateWithLifecycle()
                            val engineState by playerVm.engineRuntimeState.collectAsStateWithLifecycle()
                            val playerSettings by playerVm.settings.collectAsStateWithLifecycle()
                            val models by modelsVm.models.collectAsStateWithLifecycle()
                            val labState by playerVm.labState.collectAsStateWithLifecycle()
                            val reports by playerVm.benchmarkReports.collectAsStateWithLifecycle()

                            PlayerAndLabScreen(
                                playbackState = playbackState,
                                engineState = engineState,
                                settings = playerSettings,
                                models = models,
                                labState = labState,
                                reports = reports,
                                onTogglePlayPause = playerVm::togglePlayPause,
                                onStop = playerVm::stop,
                                onNext = playerVm::nextSentence,
                                onPrev = playerVm::previousSentence,
                                onReplaySentence = playerVm::replaySentence,
                                onToggleRepeat = playerVm::toggleRepeatSentence,
                                onSetSpeed = playerVm::setPlaybackSpeed,
                                onReleaseEngine = playerVm::releaseEngineFromRam,
                                onRunSingleBenchmark = playerVm::runSingleBenchmark,
                                onRunFullBenchmark = playerVm::runFullSuiteOnInstalledModels,
                                onPlayWavSample = playerVm::playSavedWavSample,
                                onClearReports = playerVm::clearBenchmarkReports
                            )
                        }

                        composable<AppRoute.Models> {
                            LaunchedEffect(Unit) { modelsVm.refreshStorageAndTranslation() }
                            val models by modelsVm.models.collectAsStateWithLifecycle()
                            val storage by modelsVm.storageBreakdown.collectAsStateWithLifecycle()
                            val translationState by modelsVm.translationState.collectAsStateWithLifecycle()
                            val actionMessage by modelsVm.actionMessage.collectAsStateWithLifecycle()

                            ModelManagerScreen(
                                models = models,
                                storage = storage,
                                translationState = translationState,
                                actionMessage = actionMessage,
                                onStartOrResume = modelsVm::startOrResumeDownload,
                                onPause = modelsVm::pauseDownload,
                                onCancel = modelsVm::cancelDownload,
                                onActivate = modelsVm::activateModel,
                                onDelete = modelsVm::deleteModel,
                                onDownloadTranslationPack = modelsVm::downloadTranslationPack,
                                onDeleteTranslationPack = modelsVm::deleteTranslationPack,
                                onDismissMessage = modelsVm::dismissMessage,
                                onRefreshStorage = modelsVm::refreshStorageAndTranslation
                            )
                        }

                        composable<AppRoute.Vocabulary> {
                            val query by libraryVm.vocabQuery.collectAsStateWithLifecycle()
                            val entries by libraryVm.vocabularyList.collectAsStateWithLifecycle()
                            VocabularyScreen(
                                query = query,
                                entries = entries,
                                onQueryChange = libraryVm::setVocabQuery,
                                onPronounce = libraryVm::pronounceVocabTerm,
                                onDelete = libraryVm::deleteVocabulary
                            )
                        }

                        composable<AppRoute.Settings> {
                            LaunchedEffect(Unit) { modelsVm.refreshStorageAndTranslation() }
                            val translationState by modelsVm.translationState.collectAsStateWithLifecycle()
                            SettingsScreen(
                                settings = settings,
                                translationState = translationState,
                                hardwareInfo = modelsVm.hardwareInfo,
                                onAcceleratorSelected = modelsVm::setAcceleratorMode,
                                onSpeedModeSelected = modelsVm::setSpeedControlMode,
                                onNightModeChange = modelsVm::setNightMode,
                                onFontSizeChange = modelsVm::setFontSize,
                                onLineHeightChange = modelsVm::setLineHeight,
                                onDownloadTranslationPack = modelsVm::downloadTranslationPack,
                                onDeleteTranslationPack = modelsVm::deleteTranslationPack,
                                onClearAudioCache = modelsVm::clearAudioCache
                            )
                        }
                    }
                }
            }
        }
    }
}
