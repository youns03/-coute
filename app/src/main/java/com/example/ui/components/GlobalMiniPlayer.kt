package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState

@Composable
fun GlobalMiniPlayer(
    playbackState: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (playbackState.status == PlaybackStatus.IDLE || playbackState.status == PlaybackStatus.ERROR) {
        return
    }

    val isPlaying = playbackState.status == PlaybackStatus.PLAYING
    val isBuffering = playbackState.status == PlaybackStatus.BUFFERING
    val title = playbackState.bookTitle.ifBlank {
        playbackState.chapterTitle.ifBlank { stringResource(R.string.mini_player_title) }
    }
    val subtitle = playbackState.currentSentenceText.ifBlank {
        playbackState.chapterTitle.ifBlank { stringResource(R.string.mini_player_waiting_for_text) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = stringResource(R.string.mini_player_open)
            }
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = !isBuffering,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.mini_player_previous)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onTogglePlayPause,
                enabled = !isBuffering,
                modifier = Modifier.size(44.dp)
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.mini_player_next)
                )
            }
        }
    }
}
