package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.importengine.LanguageDetector
import com.example.data.importengine.SupportedImportFormat
import com.example.ui.components.AtelierCard
import com.example.ui.components.BookCoverArtwork
import com.example.ui.components.BookCoverSheet
import com.example.ui.components.BookInfoDialog
import com.example.ui.components.BookRenameDialog
import com.example.ui.components.DeleteBookConfirmDialog
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.viewmodel.LibraryFilter
import com.example.ui.viewmodel.LibrarySortOrder
import com.example.ui.viewmodel.LibraryUiState
import com.example.ui.viewmodel.LibraryViewMode

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSearchChange: (String) -> Unit,
    onFilterSelected: (LibraryFilter) -> Unit,
    onSortSelected: (LibrarySortOrder) -> Unit,
    onToggleViewMode: () -> Unit,
    onImportUri: (Uri) -> Unit,
    onInspectImportUris: (List<Uri>) -> Unit = { uris -> uris.firstOrNull()?.let(onImportUri) },
    onOpenBook: (String) -> Unit,
    onRenameBook: (String, String) -> Unit,
    onUpdateCoverTemplate: (String, String) -> Unit,
    onUpdateCoverFromUri: (String, Uri) -> Unit,
    onDeleteBook: (String) -> Unit,
    onDismissBanner: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteBook by remember { mutableStateOf<BookEntity?>(null) }
    var pendingRenameBook by remember { mutableStateOf<BookEntity?>(null) }
    var pendingCoverBook by remember { mutableStateOf<BookEntity?>(null) }
    var pendingInfoBook by remember { mutableStateOf<BookEntity?>(null) }
    var photoTargetBookId by remember { mutableStateOf<String?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onInspectImportUris(uris)
    }

    val coverPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val bookId = photoTargetBookId
        photoTargetBookId = null
        if (uri != null && bookId != null) {
            onUpdateCoverFromUri(bookId, uri)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val gridColumns = if (maxWidth >= 640.dp) 3 else 2

        LazyColumn(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            focusedBorderColor = MaterialTheme.colorScheme.secondary,
                            unfocusedBorderColor = LocalEcouteAtelierColors.current.cardBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library_search_input")
                    )

                    Box {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, LocalEcouteAtelierColors.current.cardBorder),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("library_sort_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Sort,
                                    contentDescription = stringResource(R.string.library_sort_recent)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_sort_recent)) },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortSelected(LibrarySortOrder.RECENT)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_sort_added)) },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortSelected(LibrarySortOrder.ADDED)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_sort_name)) },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortSelected(LibrarySortOrder.NAME)
                                }
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, LocalEcouteAtelierColors.current.cardBorder),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        IconButton(
                            onClick = onToggleViewMode,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("library_view_toggle")
                        ) {
                            Icon(
                                if (state.viewMode == LibraryViewMode.GRID) Icons.AutoMirrored.Rounded.ViewList
                                else Icons.Rounded.GridView,
                                contentDescription = stringResource(
                                    if (state.viewMode == LibraryViewMode.GRID) R.string.library_view_list_cd
                                    else R.string.library_view_grid_cd
                                )
                            )
                        }
                    }
                }
            }

            item {
                val filters = listOf(
                    LibraryFilter.ALL to stringResource(R.string.library_filter_all),
                    LibraryFilter.BOOKS to stringResource(R.string.library_filter_books),
                    LibraryFilter.AUDIO to stringResource(R.string.library_filter_audio),
                    LibraryFilter.TRANSLATED to stringResource(R.string.library_filter_translated),
                    LibraryFilter.IN_PROGRESS to stringResource(R.string.library_filter_in_progress),
                    LibraryFilter.COMPLETED to stringResource(R.string.library_filter_completed)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("library_filter_${filter.name.lowercase()}")
                        )
                    }
                }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorialSectionHeader(
                        titleAr = stringResource(R.string.library_section_title),
                        folioTag = "Bibliothèque",
                        countBadge = "${state.books.size}",
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onOpenModels,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("hero_models_button")
                        ) {
                            Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.nav_models), style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = {
                                filePickerLauncher.launch(SupportedImportFormat.safPickerMimeTypes())
                            },
                            enabled = !state.isImporting,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("import_book_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.library_import_button), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
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
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_empty_library),
                                contentDescription = stringResource(R.string.library_empty_title),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 180.dp, height = 120.dp)
                                    .clip(MaterialTheme.shapes.medium)
                            )
                            Text(
                                stringResource(R.string.library_empty_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.library_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (state.viewMode == LibraryViewMode.GRID) {
                val rows = state.books.chunked(gridColumns)
                items(rows, key = { row -> row.joinToString("_") { it.id } }) { rowBooks ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowBooks.forEach { book ->
                            val progress = state.bookProgress[book.id] ?: 0f
                            val isTranslated = book.id in state.translatedBookIds
                            LibraryGridCard(
                                book = book,
                                progress = progress,
                                isTranslated = isTranslated,
                                onOpen = { onOpenBook(book.id) },
                                onRename = { pendingRenameBook = book },
                                onEditCover = { pendingCoverBook = book },
                                onInfo = { pendingInfoBook = book },
                                onDelete = { pendingDeleteBook = book },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(gridColumns - rowBooks.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(state.books, key = { it.id }) { book ->
                    val progress = state.bookProgress[book.id] ?: 0f
                    val isTranslated = book.id in state.translatedBookIds
                    LibraryListCard(
                        book = book,
                        progress = progress,
                        isTranslated = isTranslated,
                        onOpen = { onOpenBook(book.id) },
                        onRename = { pendingRenameBook = book },
                        onEditCover = { pendingCoverBook = book },
                        onInfo = { pendingInfoBook = book },
                        onDelete = { pendingDeleteBook = book }
                    )
                }
            }

            item { Spacer(Modifier.height(84.dp)) }
        }
    }

    pendingRenameBook?.let { target ->
        BookRenameDialog(
            book = target,
            onConfirm = { newTitle ->
                pendingRenameBook = null
                onRenameBook(target.id, newTitle)
            },
            onDismiss = { pendingRenameBook = null }
        )
    }

    pendingCoverBook?.let { target ->
        BookCoverSheet(
            book = target,
            onSelectTemplate = { styleId -> onUpdateCoverTemplate(target.id, styleId) },
            onPickPhoto = {
                photoTargetBookId = target.id
                coverPhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { pendingCoverBook = null }
        )
    }

    pendingInfoBook?.let { target ->
        BookInfoDialog(
            book = target,
            progress = state.bookProgress[target.id] ?: 0f,
            isTranslated = target.id in state.translatedBookIds,
            onDismiss = { pendingInfoBook = null }
        )
    }

    pendingDeleteBook?.let { target ->
        DeleteBookConfirmDialog(
            book = target,
            onConfirm = {
                pendingDeleteBook = null
                onDeleteBook(target.id)
            },
            onDismiss = { pendingDeleteBook = null }
        )
    }
}

@Composable
private fun LibraryGridCard(
    book: BookEntity,
    progress: Float,
    isTranslated: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEditCover: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    AtelierCard(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.testTag("book_card_${book.id}")
    ) {
        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                BookCoverArtwork(
                    book = book,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(146.dp)
                )
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    BookOverflowMenuButton(
                        book = book,
                        onOpen = onOpen,
                        onRename = onRename,
                        onEditCover = onEditCover,
                        onInfo = onInfo,
                        onDelete = onDelete
                    )
                }
            }

            val titleIsArabic = book.sourceLanguage.lowercase().startsWith("ar") || LanguageDetector.isArabicText(book.title)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (titleIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = if (titleIsArabic) ArabicSansFamily else FrenchSerifFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(40.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonospaceMetricPill(
                    text = if (book.mediaKind == "AUDIO") "AUDIO" else book.sourceType
                )
                if (isTranslated) {
                    MonospaceMetricPill(
                        text = stringResource(R.string.library_badge_translated),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                } else if (book.isCompleted) {
                    MonospaceMetricPill(
                        text = stringResource(R.string.library_badge_completed),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            LinearProgressIndicator(
                progress = { clamped },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(clamped * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${book.totalChapters} ف • ${book.totalSentences} ج",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LibraryListCard(
    book: BookEntity,
    progress: Float,
    isTranslated: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEditCover: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)
    AtelierCard(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book_card_${book.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                BookCoverArtwork(
                    book = book,
                    modifier = Modifier.size(width = 74.dp, height = 104.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MonospaceMetricPill(text = "${book.sourceLanguage.uppercase()} • ${book.sourceType}")
                            if (isTranslated) {
                                MonospaceMetricPill(
                                    text = stringResource(R.string.library_badge_translated),
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        BookOverflowMenuButton(
                            book = book,
                            onOpen = onOpen,
                            onRename = onRename,
                            onEditCover = onEditCover,
                            onInfo = onInfo,
                            onDelete = onDelete
                        )
                    }
                    val titleIsArabic = book.sourceLanguage.lowercase().startsWith("ar") || LanguageDetector.isArabicText(book.title)
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (titleIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = if (titleIsArabic) ArabicSansFamily else FrenchSerifFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = buildString {
                            append(book.author)
                            if (book.fileSizeBytes > 0L) {
                                append(" • ")
                                append(formatBytes(book.fileSizeBytes))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            LinearProgressIndicator(
                progress = { clamped },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(clamped * 100).toInt()}% • " +
                        stringResource(R.string.library_book_stats, book.totalChapters, book.totalSentences),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("continue_reading_${book.id}")
                ) {
                    Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (clamped > 0f) stringResource(R.string.library_continue_reading)
                        else stringResource(R.string.library_start_reading),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun BookOverflowMenuButton(
    book: BookEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEditCover: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("book_menu_${book.id}")
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.library_more_options_cd)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Rounded.AutoStories, contentDescription = null) },
                text = { Text(stringResource(R.string.library_menu_continue)) },
                onClick = {
                    expanded = false
                    onOpen()
                }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                text = { Text(stringResource(R.string.library_menu_rename)) },
                onClick = {
                    expanded = false
                    onRename()
                }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                text = { Text(stringResource(R.string.library_menu_cover)) },
                onClick = {
                    expanded = false
                    onEditCover()
                }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                text = { Text(stringResource(R.string.library_menu_info)) },
                onClick = {
                    expanded = false
                    onInfo()
                }
            )
            if (book.sourceType != "STARTER") {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.library_menu_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}
