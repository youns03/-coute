package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.example.data.importengine.LanguageDetector
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationPackStatus
import com.example.ui.components.AcousticWaveIndicator
import com.example.ui.components.AtelierCard
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.UiSansFamily
import com.example.ui.theme.withTabularNumbers
import com.example.ui.viewmodel.WordLookupSheetState
import kotlin.math.abs

@Composable
internal fun ReaderToolbar(
    bookTitle: String?,
    chapterTitle: String,
    hasBook: Boolean,
    isArabicSource: Boolean = false,
    languageBadge: String,
    isSearchOpen: Boolean,
    showInlineTranslations: Boolean,
    isImmersive: Boolean,
    onOpenChapters: () -> Unit,
    onOpenLanguagePicker: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleInlineTranslations: () -> Unit,
    onOpenAppearance: () -> Unit,
    onToggleImmersive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    var showOverflowMenu by remember { mutableStateOf(false) }
    val resolvedBookTitle = bookTitle ?: stringResource(R.string.reader_choose_book)
    val openChaptersDesc = stringResource(R.string.reader_open_chapters_cd, resolvedBookTitle)
    val fontScale = LocalDensity.current.fontScale

    Surface(
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp)
            ) {
                val isCompact = maxWidth < 375.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Book + Chapter clickable area (no heavy pill, generous flexible space)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp, max = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenChapters)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = openChaptersDesc
                            }
                            .testTag("chapter_selector_button")
                    ) {
                        Column(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val titleIsArabic = isArabicSource || LanguageDetector.isArabicText(resolvedBookTitle)
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (titleIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                            ) {
                                Text(
                                    text = resolvedBookTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = if (titleIsArabic) ArabicSansFamily else FrenchSerifFamily,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (chapterTitle.isNotEmpty()) {
                                val chapterIsArabic = isArabicSource || LanguageDetector.isArabicText(chapterTitle)
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides if (chapterIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                                ) {
                                    Text(
                                        text = chapterTitle,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = if (chapterIsArabic) ArabicSansFamily else FrenchSerifFamily,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = atelier.readerMetaText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = atelier.readerMetaText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // At most 3 primary controls: Language selector chip, Search, More overflow menu
                    if (hasBook) {
                        val langDesc = stringResource(R.string.reader_language_chip_cd, languageBadge)
                        val chipHorizontalPad = if (isCompact && fontScale > 1.15f) 8.dp else 10.dp
                        Surface(
                            onClick = onOpenLanguagePicker,
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            border = BorderStroke(1.dp, atelier.readerDivider),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .minimumInteractiveComponentSize()
                                .semantics {
                                    contentDescription = langDesc
                                }
                                .testTag("reader_language_chip")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = chipHorizontalPad, vertical = 6.dp)
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = languageBadge,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = UiSansFamily,
                                            fontSize = if (isCompact && fontScale > 1.2f) 13.sp else 14.sp,
                                            fontWeight = FontWeight.Medium
                                        ).withTabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Search transparent 48x48 button
                    val searchDesc = stringResource(R.string.reader_search_content_description)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSearchOpen) atelier.readerSageAccent.copy(alpha = 0.14f)
                                else Color.Transparent
                            )
                            .clickable(onClick = onToggleSearch)
                            .semantics {
                                role = Role.Button
                                contentDescription = searchDesc
                            }
                            .testTag("reader_search_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = if (isSearchOpen) atelier.readerSageAccent else atelier.readerMetaText,
                            modifier = Modifier.size(23.dp)
                        )
                    }

                    // More options overflow transparent 48x48 button -> 16dp themed DropdownMenu
                    Box {
                        val moreDesc = stringResource(R.string.reader_more_options_cd)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showOverflowMenu = true }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = moreDesc
                                }
                                .testTag("reader_more_menu_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                                tint = atelier.readerMetaText,
                                modifier = Modifier.size(23.dp)
                            )
                        }

                        MaterialTheme(
                            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
                        ) {
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp
                            ) {
                                if (!isArabicSource) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(
                                                    if (showInlineTranslations) R.string.reader_hide_all_translations
                                                    else R.string.reader_show_all_translations
                                                ),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (showInlineTranslations) Icons.Rounded.VisibilityOff else Icons.Rounded.Translate,
                                                contentDescription = null,
                                                tint = if (showInlineTranslations) atelier.readerSageAccent else atelier.readerMetaText
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onToggleInlineTranslations()
                                        },
                                        modifier = Modifier.testTag("toggle_translation_button")
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.reader_appearance_content_description),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.FormatSize,
                                            contentDescription = null,
                                            tint = atelier.readerMetaText
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenAppearance()
                                    },
                                    modifier = Modifier.testTag("reader_appearance_button")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(
                                                if (isImmersive) R.string.reader_exit_immersive
                                                else R.string.reader_enter_immersive
                                            ),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isImmersive) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                            contentDescription = null,
                                            tint = atelier.readerMetaText
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleImmersive()
                                    },
                                    modifier = Modifier.testTag("reader_immersive_button")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.reader_chapters_title),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.List,
                                            contentDescription = null,
                                            tint = atelier.readerMetaText
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenChapters()
                                    },
                                    modifier = Modifier.testTag("reader_chapters_menu_item")
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.8.dp, color = atelier.readerDivider)
        }
    }
}

