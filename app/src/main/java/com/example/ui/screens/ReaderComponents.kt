package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.ChapterEntity
import com.example.data.SentenceEntity
import com.example.data.UserSettings
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationPackStatus
import com.example.ui.components.AcousticWaveIndicator
import com.example.ui.components.AtelierCard
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily
import com.example.ui.viewmodel.WordLookupSheetState

@Composable
internal fun ReaderToolbar(
    bookTitle: String?,
    chapterTitle: String,
    hasBook: Boolean,
    languageBadge: String,
    isSearchOpen: Boolean,
    showInlineTranslations: Boolean,
    isImmersive: Boolean,
    onOpenChapters: () -> Unit,
    onToggleBookLanguage: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleInlineTranslations: () -> Unit,
    onOpenAppearance: () -> Unit,
    onToggleImmersive: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    Surface(
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onOpenChapters,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chapter_selector_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.List,
                            contentDescription = stringResource(R.string.reader_chapters_title)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = bookTitle ?: stringResource(R.string.reader_choose_book),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = FrenchSerifFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (chapterTitle.isNotEmpty()) {
                                Text(
                                    text = chapterTitle,
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
                    if (hasBook) {
                        AssistChip(
                            onClick = onToggleBookLanguage,
                            label = {
                                Text(
                                    languageBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = StudioMonoFamily)
                                )
                            },
                            modifier = Modifier.testTag("reader_language_chip")
                        )
                    }
                    IconButton(
                        onClick = onToggleSearch,
                        colors = if (isSearchOpen) {
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            IconButtonDefaults.iconButtonColors()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reader_search_button")
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.reader_search_content_description)
                        )
                    }
                    IconButton(
                        onClick = onToggleInlineTranslations,
                        colors = if (showInlineTranslations) {
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                        } else {
                            IconButtonDefaults.iconButtonColors()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_translation_button")
                    ) {
                        Icon(
                            if (showInlineTranslations) Icons.Rounded.Translate else Icons.Rounded.VisibilityOff,
                            contentDescription = stringResource(R.string.reader_toggle_translations_content_description)
                        )
                    }
                    IconButton(
                        onClick = onOpenAppearance,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reader_appearance_button")
                    ) {
                        Icon(
                            Icons.Rounded.FormatSize,
                            contentDescription = stringResource(R.string.reader_appearance_content_description)
                        )
                    }
                    IconButton(
                        onClick = onToggleImmersive,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reader_immersive_button")
                    ) {
                        Icon(
                            if (isImmersive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = stringResource(
                                if (isImmersive) R.string.reader_exit_immersive else R.string.reader_enter_immersive
                            )
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = atelier.cardBorder)
        }
    }
}

@Composable
internal fun ReaderSearchPanel(
    query: String,
    results: List<SentenceEntity>,
    onQueryChange: (String) -> Unit,
    onSelectResult: (SentenceEntity) -> Unit,
    onClose: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, atelier.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.reader_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = atelier.cardBorder
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.reader_close_search_content_description)
                    )
                }
            }
            if (results.isNotEmpty()) {
                LazyColumn(modifier = Modifier.height(170.dp)) {
                    items(results) { s ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectResult(s) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = s.originalText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FrenchSerifFamily),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            MonospaceMetricPill(
                                text = stringResource(
                                    R.string.reader_search_result_position,
                                    s.chapterIndex + 1,
                                    s.sentenceIndex + 1
                                ),
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider(color = atelier.cardBorder.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderAudioBar(
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
    val atelier = LocalEcouteAtelierColors.current

    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPlaying) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else atelier.cardBorder
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isPlaying || isBuffering) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AcousticWaveIndicator(
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            barColor = MaterialTheme.colorScheme.secondary,
                            barCount = 4,
                            modifier = Modifier
                                .width(18.dp)
                                .height(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isBuffering -> stringResource(R.string.reader_audio_preparing)
                                isPlaying -> stringResource(R.string.reader_audio_now_reading)
                                isPlayingThisChapter && playbackState.status == PlaybackStatus.PAUSED -> stringResource(R.string.reader_audio_paused)
                                else -> stringResource(R.string.reader_audio_player)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = playbackState.currentSentenceText.takeIf {
                                    isPlayingThisChapter && it.isNotBlank()
                                } ?: if (bookSourceLanguage == SourceLanguage.FRENCH) {
                                    stringResource(R.string.reader_ready_to_listen_french)
                                } else {
                                    stringResource(R.string.reader_ready_to_listen_english)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FrenchSerifFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = true,
                    onClick = {
                        val current = speeds.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.01f }
                            .takeIf { it >= 0 } ?: 1
                        onSpeedSelected(speeds[(current + 1) % speeds.size])
                    },
                    label = {
                        Text(
                            stringResource(R.string.reader_speed_multiplier, currentSpeed.toString()),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = StudioMonoFamily)
                        )
                    },
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
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reader_previous_button")
                ) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.reader_previous_sentence_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onTogglePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        contentColor = if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reader_play_pause_button"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    when {
                        isBuffering -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        isPlaying -> Icon(
                            Icons.Rounded.Pause,
                            contentDescription = stringResource(R.string.reader_pause_label)
                        )
                        else -> Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.reader_play_content_description)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isPlaying) stringResource(R.string.reader_pause_label)
                        else stringResource(R.string.reader_listen_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onNext,
                    enabled = isPlayingThisChapter,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reader_next_button")
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.reader_next_sentence_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onOpenFullPlayer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reader_full_player_button")
                ) {
                    Text(stringResource(R.string.reader_full_player_button))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChaptersBottomSheet(
    chapters: List<ChapterEntity>,
    activeChapterIndex: Int,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.reader_chapters_title), style = MaterialTheme.typography.titleLarge)
            chapters.forEach { ch ->
                val selected = ch.chapterIndex == activeChapterIndex
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f) else atelier.cardBorder
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectChapter(ch.chapterIndex)
                            onDismiss()
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
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FrenchSerifFamily),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        MonospaceMetricPill(
                            text = pluralStringResource(R.plurals.reader_sentence_count, ch.sentenceCount, ch.sentenceCount),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceBottomSheet(
    bookSourceLang: SourceLanguage,
    languageBadge: String,
    settings: UserSettings,
    onToggleBookLanguage: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.reader_appearance_title), style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.reader_language_for_translation, languageBadge),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onToggleBookLanguage) {
                    Text(
                        if (bookSourceLang == SourceLanguage.FRENCH) stringResource(R.string.reader_switch_to_english)
                        else stringResource(R.string.reader_switch_to_french)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reader_night_mode), style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = settings.nightMode, onCheckedChange = onNightModeChange)
            }
            Column {
                Text(stringResource(R.string.reader_font_size_label, settings.readerFontSizeSp.toInt()))
                Slider(
                    value = settings.readerFontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = 15f..30f
                )
            }
            Column {
                Text(
                    stringResource(
                        R.string.reader_line_spacing_label,
                        "%.2f".format(settings.readerLineHeightMultiplier)
                    )
                )
                Slider(
                    value = settings.readerLineHeightMultiplier,
                    onValueChange = onLineHeightChange,
                    valueRange = 1.3f..2.2f
                )
            }
            AtelierCard(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = "La lecture nourrit l'esprit et éclaire la pensée.",
                            fontFamily = FrenchSerifFamily,
                            fontSize = settings.readerFontSizeSp.sp,
                            lineHeight = (settings.readerFontSizeSp * settings.readerLineHeightMultiplier).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordLookupModalSheet(
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
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("pronounce_word_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.reader_pronounce_button))
                }
            }

            AtelierCard(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.reader_offline_translation_label, direction.shortBadge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
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
                                text = sheet.error ?: stringResource(R.string.reader_translation_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (packStatus != TranslationPackStatus.READY) {
                                Button(
                                    onClick = onDownloadPack,
                                    enabled = packStatus != TranslationPackStatus.DOWNLOADING
                                ) {
                                    Text(
                                        if (packStatus == TranslationPackStatus.DOWNLOADING) {
                                            stringResource(R.string.reader_download_in_progress)
                                        } else {
                                            stringResource(
                                                R.string.reader_download_translation_pack_with_size,
                                                direction.shortBadge
                                            )
                                        }
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
                    shape = RoundedCornerShape(14.dp),
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
                    Text(
                        if (sheet.savedToVocab) stringResource(R.string.reader_saved_to_vocabulary)
                        else stringResource(R.string.reader_save_to_vocabulary)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
