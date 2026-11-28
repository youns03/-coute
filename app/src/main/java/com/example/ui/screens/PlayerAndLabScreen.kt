package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.BenchmarkReportEntity
import com.example.data.BookEntity
import com.example.data.UserSettings
import com.example.domain.BenchmarkCorpus
import com.example.domain.BenchmarkItem
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelRole
import com.example.models.ModelUiEntry
import com.example.tts.EngineRuntimeState
import com.example.ui.components.AcousticWaveIndicator
import com.example.ui.components.AtelierCard
import com.example.ui.components.BookCoverArtwork
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.EditorialDisplayFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily
import com.example.ui.viewmodel.BenchmarkLabUiState

@Composable
fun PlayerAndLabScreen(
    playbackState: PlaybackUiState,
    activeBook: BookEntity?,
    engineState: EngineRuntimeState,
    settings: UserSettings,
    models: List<ModelUiEntry>,
    labState: BenchmarkLabUiState,
    reports: List<BenchmarkReportEntity>,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onNextParagraph: () -> Unit,
    onPrevParagraph: () -> Unit,
    onSeekRelativeSeconds: (Int) -> Unit,
    onReplaySentence: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSelectVoice: (String) -> Unit,
    onStartSleepTimerMinutes: (Int) -> Unit,
    onStartEndOfChapterTimer: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onReleaseEngine: () -> Unit,
    onRunSingleBenchmark: (String, BenchmarkItem, Float) -> Unit,
    onRunFullBenchmark: () -> Unit,
    onPlayWavSample: (String) -> Unit,
    onClearReports: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) }
                ) {
                    Text(stringResource(R.string.player_tab_player))
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Rounded.Science, contentDescription = null) },
                    modifier = Modifier.testTag("audio_lab_tab")
                ) {
                    Text(stringResource(R.string.player_tab_lab))
                }
            }

            if (selectedTab == 0) {
                PlayerTabContent(
                    playbackState = playbackState,
                    activeBook = activeBook,
                    engineState = engineState,
                    settings = settings,
                    models = models,
                    onTogglePlayPause = onTogglePlayPause,
                    onStop = onStop,
                    onNext = onNext,
                    onPrev = onPrev,
                    onNextParagraph = onNextParagraph,
                    onPrevParagraph = onPrevParagraph,
                    onSeekRelativeSeconds = onSeekRelativeSeconds,
                    onReplaySentence = onReplaySentence,
                    onToggleRepeat = onToggleRepeat,
                    onSetSpeed = onSetSpeed,
                    onSelectVoice = onSelectVoice,
                    onStartSleepTimerMinutes = onStartSleepTimerMinutes,
                    onStartEndOfChapterTimer = onStartEndOfChapterTimer,
                    onCancelSleepTimer = onCancelSleepTimer,
                    onReleaseEngine = onReleaseEngine
                )
            } else {
                AudioLabTabContent(
                    models = models.filter { it.spec.role == ModelRole.TTS },
                    labState = labState,
                    reports = reports,
                    onRunSingle = onRunSingleBenchmark,
                    onRunFullSuite = onRunFullBenchmark,
                    onPlayWav = onPlayWavSample,
                    onClearReports = onClearReports
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerTabContent(
    playbackState: PlaybackUiState,
    activeBook: BookEntity?,
    engineState: EngineRuntimeState,
    settings: UserSettings,
    models: List<ModelUiEntry>,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onNextParagraph: () -> Unit,
    onPrevParagraph: () -> Unit,
    onSeekRelativeSeconds: (Int) -> Unit,
    onReplaySentence: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSelectVoice: (String) -> Unit,
    onStartSleepTimerMinutes: (Int) -> Unit,
    onStartEndOfChapterTimer: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onReleaseEngine: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val isPlaying = playbackState.status == PlaybackStatus.PLAYING
    val isBuffering = playbackState.status == PlaybackStatus.BUFFERING
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showTechnicalTelemetry by remember { mutableStateOf(false) }

    val activeTtsModel = models.firstOrNull { it.isActive && it.spec.role == ModelRole.TTS }
    val availableVoices = activeTtsModel?.spec
        ?.voicesByLanguage
        ?.get(playbackState.activeLanguageCode.lowercase())
        .orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            AtelierCard(
                borderColor = if (isPlaying) MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f) else atelier.cardBorder,
                elevation = 2.dp,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (activeBook != null) {
                                BookCoverArtwork(
                                    book = activeBook,
                                    compact = true,
                                    modifier = Modifier.size(width = 50.dp, height = 68.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isPlaying || isBuffering) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AcousticWaveIndicator(
                                        isPlaying = isPlaying,
                                        isBuffering = isBuffering,
                                        barColor = if (isPlaying || isBuffering) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                        barCount = 5,
                                        modifier = Modifier
                                            .width(26.dp)
                                            .height(22.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = playbackState.bookTitle.ifBlank { stringResource(R.string.player_no_active_playback) },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (playbackState.totalSentencesInChapter > 0) {
                                        "${playbackState.chapterTitle} • فقرة ${playbackState.paragraphIndex + 1} • جملة ${playbackState.sentenceIndex + 1}/${playbackState.totalSentencesInChapter}"
                                    } else {
                                        stringResource(R.string.player_start_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val statusLabel = when (playbackState.status) {
                            PlaybackStatus.IDLE -> stringResource(R.string.playback_status_idle)
                            PlaybackStatus.BUFFERING -> stringResource(R.string.playback_status_buffering)
                            PlaybackStatus.PLAYING -> stringResource(R.string.playback_status_playing)
                            PlaybackStatus.PAUSED -> stringResource(R.string.playback_status_paused)
                            PlaybackStatus.ERROR -> stringResource(R.string.playback_status_error)
                        }
                        MonospaceMetricPill(
                            text = statusLabel,
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            contentColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Editorial Sentence Spotlight
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, atelier.cardBorder),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "Studio Sonore • Lecture vocale",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = EditorialDisplayFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = playbackState.currentSentenceText.ifBlank {
                                        "Sélectionnez un livre dans la bibliothèque pour écouter la prononciation locale."
                                    },
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = FrenchSerifFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }

                    if (isBuffering) {
                        LinearProgressIndicator(
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }

                    // Primary Transport Controls (-15s, Prev, Play/Pause, Next, +15s) in consistent LTR timeline order
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onSeekRelativeSeconds(-15) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_seek_back_15")
                            ) {
                                Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.player_seek_back_15_cd))
                            }
                            IconButton(onClick = onPrev, modifier = Modifier.minimumInteractiveComponentSize()) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.player_prev_cd))
                            }
                            Button(
                                onClick = onTogglePlayPause,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = atelier.readerSageAccent,
                                    contentColor = atelier.onReaderSageAccent
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_play_pause_button")
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.player_play_pause_cd)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isPlaying) stringResource(R.string.player_pause_label)
                                    else stringResource(R.string.player_play_label)
                                )
                            }
                            IconButton(onClick = onNext, modifier = Modifier.minimumInteractiveComponentSize()) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.player_next_cd))
                            }
                            IconButton(
                                onClick = { onSeekRelativeSeconds(15) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("player_seek_forward_15")
                            ) {
                                Icon(Icons.Rounded.Forward10, contentDescription = stringResource(R.string.player_seek_forward_15_cd))
                            }
                        }
                    }

                    // Secondary Row: Paragraph jump, Replay sentence, Repeat, Sleep Timer, Stop
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPrevParagraph,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(stringResource(R.string.player_prev_paragraph_cd), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onNextParagraph,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(stringResource(R.string.player_next_paragraph_cd), style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = onReplaySentence, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Replay, contentDescription = stringResource(R.string.player_replay_cd))
                        }
                        FilterChip(
                            selected = playbackState.repeatCurrentSentence,
                            onClick = onToggleRepeat,
                            leadingIcon = {
                                Icon(Icons.Rounded.RepeatOne, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(stringResource(R.string.player_repeat_label)) }
                        )
                        val sleepActive = playbackState.sleepTimerRemainingSec > 0 || playbackState.sleepTimerEndOfChapter
                        FilterChip(
                            selected = sleepActive,
                            onClick = { showSleepTimerSheet = true },
                            leadingIcon = {
                                Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = {
                                val txt = when {
                                    playbackState.sleepTimerRemainingSec > 0 -> {
                                        val m = playbackState.sleepTimerRemainingSec / 60
                                        val s = playbackState.sleepTimerRemainingSec % 60
                                        "%d:%02d".format(m, s)
                                    }
                                    playbackState.sleepTimerEndOfChapter -> stringResource(R.string.player_sleep_timer_eoc)
                                    else -> stringResource(R.string.player_sleep_timer)
                                }
                                Text(txt)
                            },
                            modifier = Modifier.testTag("player_sleep_timer_chip")
                        )
                        IconButton(onClick = onStop, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.player_stop_cd))
                        }
                    }

                    // Speed Pills
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.player_speed_label), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f).forEach { speed ->
                                FilterChip(
                                    selected = settings.playbackSpeed == speed,
                                    onClick = { onSetSpeed(speed) },
                                    label = {
                                        Text(
                                            "${speed}x",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = StudioMonoFamily)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Voice Picker for active model
                    if (availableVoices.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.player_voice_label), style = MaterialTheme.typography.labelMedium)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableVoices.forEach { voice ->
                                    val selected = playbackState.activeVoiceId == voice ||
                                        (playbackState.activeVoiceId.isBlank() && settings.activeVoiceId == voice)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectVoice(voice) },
                                        label = { Text(voice, style = MaterialTheme.typography.labelMedium.copy(fontFamily = StudioMonoFamily)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Collapsible Technical Telemetry Card
        item {
            AtelierCard(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTechnicalTelemetry = !showTechnicalTelemetry }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.player_engine_telemetry_title), style = MaterialTheme.typography.titleSmall)
                        }
                        Icon(
                            if (showTechnicalTelemetry) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showTechnicalTelemetry) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TelemetryTile(
                                    label = "قبل التحميل",
                                    value = "${"%.1f".format(engineState.ramBeforeLoadMb)} MB",
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryTile(
                                    label = "بعد التحميل",
                                    value = "${"%.1f".format(engineState.ramAfterLoadMb)} MB",
                                    modifier = Modifier.weight(1f)
                                )
                                TelemetryTile(
                                    label = "الذروة",
                                    value = "${"%.1f".format(engineState.ramPeakSynthesisMb)} MB",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider(color = atelier.cardBorder)
                            Text(
                                text = "النموذج المحمّل: ${engineState.loadedModelName ?: "غير محمل (يُحمّل عند التشغيل)"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "المُسرّع الفعلي: ${engineState.acceleratorLabel}" +
                                    if (engineState.fellBackToCpu) " (تراجع تلقائي لـ CPU)" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "وضع السرعة: ${settings.speedControlMode.labelAr} • الجمل المسبقة: ${playbackState.bufferedAheadCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = onReleaseEngine,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.player_release_ram))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSleepTimerSheet) {
        ModalBottomSheet(onDismissRequest = { showSleepTimerSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.player_sleep_timer_sheet_title), style = MaterialTheme.typography.titleLarge)
                listOf(15, 30, 45, 60).forEach { mins ->
                    FilledTonalButton(
                        onClick = {
                            onStartSleepTimerMinutes(mins)
                            showSleepTimerSheet = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                    ) {
                        Text(stringResource(R.string.player_sleep_timer_minutes, mins))
                    }
                }
                FilledTonalButton(
                    onClick = {
                        onStartEndOfChapterTimer()
                        showSleepTimerSheet = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                ) {
                    Text(stringResource(R.string.player_sleep_timer_eoc))
                }
                if (playbackState.sleepTimerRemainingSec > 0 || playbackState.sleepTimerEndOfChapter) {
                    OutlinedButton(
                        onClick = {
                            onCancelSleepTimer()
                            showSleepTimerSheet = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                    ) {
                        Text(stringResource(R.string.player_sleep_timer_cancel))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, atelier.cardBorder),
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = StudioMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioLabTabContent(
    models: List<ModelUiEntry>,
    labState: BenchmarkLabUiState,
    reports: List<BenchmarkReportEntity>,
    onRunSingle: (String, BenchmarkItem, Float) -> Unit,
    onRunFullSuite: () -> Unit,
    onPlayWav: (String) -> Unit,
    onClearReports: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val installedModels = models.filter { it.status == ModelInstallStatus.INSTALLED }
    var selectedModelId by remember(installedModels) {
        mutableStateOf(installedModels.firstOrNull()?.spec?.id ?: "")
    }
    var testSpeed by remember { mutableFloatStateOf(1.0f) }
    var confirmClearReports by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            AtelierCard(elevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Science, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text(stringResource(R.string.player_lab_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "يقيس الأداء الفعلي على جهازك الحالي: زمن التحميل، زمن أول صوت (TTFA)، معامل الزمن الحقيقي (RTF)، واستهلاك RAM الحقيقي مع حفظ عينات WAV للاستماع البشري.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (installedModels.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "نزّل نموذجاً واحداً على الأقل من شاشة إدارة النماذج لتشغيل اختبارات القياس.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Text("اختر النموذج للاختبار:", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            installedModels.forEach { entry ->
                                FilterChip(
                                    selected = selectedModelId == entry.spec.id,
                                    onClick = { selectedModelId = entry.spec.id },
                                    label = { Text(entry.spec.displayName) }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1.0f, 1.5f).forEach { sp ->
                                FilterChip(
                                    selected = testSpeed == sp,
                                    onClick = { testSpeed = sp },
                                    label = {
                                        Text("${sp}x", style = MaterialTheme.typography.labelMedium.copy(fontFamily = StudioMonoFamily))
                                    }
                                )
                            }
                        }

                        Button(
                            onClick = onRunFullSuite,
                            enabled = !labState.isRunning,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("run_full_benchmark_button")
                        ) {
                            if (labState.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(labState.currentStepLabel)
                            } else {
                                Text("تشغيل الاختبار الشامل على جميع النماذج المثبّتة")
                            }
                        }
                    }

                    if (!labState.lastError.isNullOrBlank()) {
                        Text(labState.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (installedModels.isNotEmpty() && selectedModelId.isNotBlank()) {
            item {
                EditorialSectionHeader(
                    titleAr = stringResource(R.string.player_lab_corpus_title),
                    folioTag = "Corpus",
                    countBadge = "${BenchmarkCorpus.items.size}"
                )
            }
            items(BenchmarkCorpus.items, key = { it.id }) { item ->
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.categoryAr, style = MaterialTheme.typography.titleSmall)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = item.frenchText.take(180) + if (item.frenchText.length > 180) "..." else "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FrenchSerifFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { onRunSingle(selectedModelId, item, testSpeed) },
                            enabled = !labState.isRunning,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("اختبار هذه العينة")
                        }
                    }
                }
            }
        }

        if (reports.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorialSectionHeader(
                        titleAr = stringResource(R.string.player_lab_reports_title),
                        folioTag = "Benchmarks",
                        countBadge = "${reports.size}",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { confirmClearReports = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.player_lab_clear_reports_cd))
                    }
                }
            }
            items(reports, key = { it.id }) { r ->
                AtelierCard(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${r.modelDisplayName} • ${r.corpusCategory}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "الجهاز: ${r.deviceModel} (${r.socOrAbi}, ${r.totalRamMb} MB RAM, ${r.androidVersion})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MonospaceMetricPill(text = "LOAD ${r.modelLoadTimeMs}ms")
                            MonospaceMetricPill(
                                text = "TTFA ${r.ttfaMs}ms",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                            MonospaceMetricPill(
                                text = "RTF ${"%.2f".format(r.realTimeFactor)}",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.tertiary
                            )
                            MonospaceMetricPill(text = "${r.acceleratorUsed} • ${r.speedFactor}x")
                        }
                        if (!r.wavSamplePath.isNullOrBlank()) {
                            HorizontalDivider(color = atelier.cardBorder)
                            OutlinedButton(
                                onClick = { onPlayWav(r.wavSamplePath) },
                                enabled = labState.playingSamplePath != r.wavSamplePath,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (labState.playingSamplePath == r.wavSamplePath) "جاري تشغيل WAV..." else "استماع للعينة (WAV)")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearReports) {
        AlertDialog(
            onDismissRequest = { confirmClearReports = false },
            title = { Text(stringResource(R.string.confirm_clear_reports_title)) },
            text = { Text(stringResource(R.string.confirm_clear_reports_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearReports = false
                        onClearReports()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearReports = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