@Composable
internal fun ReaderLanguagePickerDialog(
    bookSourceLanguage: SourceLanguage,
    isArabicSource: Boolean,
    onSelectToggleLanguage: () -> Unit,
    onDismiss: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.reader_language_picker_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.reader_language_picker_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = atelier.readerMetaText
                )
                if (isArabicSource) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.reader_language_arabic_only),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    val isFrench = bookSourceLanguage == SourceLanguage.FRENCH
                    listOf(
                        SourceLanguage.FRENCH to stringResource(R.string.reader_language_french_option),
                        SourceLanguage.ENGLISH to stringResource(R.string.reader_language_english_option)
                    ).forEach { (lang, label) ->
                        val selected = lang == bookSourceLanguage
                        Surface(
                            onClick = {
                                if (!selected) onSelectToggleLanguage()
                                onDismiss()
                            },
                            color = if (selected) {
                                atelier.readerSageAccent.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) atelier.readerSageAccent.copy(alpha = 0.45f) else atelier.readerDivider
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium.withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    if (!isFrench && bookSourceLanguage != SourceLanguage.ENGLISH) {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
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
        border = BorderStroke(1.dp, atelier.readerDivider),
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
                        focusedBorderColor = atelier.readerSageAccent,
                        unfocusedBorderColor = atelier.readerDivider
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
                            val resultIsArabic = LanguageDetector.isArabicText(s.originalText)
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (resultIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                            ) {
                                Text(
                                    text = s.originalText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = if (resultIsArabic) ArabicSansFamily else FrenchSerifFamily
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.reader_search_result_position,
                                        s.chapterIndex + 1,
                                        s.sentenceIndex + 1
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = UiSansFamily
                                    ).withTabularNumbers(),
                                    color = atelier.readerMetaText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = atelier.readerDivider.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderAudioBar(
    playbackState: PlaybackUiState,
    isPlayingThisChapter: Boolean,
    currentSpeed: Float,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
    val isBuffering = isPlayingThisChapter && playbackState.status == PlaybackStatus.BUFFERING
    val isPlaying = isPlayingThisChapter && playbackState.status == PlaybackStatus.PLAYING
    val isPaused = isPlayingThisChapter && playbackState.status == PlaybackStatus.PAUSED
    val atelier = LocalEcouteAtelierColors.current
    val fontScale = LocalDensity.current.fontScale

    val statusText = when {
        isBuffering -> stringResource(R.string.reader_audio_preparing)
        isPlaying -> stringResource(R.string.reader_audio_now_reading)
        isPaused -> stringResource(R.string.reader_audio_paused)
        else -> stringResource(R.string.reader_audio_player)
    }
    val speedLabel = stringResource(R.string.reader_speed_multiplier, currentSpeed.toString())
    val speedDesc = stringResource(R.string.reader_speed_chip_cd, currentSpeed.toString())
    val prevDesc = stringResource(R.string.reader_previous_sentence_content_description)
    val nextDesc = stringResource(R.string.reader_next_sentence_content_description)
    val expandDesc = stringResource(R.string.reader_full_player_button)
    val playPauseDesc = stringResource(
        when {
            isBuffering -> R.string.reader_audio_preparing
            isPlaying -> R.string.reader_pause_label
            else -> R.string.reader_play_content_description
        }
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isPlaying) atelier.readerSageAccent.copy(alpha = 0.42f) else atelier.readerDivider
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val isCompact = maxWidth < 365.dp
                val secondaryControlSize = if (isCompact) 44.dp else 48.dp

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isCompact) 2.dp else 4.dp)
                ) {
                    // Consistent LTR timeline transport cluster: [Prev (|<), Play (48dp Sage), Next (>|)]
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 0.dp else 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(secondaryControlSize)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = isPlayingThisChapter && !isBuffering, onClick = onPrev)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = prevDesc
                                    }
                                    .testTag("reader_previous_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = null,
                                    tint = if (isPlayingThisChapter) atelier.readerMetaText else atelier.readerMetaText.copy(alpha = 0.45f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(atelier.readerSageAccent)
                                    .clickable(onClick = onTogglePlay)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = playPauseDesc
                                    }
                                    .testTag("reader_play_pause_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    isBuffering -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.2.dp,
                                        color = atelier.onReaderSageAccent
                                    )
                                    isPlaying -> Icon(
                                        imageVector = Icons.Rounded.Pause,
                                        contentDescription = null,
                                        tint = atelier.onReaderSageAccent,
                                        modifier = Modifier.size(25.dp)
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = atelier.onReaderSageAccent,
                                        modifier = Modifier.size(25.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(secondaryControlSize)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = isPlayingThisChapter && !isBuffering, onClick = onNext)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = nextDesc
                                    }
                                    .testTag("reader_next_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = null,
                                    tint = if (isPlayingThisChapter) atelier.readerMetaText else atelier.readerMetaText.copy(alpha = 0.45f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Concise Arabic playback status + small acoustic indicator when active
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (isCompact) 2.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isPlaying || isBuffering) {
                            AcousticWaveIndicator(
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                barColor = atelier.readerSageAccent,
                                barCount = 3,
                                modifier = Modifier
                                    .width(14.dp)
                                    .height(14.dp)
                            )
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = UiSansFamily,
                                fontSize = if (isCompact && fontScale > 1.2f) 11.5.sp else 12.5.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (isPlaying || isBuffering) atelier.readerSageAccent else atelier.readerMetaText,
                            maxLines = if (isCompact && fontScale > 1.15f) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Speed capsule: height 40dp, corner 14dp, 14sp Medium UiSansFamily + tnum
                    Surface(
                        onClick = {
                            val current = speeds.indexOfFirst { abs(it - currentSpeed) < 0.01f }
                                .takeIf { it >= 0 } ?: 1
                            onSpeedSelected(speeds[(current + 1) % speeds.size])
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .semantics { contentDescription = speedDesc }
                            .testTag("reader_speed_chip")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = if (isCompact) 9.dp else 12.dp, vertical = 6.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = speedLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = UiSansFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ).withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Expand to Full Player icon button
                    Box(
                        modifier = Modifier
                            .size(secondaryControlSize)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenFullPlayer)
                            .semantics {
                                role = Role.Button
                                contentDescription = expandDesc
                            }
                            .testTag("reader_full_player_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInFull,
                            contentDescription = null,
                            tint = atelier.readerMetaText,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderChaptersBottomSheet(
    bookTitle: String?,
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
            if (!bookTitle.isNullOrBlank()) {
                val titleIsArabic = LanguageDetector.isArabicText(bookTitle)
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (titleIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = if (titleIsArabic) ArabicSansFamily else FrenchSerifFamily
                        ),
                        color = atelier.readerMetaText
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chapters, key = { it.id }) { ch ->
                    val selected = ch.chapterIndex == activeChapterIndex
                    Surface(
                        color = if (selected) atelier.readerSageAccent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (selected) atelier.readerSageAccent.copy(alpha = 0.5f) else atelier.readerDivider
                        ),
                        shape = RoundedCornerShape(16.dp),
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
                            val chIsArabic = LanguageDetector.isArabicText(ch.title)
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (chIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                            ) {
                                Text(
                                    text = ch.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = if (chIsArabic) ArabicSansFamily else FrenchSerifFamily,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (selected) atelier.readerSageAccent else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.reader_sentence_count, ch.sentenceCount, ch.sentenceCount),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = UiSansFamily).withTabularNumbers(),
                                    color = atelier.readerMetaText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceBottomSheet(
    bookSourceLang: SourceLanguage,
    isArabicSource: Boolean = false,
    languageBadge: String,
    settings: UserSettings,
    onToggleBookLanguage: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val clampedFontSize = settings.readerFontSizeSp.coerceIn(18f, 30f)
    val clampedLineHeight = settings.readerLineHeightMultiplier.coerceIn(1.3f, 2.2f)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.reader_appearance_title), style = MaterialTheme.typography.titleLarge)
            if (!isArabicSource) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.reader_language_for_translation, languageBadge),
                        style = MaterialTheme.typography.bodyMedium.withTabularNumbers()
                    )
                    OutlinedButton(onClick = onToggleBookLanguage) {
                        Text(
                            if (bookSourceLang == SourceLanguage.FRENCH) stringResource(R.string.reader_switch_to_english)
                            else stringResource(R.string.reader_switch_to_french)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = atelier.readerSageAccent)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reader_night_mode), style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = settings.nightMode, onCheckedChange = onNightModeChange)
            }
            Column {
                Text(
                    stringResource(R.string.reader_font_size_label, clampedFontSize.toInt()),
                    style = MaterialTheme.typography.bodyMedium.withTabularNumbers()
                )
                Slider(
                    value = clampedFontSize,
                    onValueChange = onFontSizeChange,
                    valueRange = 18f..30f
                )
            }
            Column {
                Text(
                    stringResource(
                        R.string.reader_line_spacing_label,
                        "%.2f".format(clampedLineHeight)
                    ),
                    style = MaterialTheme.typography.bodyMedium.withTabularNumbers()
                )
                Slider(
                    value = clampedLineHeight,
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
                            fontSize = clampedFontSize.sp,
                            lineHeight = (clampedFontSize * clampedLineHeight).sp,
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
    val atelier = LocalEcouteAtelierColors.current
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
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        color = atelier.readerSageAccent
                    )
                    when {
                        sheet.isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = atelier.readerSageAccent
                            )
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
                    color = atelier.readerMetaText
                )
            }

            if (!sheet.translation.isNullOrBlank()) {
                Button(
                    onClick = onSave,
                    enabled = !sheet.savedToVocab,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = atelier.readerSageAccent,
                        contentColor = atelier.onReaderSageAccent
                    ),
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
