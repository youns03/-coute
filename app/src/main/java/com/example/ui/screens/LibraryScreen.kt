package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BookEntity
import com.example.ui.components.AtelierCard
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.EditorialDisplayFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.viewmodel.LibraryUiState

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSearchChange: (String) -> Unit,
    onImportUri: (Uri) -> Unit,
    onOpenBook: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    onDismissBanner: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteBook by remember { mutableStateOf<BookEntity?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportUri(uri)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LibraryHeroBanner(
                    isImporting = state.isImporting,
                    onImportClick = {
                        filePickerLauncher.launch(
                            arrayOf("text/plain", "application/epub+zip", "application/octet-stream")
                        )
                    },
                    onModelsClick = onOpenModels
                )
            }

            if (state.statusBanner != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.statusBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissBanner,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss_banner))
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = LocalEcouteAtelierColors.current.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_input")
                )
            }

            item {
                EditorialSectionHeader(
                    titleAr = stringResource(R.string.library_section_title),
                    folioTag = "Bibliothèque",
                    countBadge = "${state.books.size}"
                )
            }

            if (state.books.isEmpty()) {
                item {
                    AtelierCard(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                stringResource(R.string.library_empty_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.library_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(state.books, key = { it.id }) { book ->
                    val bookProgress = state.bookProgress[book.id] ?: if (book.totalSentences > 0) {
                        ((book.currentSentenceIndex + 1).toFloat() / book.totalSentences.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    BookCard(
                        book = book,
                        progress = bookProgress,
                        onOpen = { onOpenBook(book.id) },
                        onDelete = { pendingDeleteBook = book }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    pendingDeleteBook?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBook = null },
            title = { Text(stringResource(R.string.confirm_delete_book_title)) },
            text = { Text(stringResource(R.string.confirm_delete_book_message, target.title)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteBook = null
                        onDeleteBook(target.id)
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
                TextButton(onClick = { pendingDeleteBook = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun LibraryHeroBanner(
    isImporting: Boolean,
    onImportClick: () -> Unit,
    onModelsClick: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    AtelierCard(
        borderColor = atelier.cardBorder,
        elevation = 3.dp,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.height(228.dp)) {
            Image(
                painter = painterResource(id = R.drawable.img_library_hero),
                contentDescription = stringResource(R.string.library_hero_content_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x880B1320),
                                Color(0xCC111F36),
                                Color(0xF212223C)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Surface(
                            color = Color(0x33FFFFFF),
                            border = BorderStroke(1.dp, Color(0x40E8B954)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8B954))
                                )
                                Text(
                                    text = "Écoute • Atelier Littéraire",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = EditorialDisplayFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFFDF4E3)
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.library_hero_headline),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.library_hero_subheadline),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFDCE3EE)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onImportClick,
                        enabled = !isImporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("import_book_button")
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondary
                            )
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.library_import_button))
                    }

                    FilledTonalButton(
                        onClick = onModelsClick,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("hero_models_button")
                    ) {
                        Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.library_models_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: BookEntity,
    progress: Float,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    AtelierCard(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book_card_${book.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                BookCover(book = book)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonospaceMetricPill(
                            text = "${book.sourceLanguage.uppercase()} • ${book.sourceType}",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (book.sourceType != "STARTER") {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.library_delete_book_cd),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FrenchSerifFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { clampedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonospaceMetricPill(
                            text = "${(clampedProgress * 100).toInt()}%",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.library_book_stats, book.totalChapters, book.totalSentences),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpen,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("continue_reading_${book.id}")
                    ) {
                        Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (book.currentChapterIndex > 0 || book.currentSentenceIndex > 0) {
                                stringResource(R.string.library_continue_reading)
                            } else {
                                stringResource(R.string.library_start_reading)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: BookEntity) {
    val isFrench = book.sourceLanguage.equals("fr", ignoreCase = true)
    val coverTop = if (isFrench) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val coverBottom = if (isFrench) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.82f)
    }
    val foilColor = LocalEcouteAtelierColors.current.warmGoldAccent

    Box(
        modifier = Modifier
            .size(width = 78.dp, height = 108.dp)
            .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp))
            .background(Brush.linearGradient(colors = listOf(coverTop, coverBottom)))
            .border(
                width = 1.dp,
                color = foilColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp)
            )
    ) {
        // Subtle bookbinding spine crease
        Box(
            modifier = Modifier
                .width(7.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.22f))
                .align(Alignment.CenterStart)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 11.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                .border(0.75.dp, foilColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = book.title.trim().firstOrNull()?.uppercase() ?: "É",
                    style = MaterialTheme.typography.headlineLarge.copy(fontFamily = EditorialDisplayFamily),
                    color = Color.White
                )
                Text(
                    text = book.sourceLanguage.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = foilColor.copy(alpha = 0.92f),
                    maxLines = 1
                )
            }
        }
    }
}
