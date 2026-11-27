package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BookEntity
import com.example.ui.screens.formatBytes
import com.example.ui.theme.EditorialDisplayFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookCoverArtwork(
    book: BookEntity,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val foilColor = LocalEcouteAtelierColors.current.warmGoldAccent
    val coverShape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp)

    val customBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = book.coverStyleId,
        key2 = book.coverImagePath
    ) {
        value = if (book.coverStyleId == "custom_image" && !book.coverImagePath.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(book.coverImagePath)
                    if (file.exists() && file.isFile) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else null
                }.getOrNull()
            }
        } else null
    }

    val drawableRes: Int? = when (book.coverStyleId) {
        "atelier_classic" -> R.drawable.img_cover_atelier_classic
        "audio_waves" -> R.drawable.img_cover_audio_waves
        "botanical_parchment" -> R.drawable.img_cover_botanical_parchment
        else -> if (book.mediaKind == "AUDIO" && book.coverStyleId == "auto") {
            R.drawable.img_cover_audio_waves
        } else null
    }

    Box(
        modifier = modifier
            .clip(coverShape)
            .border(
                width = 1.dp,
                color = foilColor.copy(alpha = 0.42f),
                shape = coverShape
            )
    ) {
        when {
            customBitmap != null -> {
                Image(
                    bitmap = customBitmap!!,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            drawableRes != null -> {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x20000000), Color(0x880B1320))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = book.title.trim().firstOrNull()?.uppercase() ?: "É",
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontFamily = EditorialDisplayFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = book.sourceLanguage.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = foilColor
                    )
                }
            }
            else -> {
                val isFrench = book.sourceLanguage.equals("fr", ignoreCase = true)
                val coverPalette = when {
                    book.mediaKind == "AUDIO" -> Color(0xFF1D3557) to Color(0xFF12233B)
                    isFrench -> Color(0xFF8C2524) to Color(0xFF6B1A19)
                    else -> Color(0xFF215244) to Color(0xFF16382E)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colors = listOf(coverPalette.first, coverPalette.second)))
                ) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(Color.Black.copy(alpha = 0.24f))
                            .align(Alignment.CenterStart)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 11.dp, end = 8.dp, top = 9.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = book.author.take(18),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFEBD9C5).copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = book.title,
                                style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                                    .copy(fontFamily = EditorialDisplayFamily),
                                color = Color(0xFFFDF8F0),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = book.sourceLanguage.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = foilColor.copy(alpha = 0.90f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (book.mediaKind == "AUDIO") {
            Surface(
                color = Color(0xCC0D1829),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = stringResource(R.string.library_badge_audio),
                        tint = foilColor,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCoverSheet(
    book: BookEntity,
    onSelectTemplate: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Triple("auto", stringResource(R.string.cover_style_auto), null),
        Triple("atelier_classic", stringResource(R.string.cover_style_atelier), R.drawable.img_cover_atelier_classic),
        Triple("audio_waves", stringResource(R.string.cover_style_waves), R.drawable.img_cover_audio_waves),
        Triple("botanical_parchment", stringResource(R.string.cover_style_botanical), R.drawable.img_cover_botanical_parchment)
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.cover_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                options.forEach { (styleId, label, resId) ->
                    val selected = book.coverStyleId == styleId
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.secondary else LocalEcouteAtelierColors.current.cardBorder
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onSelectTemplate(styleId)
                                onDismiss()
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 48.dp, height = 64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                if (resId != null) {
                                    Image(
                                        painter = painterResource(id = resId),
                                        contentDescription = label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = book.title.firstOrNull()?.uppercase() ?: "É",
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialDisplayFamily),
                                            color = Color.White
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = {
                    onDismiss()
                    onPickPhoto()
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cover_pick_device_photo))
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
fun BookRenameDialog(
    book: BookEntity,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleText by remember(book.id) { mutableStateOf(book.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title)) },
        text = {
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                label = { Text(stringResource(R.string.library_rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (titleText.trim().isNotEmpty()) {
                        onConfirm(titleText.trim())
                    }
                },
                enabled = titleText.trim().isNotEmpty()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun BookInfoDialog(
    book: BookEntity,
    progress: Float,
    isTranslated: Boolean,
    onDismiss: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("العنوان", book.title)
                InfoRow("المؤلف / المصدر", book.author)
                InfoRow("النوع", "${book.mediaKind} • ${book.sourceType}")
                InfoRow("اللغة", if (book.sourceLanguage == "en") "الإنجليزية (EN)" else "الفرنسية (FR)")
                InfoRow("المحتوى", "${book.totalChapters} فصول • ${book.totalSentences} جملة")
                InfoRow("نسبة الإنجاز", "${(progress * 100).toInt()}%" + if (book.isCompleted) " (مكتمل)" else "")
                if (book.fileSizeBytes > 0L) {
                    InfoRow("حجم الملف", formatBytes(book.fileSizeBytes))
                }
                if (book.audioDurationMs > 0L) {
                    val mins = book.audioDurationMs / 60_000L
                    val secs = (book.audioDurationMs % 60_000L) / 1000L
                    InfoRow("المدة الصوتية", String.format(Locale.US, "%d:%02d", mins, secs))
                }
                InfoRow("الترجمة العربية", if (isTranslated) "متوفرة محلياً" else "غير مترجم بعد")
                InfoRow("تاريخ الإضافة", dateFmt.format(Date(book.addedAt)))
                if (!book.sourceContentHash.isNullOrBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        text = "SHA-256: ${book.sourceContentHash.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DeleteBookConfirmDialog(
    book: BookEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_book_title)) },
        text = { Text(stringResource(R.string.confirm_delete_book_message, book.title)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
