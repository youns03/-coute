package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.SentenceEntity
import com.example.data.UserSettings
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.viewmodel.ReaderUiState
import com.example.ui.viewmodel.WordLookupSheetState

@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier
) {
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
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

    LaunchedEffect(activeSentenceIdx) {
        if (activeSentenceIdx >= 0) {
            val itemPos = groupedParagraphs.indexOfFirst { (_, sents) ->
                sents.any { it.sentenceIndex == activeSentenceIdx }
            }
            if (itemPos >= 0) {
                listState.animateScrollToItem(itemPos)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            // Compact Reader Toolbar
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = { showChaptersSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chapter_selector_button")
                        ) {
                            Icon(Icons.Rounded.List, contentDescription = "فصول الكتاب")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = state.book?.title ?: "اختر كتاباً من المكتبة",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val chTitle = state.chapters.getOrNull(state.activeChapterIndex)?.title.orEmpty()
                                if (chTitle.isNotEmpty()) {
                                    Text(
                                        text = chTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.book != null) {
                            AssistChip(
                                onClick = onToggleBookLanguage,
                                label = { Text(bookDirection.shortBadge, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("reader_language_chip")
                            )
                        }
                        IconButton(
                            onClick = onToggleSearch,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reader_search_button")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "بحث داخل الكتاب")
                        }
                        IconButton(
                            onClick = onToggleInlineTranslations,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_translation_button")
                        ) {
                            Icon(
                                if (settings.showInlineTranslations) Icons.Rounded.Translate else Icons.Rounded.VisibilityOff,
                                contentDescription = "إظهار أو إخفاء جميع الترجمات",
                                tint = if (settings.showInlineTranslations) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(
                            onClick = { showAppearanceSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reader_appearance_button")
                        ) {
                            Icon(Icons.Rounded.FormatSize, contentDescription = "تخصيص الخط والمظهر")
                        }
                    }
                }
            }

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
                                    if (activePackState.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..."
                                    else "تنزيل (${bookDirection.shortBadge})"
                                )
                            }
                        }
                        IconButton(onClick = onDismissTranslationError) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق")
                        }
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

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الفقرة ${paragraphIndex + 1}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(
                                onClick = { onTranslateOrToggleParagraph(paragraphIndex, paragraphSentences) },
                                enabled = !isParagraphTranslating &&
                                    !(settings.showInlineTranslations && !cachedParagraphTranslation.isNullOrBlank()),
                                modifier = Modifier.testTag("translate_paragraph_" + paragraphIndex)
                            ) {
                                if (isParagraphTranslating) {
                                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(7.dp))
                                    Text("تُترجم الفقرة...", style = MaterialTheme.typography.labelMedium)
                                } else {
                                    Icon(
                                        when {
                                            cachedParagraphTranslation.isNullOrBlank() -> Icons.Rounded.Translate
                                            settings.showInlineTranslations -> Icons.Rounded.Translate
                                            allParagraphRevealed -> Icons.Rounded.VisibilityOff
                                            else -> Icons.Rounded.Translate
                                        },
                                        contentDescription = null, modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        when {
                                            !cachedParagraphTranslation.isNullOrBlank() && settings.showInlineTranslations -> "تمت الترجمة"
                                            !cachedParagraphTranslation.isNullOrBlank() && allParagraphRevealed -> "إخفاء الترجمة"
                                            !cachedParagraphTranslation.isNullOrBlank() -> "عرض الترجمة"
                                            else -> "ترجمة الفقرة"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            val wordHighlightColor = MaterialTheme.colorScheme.primary
                            val annotated = remember(
                                paragraphSentences, activeSentenceIdx,
                                playbackState.activeWordStartChar, playbackState.activeWordEndChar,
                                wordHighlightColor
                            ) {
                                buildParagraphAnnotatedString(
                                    paragraphSentences, activeSentenceIdx,
                                    playbackState.activeWordStartChar, playbackState.activeWordEndChar,
                                    wordHighlightColor
                                )
                            }
                            ClickableText(
                                text = annotated,
                                style = TextStyle(
                                    fontFamily = FrenchSerifFamily,
                                    fontSize = settings.readerFontSizeSp.sp,
                                    lineHeight = (settings.readerFontSizeSp * settings.readerLineHeightMultiplier).sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                onClick = { offset ->
                                    val word = annotated.getStringAnnotations("WORD", offset, offset).firstOrNull()
                                    val sentenceTag = annotated.getStringAnnotations("SENTENCE", offset, offset).firstOrNull()
                                    val sentence = paragraphSentences.firstOrNull { it.id.toString() == sentenceTag?.item }
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
                                },
                                modifier = Modifier.fillMaxWidth().testTag("reader_paragraph_text_" + paragraphIndex)
                            )
                        }

                        if (showParagraphTranslation) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text("الترجمة العربية", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    when {
                                        isParagraphTranslating -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(9.dp))
                                                Text("تُترجم الفقرة محلياً...", style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                        fontFamily = ArabicSansFamily, lineHeight = 29.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                paragraphSentences.forEach { sentence ->
                                                    val translated = sentence.cachedArabicTranslation
                                                    if (!translated.isNullOrBlank()) {
                                                        IconButton(
                                                            onClick = { onSaveSentenceVocab(sentence, translated) },
                                                            modifier = Modifier.size(38.dp).testTag("save_sentence_${sentence.sentenceIndex}")
                                                        ) {
                                                            Icon(
                                                                Icons.Rounded.BookmarkAdd,
                                                                contentDescription = "حفظ ترجمة الجملة في القاموس",
                                                                tint = MaterialTheme.colorScheme.tertiary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        else -> Text("اضغط «ترجمة الفقرة» لعرض معناها بالعربية.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Docked Audio Playback Bar
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
        ModalBottomSheet(onDismissRequest = { showChaptersSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("فصول الكتاب", style = MaterialTheme.typography.titleLarge)
                state.chapters.forEach { ch ->
                    val selected = ch.chapterIndex == state.activeChapterIndex
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectChapter(ch.chapterIndex)
                                showChaptersSheet = false
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = ch.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = "${ch.sentenceCount} جملة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAppearanceSheet) {
        ModalBottomSheet(onDismissRequest = { showAppearanceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("مظهر القراءة ولغة النص", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("لغة الكتاب للترجمة (${bookDirection.shortBadge})", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onToggleBookLanguage) {
                        Text(if (bookSourceLang == SourceLanguage.FRENCH) "تبديل للإنجليزية" else "تبديل للفرنسية")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DarkMode, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("الوضع الليلي للقراءة", style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(checked = settings.nightMode, onCheckedChange = onNightModeChange)
                }
                Column {
                    Text("حجم الخط الأصلي: ${settings.readerFontSizeSp.toInt()} sp")
                    Slider(
                        value = settings.readerFontSizeSp,
                        onValueChange = onFontSizeChange,
                        valueRange = 15f..30f
                    )
                }
                Column {
                    Text("تباعد الأسطر: ${"%.2f".format(settings.readerLineHeightMultiplier)}x")
                    Slider(
                        value = settings.readerLineHeightMultiplier,
                        onValueChange = onLineHeightChange,
                        valueRange = 1.3f..2.2f
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (wordLookupSheet != null) {
        WordLookupModalSheet(
            sheet = wordLookupSheet,
            packStatus = translationManagerState.stateFor(TranslationDirection.forSource(wordLookupSheet.sourceLanguage)).status,
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
    highlightColor: androidx.compose.ui.graphics.Color
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
            addStringAnnotation("WORD", match.value, sentenceStart + match.range.first, sentenceStart + match.range.last + 1)
        }
        if (sentence.sentenceIndex == activeSentenceIndex &&
            activeWordStart >= 0 && activeWordEnd > activeWordStart &&
            activeWordEnd <= sentence.originalText.length
        ) {
            addStyle(
                SpanStyle(
                    background = highlightColor.copy(alpha = 0.18f),
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

@Suppress("DEPRECATION")
@Composable
private fun SentenceReaderItem(
    sentence: SentenceEntity,
    sourceLanguage: SourceLanguage,
    isCurrent: Boolean,
    activeWordStart: Int,
    activeWordEnd: Int,
    showTranslation: Boolean,
    isTranslating: Boolean,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    onPlaySentence: () -> Unit,
    onToggleTranslateSentence: () -> Unit,
    onSaveSentenceVocab: (String) -> Unit,
    onWordTapped: (String) -> Unit
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (isCurrent) 3.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (showTranslation) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = if (sourceLanguage == SourceLanguage.FRENCH) "Texte original" else "Original text",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val annotated = remember(sentence.originalText, activeWordStart, activeWordEnd, isCurrent) {
                    buildWordClickableAnnotatedString(
                        text = sentence.originalText,
                        activeStart = if (isCurrent) activeWordStart else -1,
                        activeEnd = if (isCurrent) activeWordEnd else -1,
                        highlightBg = androidx.compose.ui.graphics.Color(0x33C85A32)
                    )
                }
                ClickableText(
                    text = annotated,
                    style = TextStyle(
                        fontFamily = FrenchSerifFamily,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = { offset ->
                        val ann = annotated.getStringAnnotations("WORD", offset, offset).firstOrNull()
                        if (ann != null) {
                            onWordTapped(ann.item)
                        } else {
                            onPlaySentence()
                        }
                    }
                )
            }

            if (showTranslation) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "الترجمة العربية",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                when {
                    isTranslating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("جاري الترجمة محلياً...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    !sentence.cachedArabicTranslation.isNullOrBlank() -> {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            Text(
                                text = sentence.cachedArabicTranslation,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = ArabicSansFamily,
                                    lineHeight = 22.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "اضغط على أيقونة الترجمة لإظهار المعنى بالعربية.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Subtle compact action strip per sentence
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPlaySentence,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        Icons.Rounded.VolumeUp,
                        contentDescription = "تشغيل من هذه الجملة",
                        tint = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onToggleTranslateSentence,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        if (showTranslation && !sentence.cachedArabicTranslation.isNullOrBlank()) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Translate
                        },
                        contentDescription = if (showTranslation) "إخفاء أو تحديث الترجمة" else "ترجمة الجملة للعربية",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (!sentence.cachedArabicTranslation.isNullOrBlank()) {
                    IconButton(
                        onClick = { onSaveSentenceVocab(sentence.cachedArabicTranslation) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Rounded.BookmarkAdd,
                            contentDescription = "حفظ الجملة في القاموس",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun buildWordClickableAnnotatedString(
    text: String,
    activeStart: Int,
    activeEnd: Int,
    highlightBg: androidx.compose.ui.graphics.Color
) = buildAnnotatedString {
    append(text)
    val wordRegex = Regex("""[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*""")
    wordRegex.findAll(text).forEach { match ->
        addStringAnnotation(
            tag = "WORD",
            annotation = match.value,
            start = match.range.first,
            end = match.range.last + 1
        )
    }
    if (activeStart >= 0 && activeEnd > activeStart && activeEnd <= text.length) {
        addStyle(
            SpanStyle(
                background = highlightBg,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline
            ),
            start = activeStart,
            end = activeEnd
        )
    }
}

@Composable
private fun ReaderSearchPanel(
    query: String,
    results: List<SentenceEntity>,
    onQueryChange: (String) -> Unit,
    onSelectResult: (SentenceEntity) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("ابحث عن كلمة أو جملة داخل النص...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "إغلاق البحث")
                }
            }
            if (results.isNotEmpty()) {
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(results) { s ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectResult(s) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = s.originalText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "الفصل ${s.chapterIndex + 1} • جملة ${s.sentenceIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderAudioBar(
    playbackState: PlaybackUiState,
    bookSourceLanguage: SourceLanguage,
    isPlayingThisChapter: Boolean,
    currentSpeed: Float,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onOpenFullPlayer: () -> Unit
) {
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
    val isBuffering = isPlayingThisChapter && playbackState.status == PlaybackStatus.BUFFERING
    val isPlaying = isPlayingThisChapter && playbackState.status == PlaybackStatus.PLAYING
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isBuffering -> "جارٍ تجهيز الصوت"
                            isPlaying -> "يُقرأ الآن"
                            isPlayingThisChapter && playbackState.status == PlaybackStatus.PAUSED -> "متوقف مؤقتاً"
                            else -> "مشغّل القراءة"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = playbackState.currentSentenceText.takeIf {
                            isPlayingThisChapter && it.isNotBlank()
                        } ?: if (bookSourceLanguage == SourceLanguage.FRENCH) "جاهز للاستماع" else "Ready to listen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilterChip(
                    selected = true,
                    onClick = {
                        val current = speeds.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.01f }
                            .takeIf { it >= 0 } ?: 1
                        onSpeedSelected(speeds[(current + 1) % speeds.size])
                    },
                    label = { Text("${currentSpeed}×") },
                    modifier = Modifier.testTag("reader_speed_chip")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = onPrev,
                    enabled = isPlayingThisChapter,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("reader_previous_button")
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "الجملة السابقة",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("reader_play_pause_button"),
                    contentPadding = PaddingValues(horizontal = 25.dp, vertical = 12.dp)
                ) {
                    when {
                        isBuffering -> CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                        isPlaying -> Icon(Icons.Rounded.Pause, contentDescription = "إيقاف مؤقت")
                        else -> Icon(Icons.Rounded.PlayArrow, contentDescription = "تشغيل")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPlaying) "إيقاف مؤقت" else "استماع", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(
                    onClick = onNext,
                    enabled = isPlayingThisChapter,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("reader_next_button")
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "الجملة التالية",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(5.dp))
                TextButton(onClick = onOpenFullPlayer, modifier = Modifier.testTag("reader_full_player_button")) {
                    Text("المشغّل")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordLookupModalSheet(
    sheet: WordLookupSheetState,
    packStatus: TranslationPackStatus,
    onPronounce: () -> Unit,
    onSave: () -> Unit,
    onDownloadPack: () -> Unit,
    onDismiss: () -> Unit
) {
    val direction = TranslationDirection.forSource(sheet.sourceLanguage)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = sheet.selectedWord,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FrenchSerifFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                FilledTonalButton(
                    onClick = onPronounce,
                    modifier = Modifier.testTag("pronounce_word_button")
                ) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("نطق")
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الترجمة العربية (${direction.shortBadge} دون اتصال):", style = MaterialTheme.typography.labelMedium)
                    when {
                        sheet.isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        !sheet.translation.isNullOrBlank() -> {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Text(
                                    text = sheet.translation,
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = ArabicSansFamily),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = sheet.error ?: "تعذر الترجمة.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (packStatus != TranslationPackStatus.READY) {
                                Button(
                                    onClick = onDownloadPack,
                                    enabled = packStatus != TranslationPackStatus.DOWNLOADING
                                ) {
                                    Text(
                                        if (packStatus == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..."
                                        else "تنزيل حزمة ${direction.shortBadge} (~30 MB)"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = sheet.contextSentence.originalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FrenchSerifFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!sheet.translation.isNullOrBlank()) {
                Button(
                    onClick = onSave,
                    enabled = !sheet.savedToVocab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("save_word_vocab_button")
                ) {
                    Icon(
                        if (sheet.savedToVocab) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (sheet.savedToVocab) "تم الحفظ في قاموس المفردات" else "حفظ في قاموس المفردات")
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
