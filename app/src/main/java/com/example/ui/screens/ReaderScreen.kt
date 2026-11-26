package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
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
import com.example.ui.components.AtelierCard
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.viewmodel.ReaderUiState
import com.example.ui.viewmodel.WordLookupSheetState
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
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
    modifier: Modifier = Modifier
) {
    // Allow hardware back gesture to exit immersive reading cleanly
    BackHandler(enabled = isImmersive) {
        onToggleImmersive()
    }

    var showChaptersSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
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

    var isAutoFollowEnabled by remember { mutableStateOf(true) }
    var returnToAudioRequest by remember { mutableIntStateOf(0) }
    val activeParagraphItemIndex = remember(groupedParagraphs, activeSentenceIdx) {
        if (activeSentenceIdx < 0) -1 else groupedParagraphs.indexOfFirst { (_, sentences) ->
            sentences.any { it.sentenceIndex == activeSentenceIdx }
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

    // A new book/chapter starts a fresh reading-follow session.
    LaunchedEffect(activeBookId, state.activeChapterIndex) {
        isAutoFollowEnabled = true
        returnToAudioRequest = 0
    }

    LaunchedEffect(activeSentenceIdx, playbackState.status, isAutoFollowEnabled, activeParagraphItemIndex) {
        if (
            shouldAutoFollowPlayback(playbackState.status, isAutoFollowEnabled) &&
            activeParagraphItemIndex >= 0
        ) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index }
            if (shouldScrollToParagraph(activeParagraphItemIndex, visibleItems)) {
                listState.animateScrollToItem(activeParagraphItemIndex)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize()
        ) {
            ReaderToolbar(
                bookTitle = state.book?.title,
                chapterTitle = state.chapters.getOrNull(state.activeChapterIndex)?.title.orEmpty(),
                hasBook = state.book != null,
                languageBadge = bookDirection.shortBadge,
                isSearchOpen = state.isSearchOpen,
                showInlineTranslations = settings.showInlineTranslations,
                isImmersive = isImmersive,
                onOpenChapters = { showChaptersSheet = true },
                onToggleBookLanguage = onToggleBookLanguage,
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

            // Keep the return action outside the lazy content so it remains reachable.
            if (!isAutoFollowEnabled && activeParagraphItemIndex >= 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            val target = activeParagraphItemIndex
                            returnToAudioRequest++
                            isAutoFollowEnabled = true
                            if (target >= 0 && playbackState.status != PlaybackStatus.PLAYING) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(target)
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("reader_return_to_audio_button")
                    ) {
                        Text(stringResource(R.string.reader_return_to_audio))
                    }
                }
            }

            // Prose Area grouped by Paragraph (preserving originalText untouched)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(groupedParagraphs, key = { it.first }) { (paragraphIndex, paragraphSentences) ->
                    val isParagraphTranslating = paragraphIndex in state.translatingParagraphIndices
                    val allParagraphRevealed = paragraphSentences.all {
                        settings.showInlineTranslations || it.id in state.revealedSentenceIds
                    }
                    val cachedParagraphTranslation = state.paragraphTranslations[paragraphIndex]
                    val showParagraphTranslation = settings.showInlineTranslations ||
                        paragraphSentences.any { it.id in state.revealedSentenceIds }
                    val currentSentence = paragraphSentences.firstOrNull { it.sentenceIndex == activeSentenceIdx }
                    val isActiveParagraph = currentSentence != null
                    val paragraphBringIntoViewRequester = remember(paragraphIndex) { BringIntoViewRequester() }
                    var paragraphTextLayout by remember(paragraphIndex) { mutableStateOf<TextLayoutResult?>(null) }

                    AtelierCard(
                        containerColor = if (isActiveParagraph) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        },
                        borderColor = if (isActiveParagraph) {
                            atelier.activeParagraphStripe.copy(alpha = 0.5f)
                        } else {
                            atelier.cardBorder
                        },
                        elevation = if (isActiveParagraph) 2.dp else 0.dp,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            if (isActiveParagraph) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(atelier.activeParagraphStripe)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        MonospaceMetricPill(
                                            text = "P·${(paragraphIndex + 1).toString().padStart(2, '0')}",
                                            containerColor = if (isActiveParagraph) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            contentColor = if (isActiveParagraph) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Text(
                                            stringResource(R.string.reader_paragraph_number, paragraphIndex + 1),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    TextButton(
                                        onClick = { onTranslateOrToggleParagraph(paragraphIndex, paragraphSentences) },
                                        enabled = !isParagraphTranslating &&
                                            !(settings.showInlineTranslations && !cachedParagraphTranslation.isNullOrBlank()),
                                        modifier = Modifier.testTag("translate_paragraph_$paragraphIndex")
                                    ) {
                                        if (isParagraphTranslating) {
                                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(7.dp))
                                            Text(
                                                stringResource(R.string.reader_paragraph_translating),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        } else {
                                            Icon(
                                                when {
                                                    cachedParagraphTranslation.isNullOrBlank() -> Icons.Rounded.Translate
                                                    settings.showInlineTranslations -> Icons.Rounded.Translate
                                                    allParagraphRevealed -> Icons.Rounded.VisibilityOff
                                                    else -> Icons.Rounded.Translate
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                when {
                                                    !cachedParagraphTranslation.isNullOrBlank() && settings.showInlineTranslations ->
                                                        stringResource(R.string.reader_translation_complete)
                                                    !cachedParagraphTranslation.isNullOrBlank() && allParagraphRevealed ->
                                                        stringResource(R.string.reader_hide_translation)
                                                    !cachedParagraphTranslation.isNullOrBlank() ->
                                                        stringResource(R.string.reader_show_translation)
                                                    else -> stringResource(R.string.reader_translate_paragraph)
                                                },
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }

                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    val wordHighlightColor = MaterialTheme.colorScheme.secondary
                                    val wordHighlightBg = atelier.wordHighlightBg
                                    val annotated = remember(
                                        paragraphSentences, activeSentenceIdx,
                                        playbackState.activeWordStartChar, playbackState.activeWordEndChar,
                                        wordHighlightColor, wordHighlightBg
                                    ) {
                                        buildParagraphAnnotatedString(
                                            sentences = paragraphSentences,
                                            activeSentenceIndex = activeSentenceIdx,
                                            activeWordStart = playbackState.activeWordStartChar,
                                            activeWordEnd = playbackState.activeWordEndChar,
                                            highlightColor = wordHighlightColor,
                                            highlightBackground = wordHighlightBg
                                        )
                                    }
                                    LaunchedEffect(
                                        activeSentenceIdx,
                                        playbackState.status,
                                        isAutoFollowEnabled,
                                        returnToAudioRequest,
                                        paragraphTextLayout,
                                        annotated
                                    ) {
                                        val shouldRevealCurrentSentence = currentSentence != null &&
                                            isAutoFollowEnabled &&
                                            (playbackState.status == PlaybackStatus.PLAYING || returnToAudioRequest > 0)
                                        val layout = paragraphTextLayout
                                        if (shouldRevealCurrentSentence && layout != null && layout.size.width > 0) {
                                            val sentenceRange = annotated.getStringAnnotations(
                                                tag = "SENTENCE",
                                                start = 0,
                                                end = annotated.length
                                            ).firstOrNull { it.item == currentSentence.id.toString() }
                                            if (sentenceRange != null && sentenceRange.end > sentenceRange.start) {
                                                val firstLine = layout.getLineForOffset(sentenceRange.start)
                                                val lastLine = layout.getLineForOffset(sentenceRange.end - 1)
                                                paragraphBringIntoViewRequester.bringIntoView(
                                                    Rect(
                                                        left = 0f,
                                                        top = layout.getLineTop(firstLine),
                                                        right = layout.size.width.toFloat(),
                                                        bottom = layout.getLineBottom(lastLine)
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = annotated,
                                        onTextLayout = { paragraphTextLayout = it },
                                        style = TextStyle(
                                            fontFamily = FrenchSerifFamily,
                                            fontSize = settings.readerFontSizeSp.sp,
                                            lineHeight = (settings.readerFontSizeSp * settings.readerLineHeightMultiplier).sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier
                                            .bringIntoViewRequester(paragraphBringIntoViewRequester)
                                            .fillMaxWidth()
                                            .pointerInput(annotated, paragraphSentences, currentSentence) {
                                                detectTapGestures { pos ->
                                                    val layout = paragraphTextLayout ?: return@detectTapGestures
                                                    val offset = layout.getOffsetForPosition(pos)
                                                    val word = annotated.getStringAnnotations("WORD", offset, offset).firstOrNull()
                                                    val sentenceTag = annotated.getStringAnnotations("SENTENCE", offset, offset).firstOrNull()
                                                    val sentence = paragraphSentences.firstOrNull {
                                                        it.id.toString() == sentenceTag?.item
                                                    }
                                                    if (sentence != null) {
                                                        if (word != null) onWordTapped(word.item, sentence)
                                                        else {
                                                            requestNotificationIfNeeded()
                                                            onPlayFromSentence(sentence)
                                                        }
                                                    } else if (currentSentence != null) {
                                                        requestNotificationIfNeeded()
                                                        onPlayFromSentence(currentSentence)
                                                    }
                                                }
                                            }
                                            .testTag("reader_paragraph_text_$paragraphIndex")
                                    )
                                }

                                if (showParagraphTranslation) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, atelier.cardBorder),
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(3.dp)
                                                        .height(14.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(MaterialTheme.colorScheme.tertiary)
                                                )
                                                Text(
                                                    stringResource(R.string.reader_arabic_translation),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                            when {
                                                isParagraphTranslating -> {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                        Spacer(Modifier.width(9.dp))
                                                        Text(
                                                            stringResource(R.string.reader_translating_paragraph_locally),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                !cachedParagraphTranslation.isNullOrBlank() ||
                                                    paragraphSentences.any { !it.cachedArabicTranslation.isNullOrBlank() } -> {
                                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                                        Text(
                                                            text = cachedParagraphTranslation?.takeIf(String::isNotBlank)
                                                                ?: paragraphSentences.mapNotNull { it.cachedArabicTranslation?.takeIf(String::isNotBlank) }
                                                                    .joinToString(" "),
                                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                                fontFamily = ArabicSansFamily,
                                                                lineHeight = 29.sp
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    HorizontalDivider(color = atelier.cardBorder.copy(alpha = 0.6f))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (!cachedParagraphTranslation.isNullOrBlank()) {
                                                            TextButton(
                                                                onClick = {
                                                                    onSaveParagraphVocab(
                                                                        paragraphSentences,
                                                                        cachedParagraphTranslation
                                                                    )
                                                                },
                                                                modifier = Modifier.testTag("save_paragraph_$paragraphIndex")
                                                            ) {
                                                                Icon(
                                                                    Icons.Rounded.BookmarkAdd,
                                                                    contentDescription = stringResource(
                                                                        R.string.reader_save_paragraph_content_description
                                                                    ),
                                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                                Spacer(Modifier.width(5.dp))
                                                                Text(
                                                                    stringResource(R.string.reader_save_paragraph_to_vocabulary),
                                                                    style = MaterialTheme.typography.labelMedium,
                                                                    color = MaterialTheme.colorScheme.tertiary
                                                                )
                                                            }
                                                        } else {
                                                            Spacer(Modifier.width(1.dp))
                                                        }
                                                        Row(horizontalArrangement = Arrangement.End) {
                                                            paragraphSentences.forEach { sentence ->
                                                                val translated = sentence.cachedArabicTranslation
                                                                if (!translated.isNullOrBlank()) {
                                                                    IconButton(
                                                                        onClick = { onSaveSentenceVocab(sentence, translated) },
                                                                        modifier = Modifier
                                                                            .minimumInteractiveComponentSize()
                                                                            .testTag("save_sentence_${sentence.sentenceIndex}")
                                                                    ) {
                                                                        Icon(
                                                                            Icons.Rounded.BookmarkAdd,
                                                                            contentDescription = stringResource(
                                                                                R.string.reader_save_sentence_translation_content_description
                                                                            ),
                                                                            tint = MaterialTheme.colorScheme.tertiary,
                                                                            modifier = Modifier.size(18.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                else -> Text(
                                                    stringResource(R.string.reader_translate_paragraph_hint),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            ReaderAudioBar(
                playbackState = playbackState,
                bookSourceLanguage = bookSourceLang,
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

    if (showChaptersSheet) {
        ReaderChaptersBottomSheet(
            chapters = state.chapters,
            activeChapterIndex = state.activeChapterIndex,
            onSelectChapter = onSelectChapter,
            onDismiss = { showChaptersSheet = false }
        )
    }

    if (showAppearanceSheet) {
        ReaderAppearanceBottomSheet(
            bookSourceLang = bookSourceLang,
            languageBadge = bookDirection.shortBadge,
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

private fun buildParagraphAnnotatedString(
    sentences: List<SentenceEntity>,
    activeSentenceIndex: Int,
    activeWordStart: Int,
    activeWordEnd: Int,
    highlightColor: Color,
    highlightBackground: Color
) = buildAnnotatedString {
    var paragraphOffset = 0
    sentences.forEachIndexed { index, sentence ->
        val sentenceStart = paragraphOffset
        append(sentence.originalText)
        val sentenceEnd = sentenceStart + sentence.originalText.length
        if (sentenceEnd > sentenceStart) {
            addStringAnnotation("SENTENCE", sentence.id.toString(), sentenceStart, sentenceEnd)
        }
        val wordRegex = Regex("""[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*""")
        wordRegex.findAll(sentence.originalText).forEach { match ->
            addStringAnnotation(
                "WORD",
                match.value,
                sentenceStart + match.range.first,
                sentenceStart + match.range.last + 1
            )
        }
        if (sentence.sentenceIndex == activeSentenceIndex &&
            activeWordStart >= 0 && activeWordEnd > activeWordStart &&
            activeWordEnd <= sentence.originalText.length
        ) {
            addStyle(
                SpanStyle(
                    background = highlightBackground,
                    color = highlightColor,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline
                ),
                start = sentenceStart + activeWordStart,
                end = sentenceStart + activeWordEnd
            )
        }
        paragraphOffset = sentenceEnd
        if (index < sentences.lastIndex) {
            append(" ")
            paragraphOffset++
        }
    }
}
