package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.BookEntity
import com.example.data.RecentActivityEntity
import com.example.runtime.LocalTaskEntry
import com.example.runtime.LocalTaskStatus
import com.example.ui.components.AtelierCard
import com.example.ui.components.BookCoverArtwork
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.viewmodel.HomeDashboardUiState

@Composable
fun HomeScreen(
    state: HomeDashboardUiState,
    onOpenBook: (String) -> Unit,
    onViewAllLibrary: () -> Unit,
    onOpenActivity: (RecentActivityEntity) -> Unit,
    onOpenQuickAdd: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
    onClearFinishedTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 1. Clean Arabic Editorial Greeting
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_greeting_title),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.home_greeting_subtitle),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. Hero "Continue Reading" Charcoal Card with Warm Cream Framed Cover
            if (state.continueReadingBook != null) {
                item {
                    ContinueReadingMockupCard(
                        book = state.continueReadingBook,
                        progress = state.continueReadingProgress,
                        onContinue = { onOpenBook(state.continueReadingBook.id) }
                    )
                }
            }

            // 3. Live LocalTaskCoordinator Tasks (Only displayed when active/queued/completed tasks exist)
            if (state.activeTasks.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.home_tasks_section),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (state.activeTasks.any { it.isTerminal }) {
                                TextButton(onClick = onClearFinishedTasks) {
                                    Text(
                                        text = stringResource(R.string.home_tasks_clear_finished),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        state.activeTasks.forEach { task ->
                            CoordinatorTaskCard(
                                task = task,
                                onPause = { onPauseTask(task.id) },
                                onResume = { onResumeTask(task.id) },
                                onCancel = { onCancelTask(task.id) },
                                onRetry = { onRetryTask(task.id) },
                                onDismiss = { onDismissTask(task.id) }
                            )
                        }
                    }
                }
            }

            // 4. "مكتبتي" Horizontal Shelf (3 crisp portrait covers across phone width)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_shelf_section),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${stringResource(R.string.home_view_all)} \u2190",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onViewAllLibrary)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("home_view_all_library")
                        )
                    }

                    if (state.recentBooks.isNotEmpty()) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val visibleSlots = if (maxWidth >= 600.dp) 5 else 3
                            val gap = 12.dp
                            val computedWidth: Dp = ((maxWidth - (gap * (visibleSlots - 1))) / visibleSlots)
                                .coerceIn(100.dp, 138.dp)

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                items(state.recentBooks, key = { it.id }) { book ->
                                    HomeLibraryBookTile(
                                        book = book,
                                        cardWidth = computedWidth,
                                        onClick = { onOpenBook(book.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. "آخر الأنشطة" Flat Activity List
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_recent_section),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.home_view_all),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onViewAllLibrary)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }

                    if (state.recentActivities.isEmpty()) {
                        // Show latest imported/audio item or calm onboarding prompt matching the same flat row style
                        val starterAudioOrBook = state.recentBooks.firstOrNull { it.mediaKind == "AUDIO" }
                        RecentActivityFlatRow(
                            iconKind = if (starterAudioOrBook != null) "TRANSCRIPTION" else "READ",
                            title = starterAudioOrBook?.title ?: stringResource(R.string.add_option_audio_title),
                            subtitle = if (starterAudioOrBook != null) stringResource(R.string.home_audio_label)
                            else stringResource(R.string.home_recent_empty),
                            onClick = {
                                if (starterAudioOrBook != null) onOpenBook(starterAudioOrBook.id)
                                else onOpenQuickAdd()
                            }
                        )
                    } else {
                        state.recentActivities.take(5).forEach { act ->
                            RecentActivityFlatRow(
                                iconKind = act.kind,
                                title = act.title,
                                subtitle = act.subtitle,
                                onClick = { onOpenActivity(act) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingMockupCard(
    book: BookEntity,
    progress: Float,
    onContinue: () -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)
    val pctInt = (clamped * 100).toInt()
    val atelier = LocalEcouteAtelierColors.current

    Surface(
        onClick = onContinue,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, atelier.cardBorder),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_continue_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Soft warm parchment mat framing the portrait book cover (matches reference design)
            Surface(
                color = if (atelier.isDark) Color(0xFFE4DCCB) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(width = 96.dp, height = 132.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BookCoverArtwork(
                        book = book,
                        compact = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_continue_section),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = stringResource(R.string.home_continue_sublabel),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                LinearProgressIndicator(
                    progress = { clamped.coerceAtLeast(0.04f) },
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = stringResource(R.string.home_percent_complete, pctInt),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLibraryBookTile(
    book: BookEntity,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("home_shelf_${book.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BookCoverArtwork(
            book = book,
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(14.dp))
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (book.mediaKind == "AUDIO") {
                    stringResource(R.string.home_audio_label)
                } else {
                    stringResource(R.string.home_ebook_label)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentActivityFlatRow(
    iconKind: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val icon = when (iconKind) {
        "TRANSCRIPTION" -> Icons.Rounded.GraphicEq
        "TRANSLATION" -> Icons.Outlined.Translate
        "MODEL" -> Icons.Outlined.CloudDownload
        else -> Icons.AutoMirrored.Outlined.MenuBook
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(22.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun CoordinatorTaskCard(
    task: LocalTaskEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AtelierCard(
        borderColor = if (task.isActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
        else LocalEcouteAtelierColors.current.cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("coordinator_task_${task.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonospaceMetricPill(text = task.type.labelAr)
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = task.errorMessage ?: task.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!task.errorMessage.isNullOrBlank()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        LocalTaskStatus.RUNNING -> if (task.canPause) {
                            IconButton(onClick = onPause, modifier = Modifier.minimumInteractiveComponentSize()) {
                                Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.models_pause_button))
                            }
                        }
                        LocalTaskStatus.PAUSED -> IconButton(onClick = onResume, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.models_resume_button))
                        }
                        LocalTaskStatus.FAILED -> IconButton(onClick = onRetry, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.models_retry_button))
                        }
                        else -> Unit
                    }

                    if (task.canCancel && (task.isActive || task.status == LocalTaskStatus.PAUSED)) {
                        IconButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Cancel, contentDescription = stringResource(R.string.common_cancel))
                        }
                    } else if (task.isTerminal) {
                        IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss_banner))
                        }
                    }
                }
            }

            if (task.isActive || task.status == LocalTaskStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }
    }
}
