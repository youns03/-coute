package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.example.audio.PlaybackStatus
import com.example.domain.BenchmarkCorpus
import com.example.ui.components.AcousticWaveIndicator
import com.example.ui.components.EcouteAtelierBackdrop
import com.example.ui.components.GlobalMiniPlayer
import com.example.ui.components.shouldHideAppChrome
import com.example.ui.components.shouldShowGlobalMiniPlayer
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.FileTranslatorScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.PlayerAndLabScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VocabularyScreen
import com.example.ui.theme.EcouteTheme
import com.example.ui.theme.EditorialDisplayFamily
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
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val route: AppRoute
)

private fun isNavItemSelected(route: AppRoute, destination: androidx.navigation.NavDestination?): Boolean =
    when (route) {
        is AppRoute.Library -> destination?.hasRoute<AppRoute.Library>() == true
        is AppRoute.Reader -> destination?.hasRoute<AppRoute.Reader>() == true
        is AppRoute.FileTranslator -> destination?.hasRoute<AppRoute.FileTranslator>() == true
        is AppRoute.PlayerLab -> destination?.hasRoute<AppRoute.PlayerLab>() == true
        is AppRoute.Models -> destination?.hasRoute<AppRoute.Models>() == true
        is AppRoute.Vocabulary -> destination?.hasRoute<AppRoute.Vocabulary>() == true
        is AppRoute.Settings -> destination?.hasRoute<AppRoute.Settings>() == true
    }

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
    var isReaderImmersive by remember { mutableStateOf(false) }

    LaunchedEffect(isReaderDestination) {
        if (!isReaderDestination) isReaderImmersive = false
    }
    val isFullPlayerDestination = currentDest?.hasRoute<AppRoute.PlayerLab>() == true
    val showGlobalMiniPlayer = shouldShowGlobalMiniPlayer(
        status = globalPlaybackState.status,
        isReaderDestination = isReaderDestination,
        isFullPlayerDestination = isFullPlayerDestination
    )
    val hideAppChrome = shouldHideAppChrome(isReaderDestination, isReaderImmersive)

    val defaultBookId = settings.activeBookId
        ?: libraryState.books.firstOrNull()?.id
        ?: BenchmarkCorpus.starterBooks.first().id

    val primaryTabs = listOf(
        TopLevelNavItem(
            labelRes = R.string.nav_library,
            selectedIcon = Icons.AutoMirrored.Rounded.LibraryBooks,
            unselectedIcon = Icons.AutoMirrored.Outlined.LibraryBooks,
            testTag = "nav_library",
            route = AppRoute.Library
        ),
        TopLevelNavItem(
            labelRes = R.string.nav_reader,
            selectedIcon = Icons.Rounded.AutoStories,
            unselectedIcon = Icons.Outlined.AutoStories,
            testTag = "nav_reader",
            route = AppRoute.Reader(defaultBookId)
        ),
        TopLevelNavItem(
            labelRes = R.string.nav_translator,
            selectedIcon = Icons.Rounded.Translate,
            unselectedIcon = Icons.Outlined.Translate,
            testTag = "nav_file_translator",
            route = AppRoute.FileTranslator
        ),
        TopLevelNavItem(
            labelRes = R.string.nav_player,
            selectedIcon = Icons.Rounded.GraphicEq,
            unselectedIcon = Icons.Outlined.GraphicEq,
            testTag = "nav_player",
            route = AppRoute.PlayerLab
        ),
        TopLevelNavItem(
            labelRes = R.string.nav_models,
            selectedIcon = Icons.Rounded.CloudDownload,
            unselectedIcon = Icons.Outlined.CloudDownload,
            testTag = "nav_models",
            route = AppRoute.Models
        )
    )

    val navigateTopLevel: (AppRoute) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            // Reader carries active bookId in its route; avoid restoring a stale bookId stack entry.
            restoreState = target !is AppRoute.Reader
        }
    }

    val currentTitle = when {
        currentDest?.hasRoute<AppRoute.Library>() == true -> stringResource(R.string.title_library)
        currentDest?.hasRoute<AppRoute.Reader>() == true -> stringResource(R.string.title_reader)
        currentDest?.hasRoute<AppRoute.FileTranslator>() == true -> stringResource(R.string.title_translator)
        currentDest?.hasRoute<AppRoute.PlayerLab>() == true -> stringResource(R.string.title_player)
        currentDest?.hasRoute<AppRoute.Models>() == true -> stringResource(R.string.title_models)
        currentDest?.hasRoute<AppRoute.Vocabulary>() == true -> stringResource(R.string.title_vocab)
        currentDest?.hasRoute<AppRoute.Settings>() == true -> stringResource(R.string.title_settings)
        else -> stringResource(R.string.app_name)
    }

    val isVocabSelected = isNavItemSelected(AppRoute.Vocabulary, currentDest)
    val isSettingsSelected = isNavItemSelected(AppRoute.Settings, currentDest)
    val isLiveAudio = globalPlaybackState.status == PlaybackStatus.PLAYING ||
        globalPlaybackState.status == PlaybackStatus.BUFFERING

    EcouteTheme(darkTheme = settings.nightMode) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            EcouteAtelierBackdrop {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        if (!hideAppChrome) {
                            Column {
                                TopAppBar(
                                    title = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Text(
                                                        text = "Écoute",
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontFamily = EditorialDisplayFamily,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = currentTitle,
                                                style = MaterialTheme.typography.titleLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.90f),
                                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    actions = {
                                        if (isLiveAudio && !isFullPlayerDestination) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 2.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f))
                                                    .clickable { navigateTopLevel(AppRoute.PlayerLab) }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AcousticWaveIndicator(
                                                    isPlaying = globalPlaybackState.status == PlaybackStatus.PLAYING,
                                                    isBuffering = globalPlaybackState.status == PlaybackStatus.BUFFERING,
                                                    barColor = MaterialTheme.colorScheme.secondary,
                                                    barCount = 4,
                                                    modifier = Modifier
                                                        .width(18.dp)
                                                        .height(16.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { navigateTopLevel(AppRoute.Vocabulary) },
                                            colors = if (isVocabSelected) {
                                                IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            } else {
                                                IconButtonDefaults.iconButtonColors()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("nav_vocabulary")
                                        ) {
                                            Icon(
                                                if (isVocabSelected) Icons.Rounded.Bookmarks else Icons.Outlined.Bookmarks,
                                                contentDescription = stringResource(R.string.title_vocab)
                                            )
                                        }
                                        IconButton(
                                            onClick = { navigateTopLevel(AppRoute.Settings) },
                                            colors = if (isSettingsSelected) {
                                                IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            } else {
                                                IconButtonDefaults.iconButtonColors()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("nav_settings")
                                        ) {
                                            Icon(
                                                if (isSettingsSelected) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                                contentDescription = stringResource(R.string.title_settings)
                                            )
                                        }
                                    }
                                )
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                )
                            }
                        }
                    },
                    bottomBar = {
                        if (!hideAppChrome) {
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
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                        tonalElevation = 0.dp
                                    ) {
                                        primaryTabs.forEach { tab ->
                                            val selected = isNavItemSelected(tab.route, currentDest)
                                            val tabLabel = stringResource(tab.labelRes)
                                            NavigationBarItem(
                                                selected = selected,
                                                onClick = { navigateTopLevel(tab.route) },
                                                icon = {
                                                    Icon(
                                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                        contentDescription = tabLabel
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        tabLabel,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.secondary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                ),
                                                modifier = Modifier.testTag(tab.testTag)
                                            )
                                        }
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
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ) {
                                Spacer(Modifier.height(8.dp))
                                primaryTabs.forEach { tab ->
                                    val selected = isNavItemSelected(tab.route, currentDest)
                                    val tabLabel = stringResource(tab.labelRes)
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { navigateTopLevel(tab.route) },
                                        icon = {
                                            Icon(
                                                if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tabLabel
                                            )
                                        },
                                        label = { Text(tabLabel) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.secondary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                        modifier = Modifier.testTag(tab.testTag)
                                    )
                                }
                                NavigationRailItem(
                                    selected = isVocabSelected,
                                    onClick = { navigateTopLevel(AppRoute.Vocabulary) },
                                    icon = {
                                        Icon(
                                            if (isVocabSelected) Icons.Rounded.Bookmarks else Icons.Outlined.Bookmarks,
                                            contentDescription = stringResource(R.string.nav_vocab)
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_vocab)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.secondary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                                NavigationRailItem(
                                    selected = isSettingsSelected,
                                    onClick = { navigateTopLevel(AppRoute.Settings) },
                                    icon = {
                                        Icon(
                                            if (isSettingsSelected) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                            contentDescription = stringResource(R.string.nav_settings)
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_settings)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.secondary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
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
                                    onSaveParagraphVocab = readerVm::saveParagraphToVocab,
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
                                    onOpenFullPlayer = { navigateTopLevel(AppRoute.PlayerLab) },
                                    isImmersive = isReaderImmersive,
                                    onToggleImmersive = { isReaderImmersive = !isReaderImmersive }
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
}
