package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.withTabularNumbers

@Composable
fun GlobalMiniPlayer(
    playbackState: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onSeekBack: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (playbackState.status == PlaybackStatus.IDLE || playbackState.status == PlaybackStatus.ERROR) {
        return
    }

    val atelier = LocalEcouteAtelierColors.current
    val isPlaying = playbackState.status == PlaybackStatus.PLAYING
    val isBuffering = playbackState.status == PlaybackStatus.BUFFERING
    val openDescription = stringResource(R.string.mini_player_open)
    val title = playbackState.bookTitle.ifBlank {
        playbackState.chapterTitle.ifBlank { stringResource(R.string.mini_player_title) }
    }
    val subtitle = playbackState.currentSentenceText.ifBlank {
        playbackState.chapterTitle.ifBlank { stringResource(R.string.mini_player_waiting_for_text) }
    }
    val chapterProgress = if (playbackState.totalSentencesInChapter > 0) {
        ((playbackState.sentenceIndex + 1).toFloat() / playbackState.totalSentencesInChapter.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = openDescription
            }
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPlaying) atelier.readerSageAccent.copy(alpha = 0.42f) else atelier.cardBorder
        ),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(20.dp)
    ) {
        BoxWithConstraints {
            val showSeekButtons = maxWidth >= 430.dp
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isPlaying || isBuffering) {
                                    atelier.readerSageAccent.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AcousticWaveIndicator(
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            barColor = atelier.readerSageAccent,
                            barCount = 4,
                            modifier = Modifier
                                .width(20.dp)
                                .height(18.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (playbackState.sleepTimerRemainingSec > 0) {
                                val min = playbackState.sleepTimerRemainingSec / 60
                                val sec = playbackState.sleepTimerRemainingSec % 60
                                Text(
                                    text = "%d:%02d".format(min, sec),
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                    color = atelier.readerSageAccent
                                )
                            } else if (playbackState.sleepTimerEndOfChapter) {
                                Text(
                                    text = stringResource(R.string.player_sleep_timer_eoc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = atelier.readerSageAccent
                                )
                            }
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FrenchSerifFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (showSeekButtons) {
                                IconButton(
                                    onClick = onSeekBack,
                                    enabled = !isBuffering,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Rounded.Replay10,
                                        contentDescription = stringResource(R.string.player_seek_back_15_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(
                                onClick = onPrevious,
                                enabled = !isBuffering,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    contentDescription = stringResource(R.string.mini_player_previous),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            FilledIconButton(
                                onClick = onTogglePlayPause,
                                enabled = !isBuffering,
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = atelier.readerSageAccent,
                                    contentColor = atelier.onReaderSageAccent
                                ),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = atelier.onReaderSageAccent
                                    )
                                } else {
                                    Icon(
                                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(
                                            if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play
                                        )
                                    )
                                }
                            }

                            IconButton(
                                onClick = onNext,
                                enabled = !isBuffering,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = stringResource(R.string.mini_player_next),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (showSeekButtons) {
                                IconButton(
                                    onClick = onSeekForward,
                                    enabled = !isBuffering,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Rounded.Forward10,
                                        contentDescription = stringResource(R.string.player_seek_forward_15_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (chapterProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { chapterProgress },
                        color = atelier.readerSageAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                    )
                }
            }
        }
    }
}
