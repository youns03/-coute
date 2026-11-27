package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.components.AsrModelMissingDialog
import com.example.ui.components.AudioTranscriptionLanguageDialog
import com.example.ui.components.DirectTextInputDialog
import com.example.ui.components.EcouteAtelierBackdrop
import com.example.ui.components.GlobalMiniPlayer
import com.example.ui.components.QuickAddModalSheet
import com.example.ui.components.shouldHideAppChrome
import com.example.ui.components.shouldShowGlobalMiniPlayer
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.FileTranslatorScreen
import com.example.ui.screens.HomeScreen
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
        is AppRoute.Home -> destination?.hasRoute<AppRoute.Home>() == true
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
    val homeState by libraryVm.homeState.collectAsStateWithLifecycle()
    val libraryState by libraryVm.libraryState.collectAsStateWithLifecycle()
    val globalPlaybackState by playerVm.playbackState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isHomeDestination = currentDest?.hasRoute<AppRoute.Home>() == true
    val isLibraryDestination = currentDest?.hasRoute<AppRoute.Library>() == true
    val isReaderDestination = currentDest?.hasRoute<AppRoute.Reader>() == true
    var isReaderImmersive by remember { mutableStateOf(false) }

    var showQuickAddSheet by remember { mutableStateOf(false) }
    var showDirectTextDialog by remember { mutableStateOf(false) }
    var showAudioLangDialog by remember { mutableStateOf(false) }
    var showAsrMissingDialog by remember { mutableStateOf(false) }
    var showHomeSearchDialog by remember { mutableStateOf(false) }
    var homeSearchQuery by remember { mutableStateOf("") }
    var pendingAudioLang by remember { mutableStateOf("fr") }

    val quickDocPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            libraryVm.importBook(uri) { importedId ->
                navController.navigate(AppRoute.Reader(importedId))
            }
        }
    }

    val quickAudioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            libraryVm.importAudioForTranscription(uri, pendingAudioLang) { bookId ->
                navController.navigate(AppRoute.Reader(bookId))
            }
        }
    }

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
            labelRes = R.string.nav_home,
            selectedIcon = Icons.Rounded.Home,
            unselectedIcon = Icons.Outlined.Home,
            testTag = "nav_home",
            route = AppRoute.Home
        ),
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
            restoreState = target !is AppRoute.Reader
        }
    }

    val currentTitle = when {
        isHomeDestination -> stringResource(R.string.title_home)
        isLibraryDestination -> stringResource(R.string.title_library)
        isReaderDestination -> stringResource(R.string.title_reader)
        currentDest?.hasRoute<AppRoute.FileTranslator>() == true -> stringResource(R.string.title_translator)
        isFullPlayerDestination -> stringResource(R.string.title_player)
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
                    floatingActionButton = {
                        if (!hideAppChrome && isExpandedNav && (isHomeDestination || isLibraryDestination)) {
                            FloatingActionButton(
                                onClick = { showQuickAddSheet = true },
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.testTag("fab_quick_add")
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.fab_quick_add_cd)
                                )
                            }
                        }
                    },
                    topBar = {
                        if (!hideAppChrome) {
                            if (isHomeDestination) {
                                TopAppBar(
                                    title = {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Text(
                                                text = "Écoute",
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontFamily = EditorialDisplayFamily,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = Color.Transparent,
                                        scrolledContainerColor = MaterialTheme.colorScheme.background
                                    ),
                                    actions = {
                                        IconButton(
                                            onClick = { showHomeSearchDialog = true },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("home_search_button")
                                        ) {
                                            Icon(
                                                Icons.Rounded.Search,
                                                contentDescription = stringResource(R.string.home_search_cd),
                                                tint = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                        IconButton(
                                            onClick = { navigateTopLevel(AppRoute.Settings) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("nav_settings")
                                        ) {
                                            Icon(
                                                Icons.Outlined.Settings,
                                                contentDescription = stringResource(R.string.title_settings),
                                                tint = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    }
                                )
                            } else {
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
                                            IconButton(
                                                onClick = { navigateTopLevel(AppRoute.FileTranslator) },
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("nav_file_translator")
                                            ) {
                                                Icon(
                                                    if (currentDest?.hasRoute<AppRoute.FileTranslator>() == true) Icons.Rounded.Translate else Icons.Outlined.Translate,
                                                    contentDescription = stringResource(R.string.nav_translator)
                                                )
                                            }
                                            IconButton(
                                                onClick = { navigateTopLevel(AppRoute.Models) },
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("nav_models")
                                            ) {
                                                Icon(
                                                    if (currentDest?.hasRoute<AppRoute.Models>() == true) Icons.Rounded.CloudDownload else Icons.Outlined.CloudDownload,
                                                    contentDescription = stringResource(R.string.nav_models)
                                                )
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
                                        onSeekBack = { playerVm.seekRelativeSeconds(-15) },
                                        onSeekForward = { playerVm.seekRelativeSeconds(15) },
                                        onOpen = { navigateTopLevel(AppRoute.PlayerLab) },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                if (!isExpandedNav) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                                        tonalElevation = 0.dp
                                    ) {
                                        Column(modifier = Modifier.navigationBarsPadding()) {
                                            HorizontalDivider(
                                                thickness = 0.8.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(60.dp)
                                                    .padding(horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceAround,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                IconButton(
                                                    onClick = { navigateTopLevel(AppRoute.Home) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("nav_home")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Home,
                                                        contentDescription = stringResource(R.string.nav_home),
                                                        tint = if (isHomeDestination) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                                                        modifier = Modifier.size(25.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { navigateTopLevel(AppRoute.Library) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("nav_library")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isLibraryDestination) Icons.AutoMirrored.Rounded.LibraryBooks else Icons.AutoMirrored.Outlined.LibraryBooks,
                                                        contentDescription = stringResource(R.string.nav_library),
                                                        tint = if (isLibraryDestination) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { showQuickAddSheet = true },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("fab_quick_add")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.AddCircleOutline,
                                                        contentDescription = stringResource(R.string.fab_quick_add_cd),
                                                        tint = MaterialTheme.colorScheme.onBackground,
                                                        modifier = Modifier.size(27.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { navigateTopLevel(AppRoute.PlayerLab) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("nav_player")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isFullPlayerDestination) Icons.Rounded.Headphones else Icons.Outlined.Headphones,
                                                        contentDescription = stringResource(R.string.nav_player),
                                                        tint = if (isFullPlayerDestination || isLiveAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { navigateTopLevel(AppRoute.Settings) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("nav_settings_tab")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isSettingsSelected) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                                        contentDescription = stringResource(R.string.nav_settings),
                                                        tint = if (isSettingsSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
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
                                    selected = isFullPlayerDestination,
                                    onClick = { navigateTopLevel(AppRoute.PlayerLab) },
                                    icon = {
                                        Icon(
                                            if (isFullPlayerDestination) Icons.Rounded.GraphicEq else Icons.Outlined.GraphicEq,
                                            contentDescription = stringResource(R.string.nav_player)
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_player)) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.secondary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
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
                            startDestination = AppRoute.Home,
                            modifier = Modifier.weight(1f)
                        ) {
                            composable<AppRoute.Home> {
                                HomeScreen(
                                    state = homeState,
                                    onOpenBook = { bookId -> navController.navigate(AppRoute.Reader(bookId)) },
                                    onViewAllLibrary = { navigateTopLevel(AppRoute.Library) },
                                    onOpenActivity = { activity ->
                                        when (activity.targetRoute) {
                                            "reader" -> navController.navigate(AppRoute.Reader(activity.targetId))
                                            "translator" -> navigateTopLevel(AppRoute.FileTranslator)
                                            "models" -> navigateTopLevel(AppRoute.Models)
                                            "player" -> navigateTopLevel(AppRoute.PlayerLab)
                                            else -> navigateTopLevel(AppRoute.Library)
                                        }
                                    },
                                    onOpenQuickAdd = { showQuickAddSheet = true },
                                    onPauseTask = libraryVm::pauseTask,
                                    onResumeTask = libraryVm::resumeTask,
                                    onCancelTask = libraryVm::cancelTask,
                                    onRetryTask = libraryVm::retryTask,
                                    onDismissTask = libraryVm::dismissTask,
                                    onClearFinishedTasks = libraryVm::clearFinishedTasks
                                )
                            }

                            composable<AppRoute.Library> {
                                LibraryScreen(
                                    state = libraryState,
                                    onSearchChange = libraryVm::setBookSearchQuery,
                                    onFilterSelected = libraryVm::setLibraryFilter,
                                    onSortSelected = libraryVm::setLibrarySortOrder,
                                    onToggleViewMode = libraryVm::toggleLibraryViewMode,
                                    onImportUri = { uri ->
                                        libraryVm.importBook(uri) { importedId ->
                                            navController.navigate(AppRoute.Reader(importedId))
                                        }
                                    },
                                    onOpenBook = { bookId ->
                                        navController.navigate(AppRoute.Reader(bookId))
                                    },
                                    onRenameBook = libraryVm::renameBook,
                                    onUpdateCoverTemplate = libraryVm::updateBookCoverTemplate,
                                    onUpdateCoverFromUri = libraryVm::updateBookCoverFromUri,
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
                                val activeBook by playerVm.activeBook.collectAsStateWithLifecycle()
                                val engineState by playerVm.engineRuntimeState.collectAsStateWithLifecycle()
                                val playerSettings by playerVm.settings.collectAsStateWithLifecycle()
                                val models by modelsVm.models.collectAsStateWithLifecycle()
                                val labState by playerVm.labState.collectAsStateWithLifecycle()
                                val reports by playerVm.benchmarkReports.collectAsStateWithLifecycle()

                                PlayerAndLabScreen(
                                    playbackState = playbackState,
                                    activeBook = activeBook,
                                    engineState = engineState,
                                    settings = playerSettings,
                                    models = models,
                                    labState = labState,
                                    reports = reports,
                                    onTogglePlayPause = playerVm::togglePlayPause,
                                    onStop = playerVm::stop,
                                    onNext = playerVm::nextSentence,
                                    onPrev = playerVm::previousSentence,
                                    onNextParagraph = playerVm::nextParagraph,
                                    onPrevParagraph = playerVm::previousParagraph,
                                    onSeekRelativeSeconds = playerVm::seekRelativeSeconds,
                                    onReplaySentence = playerVm::replaySentence,
                                    onToggleRepeat = playerVm::toggleRepeatSentence,
                                    onSetSpeed = playerVm::setPlaybackSpeed,
                                    onSelectVoice = playerVm::setActiveVoice,
                                    onStartSleepTimerMinutes = playerVm::startSleepTimerMinutes,
                                    onStartEndOfChapterTimer = playerVm::startEndOfChapterSleepTimer,
                                    onCancelSleepTimer = playerVm::cancelSleepTimer,
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
                                val memorySnap by modelsVm.memoryGovernorSnapshot.collectAsStateWithLifecycle()
                                val translationState by modelsVm.translationState.collectAsStateWithLifecycle()
                                val actionMessage by modelsVm.actionMessage.collectAsStateWithLifecycle()

                                ModelManagerScreen(
                                    models = models,
                                    storage = storage,
                                    memorySnapshot = memorySnap,
                                    translationState = translationState,
                                    actionMessage = actionMessage,
                                    onStartOrResume = modelsVm::startOrResumeDownload,
                                    onPause = modelsVm::pauseDownload,
                                    onCancel = modelsVm::cancelDownload,
                                    onActivate = modelsVm::activateModel,
                                    onDelete = modelsVm::deleteModel,
                                    onDownloadTranslationPack = modelsVm::downloadTranslationPack,
                                    onDeleteTranslationPack = modelsVm::deleteTranslationPack,
                                    onReleaseIdleEngines = modelsVm::releaseIdleEnginesNow,
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
                                    onClearAudioCache = modelsVm::clearAudioCache,
                                    onOpenReader = { navController.navigate(AppRoute.Reader(defaultBookId)) },
                                    onOpenTranslator = { navigateTopLevel(AppRoute.FileTranslator) },
                                    onOpenModels = { navigateTopLevel(AppRoute.Models) },
                                    onOpenVocab = { navigateTopLevel(AppRoute.Vocabulary) }
                                )
                            }
                        }
                    }
                }

                if (showHomeSearchDialog) {
                    val matchingBooks = remember(homeSearchQuery, libraryState.books) {
                        val q = homeSearchQuery.trim().lowercase()
                        if (q.isEmpty()) libraryState.books.take(6)
                        else libraryState.books.filter {
                            it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
                        }.take(8)
                    }
                    AlertDialog(
                        onDismissRequest = {
                            showHomeSearchDialog = false
                            homeSearchQuery = ""
                        },
                        title = { Text(stringResource(R.string.home_search_cd)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = homeSearchQuery,
                                    onValueChange = { homeSearchQuery = it },
                                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LazyColumn(
                                    modifier = Modifier.height(220.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(matchingBooks, key = { it.id }) { book ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    showHomeSearchDialog = false
                                                    homeSearchQuery = ""
                                                    navController.navigate(AppRoute.Reader(book.id))
                                                }
                                                .padding(horizontal = 8.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    if (book.mediaKind == "AUDIO") stringResource(R.string.home_audio_label) else stringResource(R.string.home_ebook_label),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showHomeSearchDialog = false
                                    homeSearchQuery = ""
                                    navigateTopLevel(AppRoute.Library)
                                }
                            ) {
                                Text(stringResource(R.string.home_view_all))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showHomeSearchDialog = false
                                    homeSearchQuery = ""
                                }
                            ) {
                                Text(stringResource(R.string.common_close))
                            }
                        }
                    )
                }

                if (showQuickAddSheet) {
                    QuickAddModalSheet(
                        onPickDocument = {
                            quickDocPickerLauncher.launch(
                                arrayOf(
                                    "text/plain",
                                    "application/epub+zip",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onPickAudio = {
                            if (homeState.readyAsrAvailable) {
                                showAudioLangDialog = true
                            } else {
                                showAsrMissingDialog = true
                            }
                        },
                        onOpenDirectText = { showDirectTextDialog = true },
                        onDismiss = { showQuickAddSheet = false }
                    )
                }

                if (showDirectTextDialog) {
                    DirectTextInputDialog(
                        onSave = { title, body, lang ->
                            showDirectTextDialog = false
                            libraryVm.importDirectText(title, body, lang) { importedId ->
                                navController.navigate(AppRoute.Reader(importedId))
                            }
                        },
                        onDismiss = { showDirectTextDialog = false }
                    )
                }

                if (showAudioLangDialog) {
                    AudioTranscriptionLanguageDialog(
                        onSelectLanguage = { lang ->
                            pendingAudioLang = lang
                            showAudioLangDialog = false
                            quickAudioPickerLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
                        },
                        onDismiss = { showAudioLangDialog = false }
                    )
                }

                if (showAsrMissingDialog) {
                    AsrModelMissingDialog(
                        onGoToModels = {
                            showAsrMissingDialog = false
                            navigateTopLevel(AppRoute.Models)
                        },
                        onDismiss = { showAsrMissingDialog = false }
                    )
                }
            }
        }
    }
}
