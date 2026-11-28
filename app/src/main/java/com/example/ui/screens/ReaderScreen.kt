package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.SentenceEntity
import com.example.data.UserSettings
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.UiSansFamily
import com.example.ui.viewmodel.ReaderUiState
import com.example.ui.viewmodel.WordLookupSheetState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    settings: UserSettings,
    playbackState: PlaybackUiState,
    translationManagerState: TranslationManagerUiState,
    wordLookupSheet: WordLookupSheetState?,
    onSelectChapter: (Int) -> Unit,
    onToggleBookLanguage: () -> Unit,
    onPlayFromSentence: (SentenceEntity) -> Unit,
    onTogglePlayChapter: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevSentence: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onToggleInlineTranslations: () -> Unit,
    onTranslateOrToggleSentence: (SentenceEntity) -> Unit,
    onTranslateOrToggleParagraph: (Int, List<SentenceEntity>) -> Unit,
    onSaveSentenceVocab: (SentenceEntity, String) -> Unit,
    onSaveParagraphVocab: (List<SentenceEntity>, String) -> Unit = { _, _ -> },
    onWordTapped: (String, SentenceEntity) -> Unit,
    onPronounceWord: (String) -> Unit,
    onSaveWordVocab: () -> Unit,
    onDismissWordSheet: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onJumpToSearchResult: (SentenceEntity) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onDownloadTranslationPack: () -> Unit,
    onDismissTranslationError: () -> Unit,
    onOpenFullPlayer: () -> Unit,
    isImmersive: Boolean,
    onToggleImmersive: () -> Unit,
    modifier: Modifier = Modifier,
    paragraphPresentationMode: ParagraphPresentationMode = ParagraphPresentationMode.CURRENT_CARD,
    initialAutoFollowEnabled: Boolean = true
) {
    // Allow hardware back gesture to exit immersive reading cleanly
    BackHandler(enabled = isImmersive) {
        onToggleImmersive()
    }

    var showChaptersSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var selectedParagraphIndex by remember(state.book?.id, state.activeChapterIndex) {
        mutableIntStateOf(-1)
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val atelier = LocalEcouteAtelierColors.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val requestNotificationIfNeeded = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val isArabicSource = state.book?.sourceLanguage?.lowercase()?.startsWith("ar") == true
    val bookSourceLang = SourceLanguage.fromCode(state.book?.sourceLanguage)
    val bookDirection = TranslationDirection.forSource(bookSourceLang)
    val activePackState = translationManagerState.stateFor(bookDirection)

    val activeBookId = state.book?.id
    val isPlayingThisChapter = playbackState.bookId == activeBookId &&
        playbackState.chapterIndex == state.activeChapterIndex &&
        playbackState.status != PlaybackStatus.IDLE

    val activeSentenceIdx = if (isPlayingThisChapter) playbackState.sentenceIndex else -1

    val groupedParagraphs = remember(state.sentences) {
        state.sentences.groupBy { it.paragraphIndex }.toList().sortedBy { it.first }
    }

    var isAutoFollowEnabled by remember { mutableStateOf(initialAutoFollowEnabled) }
    var leftActiveViewport by remember { mutableStateOf(false) }
    var returnToAudioRequest by remember { mutableIntStateOf(0) }
    val activeParagraphItemIndex = remember(groupedParagraphs, activeSentenceIdx) {
        if (activeSentenceIdx < 0) -1 else groupedParagraphs.indexOfFirst { (_, sentences) ->
            sentences.any { it.sentenceIndex == activeSentenceIdx }
        }
    }

    val topBarHeightPx = with(density) { 57.dp.toPx() }
    val topReservedPx = with(density) { 64.dp.toPx().toInt() }
    var topBarOffsetPx by remember { mutableFloatStateOf(0f) }
    val isTopBarPinned = state.isSearchOpen || state.translationError != null || showLanguageDialog || isImmersive

    LaunchedEffect(isTopBarPinned) {
        if (isTopBarPinned) topBarOffsetPx = 0f
    }

    val nestedScrollConnection = remember(topBarHeightPx, isImmersive, isTopBarPinned) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isImmersive && !isTopBarPinned && abs(available.y) > 0.5f) {
                    topBarOffsetPx = computeCollapsedTopBarOffsetPx(
                        currentOffsetPx = topBarOffsetPx,
                        scrollDeltaY = available.y,
                        maxCollapsePx = topBarHeightPx,
                        isPinnedOpen = isTopBarPinned
                    )
                }
                return Offset.Zero
            }
        }
    }

    // A real user drag suspends automatic following; it never pauses playback.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                isAutoFollowEnabled = false
            }
        }
    }

    // Hide the floating return pill automatically when the user manually scrolls back to the active paragraph.
    LaunchedEffect(listState, activeParagraphItemIndex, isAutoFollowEnabled) {
        if (isAutoFollowEnabled || activeParagraphItemIndex < 0) {
            leftActiveViewport = false
            return@LaunchedEffect
        }
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleTriples = layoutInfo.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) }
            Triple(
                listState.isScrollInProgress,
                visibleTriples,
                layoutInfo.viewportSize.height
            )
        }
            .distinctUntilChanged()
            .collect { (scrolling, visibleTriples, viewportH) ->
                val isVisible = isParagraphComfortablyVisible(
                    activeParagraphIndex = activeParagraphItemIndex,
                    visibleItemsOffsetsAndSizes = visibleTriples,
                    viewportHeightPx = viewportH,
                    topReservedPx = topReservedPx
                )
                if (!isVisible) {
                    leftActiveViewport = true
                } else if (leftActiveViewport && !scrolling) {
                    isAutoFollowEnabled = true
                    leftActiveViewport = false
                }
            }
    }

    // A new book/chapter starts a fresh reading-follow session.
    LaunchedEffect(activeBookId, state.activeChapterIndex) {
        isAutoFollowEnabled = initialAutoFollowEnabled
        leftActiveViewport = false
        returnToAudioRequest = 0
        topBarOffsetPx = 0f
    }

    LaunchedEffect(activeSentenceIdx, playbackState.status, isAutoFollowEnabled, activeParagraphItemIndex) {
        if (
            shouldAutoFollowPlayback(playbackState.status, isAutoFollowEnabled) &&
            activeParagraphItemIndex >= 0
        ) {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index }
            if (shouldScrollToParagraph(activeParagraphItemIndex, visibleItems)) {
                listState.animateScrollToItem(activeParagraphItemIndex, scrollOffset = 0)
            } else {
                val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeParagraphItemIndex }
                if (itemInfo != null) {
                    val adjustment = computeUpperThirdScrollAdjustmentPx(
                        itemOffsetPx = itemInfo.offset,
                        viewportHeightPx = layoutInfo.viewportSize.height,
                        topReservedPx = topReservedPx
                    )
                    if (abs(adjustment) > 12) {
                        listState.animateScrollBy(adjustment.toFloat())
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .nestedScroll(nestedScrollConnection)
            ) {
                if (groupedParagraphs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.reader_empty_chapter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = atelier.readerMetaText
                        )
                    }
                } else {
                    val verticalParagraphGap = when (paragraphPresentationMode) {
                        ParagraphPresentationMode.CURRENT_CARD -> 16.dp
                        ParagraphPresentationMode.FLAT_A -> 22.dp
                        ParagraphPresentationMode.ACTIVE_HIGHLIGHT_B -> 24.dp
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = if (isImmersive) 18.dp else 66.dp,
                            bottom = 84.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(verticalParagraphGap)
                    ) {
                        itemsIndexed(
                            items = groupedParagraphs,
                            key = { _, item -> item.first }
                        ) { itemIdx, (paragraphIndex, paragraphSentences) ->
                            val isParagraphTranslating = paragraphIndex in state.translatingParagraphIndices
                            val cachedParagraphTranslation = state.paragraphTranslations[paragraphIndex]
                            val currentSentence = paragraphSentences.firstOrNull { it.sentenceIndex == activeSentenceIdx }
                            val isActiveParagraph = currentSentence != null

                            ReaderParagraphItem(
                                paragraphIndex = paragraphIndex,
                                paragraphSentences = paragraphSentences,
                                isArabicSource = isArabicSource,
                                isActiveParagraph = isActiveParagraph,
                                isSelectedParagraph = selectedParagraphIndex == paragraphIndex,
                                isLastParagraph = itemIdx == groupedParagraphs.lastIndex,
                                activeSentenceIdx = activeSentenceIdx,
                                activeWordStartChar = playbackState.activeWordStartChar,
                                activeWordEndChar = playbackState.activeWordEndChar,
                                playbackStatus = playbackState.status,
                                isAutoFollowEnabled = isAutoFollowEnabled,
                                returnToAudioRequest = returnToAudioRequest,
                                showInlineTranslations = settings.showInlineTranslations,
                                isParagraphTranslating = isParagraphTranslating,
                                cachedParagraphTranslation = cachedParagraphTranslation,
                                revealedSentenceIds = state.revealedSentenceIds,
                                readerFontSizeSp = settings.readerFontSizeSp,
                                readerLineHeightMultiplier = settings.readerLineHeightMultiplier,
                                presentationMode = paragraphPresentationMode,
                                onSelectParagraph = { selectedParagraphIndex = it },
                                onTranslateOrToggleParagraph = onTranslateOrToggleParagraph,
                                onWordTapped = onWordTapped,
                                onPlayFromSentence = { sentence ->
                                    requestNotificationIfNeeded()
                                    onPlayFromSentence(sentence)
                                },
                                onSaveParagraphVocab = onSaveParagraphVocab,
                                onSaveSentenceVocab = onSaveSentenceVocab
                            )
                        }
                    }
                }

                // Collapsible Top Bar + Search/Error Banner overlay (never shifts LazyColumn scroll)
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isImmersive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = topBarOffsetPx }
                    ) {
                        ReaderToolbar(
                            bookTitle = state.book?.title,
                            chapterTitle = state.chapters.getOrNull(state.activeChapterIndex)?.title.orEmpty(),
                            hasBook = state.book != null,
                            isArabicSource = isArabicSource,
                            languageBadge = if (isArabicSource) "AR" else bookDirection.shortBadge,
                            isSearchOpen = state.isSearchOpen,
                            showInlineTranslations = settings.showInlineTranslations,
                            isImmersive = isImmersive,
                            onOpenChapters = { showChaptersSheet = true },
                            onOpenLanguagePicker = { showLanguageDialog = true },
                            onToggleSearch = onToggleSearch,
                            onToggleInlineTranslations = onToggleInlineTranslations,
                            onOpenAppearance = { showAppearanceSheet = true },
                            onToggleImmersive = onToggleImmersive
                        )

                        if (state.isSearchOpen) {
                            ReaderSearchPanel(
                                query = state.searchQuery,
                                results = state.searchResults,
                                onQueryChange = onSearchQueryChange,
                                onSelectResult = onJumpToSearchResult,
                                onClose = onToggleSearch
                            )
                        }

                        if (state.translationError != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = state.translationError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (activePackState.status != TranslationPackStatus.READY) {
                                        FilledTonalButton(onClick = onDownloadTranslationPack) {
                                            Text(
                                                if (activePackState.status == TranslationPackStatus.DOWNLOADING) {
                                                    stringResource(R.string.reader_download_in_progress)
                                                } else {
                                                    stringResource(R.string.reader_download_translation_pack, bookDirection.shortBadge)
                                                }
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = onDismissTranslationError,
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                                    }
                                }
                            }
                        }
                    }
                }

                // Subtle exit-immersive control so users can exit full-screen with tap or back gesture
                androidx.compose.animation.AnimatedVisibility(
                    visible = isImmersive,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, atelier.readerDivider)
                    ) {
                        IconButton(
                            onClick = onToggleImmersive,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("reader_exit_immersive_floating")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FullscreenExit,
                                contentDescription = stringResource(R.string.reader_exit_immersive),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Floating capsule "Return to Audio" button placed cleanly above the mini player
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAutoFollowEnabled && activeParagraphItemIndex >= 0,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Surface(
                        onClick = {
                            val target = activeParagraphItemIndex
                            returnToAudioRequest++
                            isAutoFollowEnabled = true
                            leftActiveViewport = false
                            if (target >= 0) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(target, scrollOffset = 0)
                                }
                            }
                        },
                        color = atelier.readerReturnChipBg,
                        contentColor = atelier.readerReturnChipContent,
                        shape = RoundedCornerShape(percent = 50),
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .heightIn(min = 46.dp, max = 48.dp)
                            .testTag("reader_return_to_audio_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = stringResource(R.string.reader_return_to_audio),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = UiSansFamily,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(150))
            ) {
                ReaderAudioBar(
                    playbackState = playbackState,
                    isPlayingThisChapter = isPlayingThisChapter,
                    currentSpeed = settings.playbackSpeed,
                    onTogglePlay = {
                        requestNotificationIfNeeded()
                        onTogglePlayChapter()
                    },
                    onPrev = onPrevSentence,
                    onNext = onNextSentence,
                    onSpeedSelected = onSpeedSelected,
                    onOpenFullPlayer = onOpenFullPlayer
                )
            }
        }
    }

    if (showChaptersSheet) {
        ReaderChaptersBottomSheet(
            bookTitle = state.book?.title,
            chapters = state.chapters,
            activeChapterIndex = state.activeChapterIndex,
            onSelectChapter = onSelectChapter,
            onDismiss = { showChaptersSheet = false }
        )
    }

    if (showLanguageDialog) {
        ReaderLanguagePickerDialog(
            bookSourceLanguage = bookSourceLang,
            isArabicSource = isArabicSource,
            onSelectToggleLanguage = onToggleBookLanguage,
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showAppearanceSheet) {
        ReaderAppearanceBottomSheet(
            bookSourceLang = bookSourceLang,
            isArabicSource = isArabicSource,
            languageBadge = if (isArabicSource) "AR" else bookDirection.shortBadge,
            settings = settings,
            onToggleBookLanguage = onToggleBookLanguage,
            onNightModeChange = onNightModeChange,
            onFontSizeChange = onFontSizeChange,
            onLineHeightChange = onLineHeightChange,
            onDismiss = { showAppearanceSheet = false }
        )
    }

    if (wordLookupSheet != null) {
        WordLookupModalSheet(
            sheet = wordLookupSheet,
            packStatus = translationManagerState.stateFor(
                TranslationDirection.forSource(wordLookupSheet.sourceLanguage)
            ).status,
            onPronounce = { onPronounceWord(wordLookupSheet.selectedWord) },
            onSave = onSaveWordVocab,
            onDownloadPack = onDownloadTranslationPack,
            onDismiss = onDismissWordSheet
        )
    }
}
