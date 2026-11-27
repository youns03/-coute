package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.importengine.FileInspectionPreview
import com.example.data.importengine.ImportFormatCategory
import com.example.data.importengine.PdfExtractionMode
import com.example.data.importengine.SupportedImportFormat
import com.example.ui.screens.formatBytes
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily
import com.example.ui.viewmodel.DocumentImportQueueState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentImportQueueSheet(
    state: DocumentImportQueueState,
    title: String = stringResource(R.string.import_queue_title),
    confirmLabel: String = stringResource(R.string.import_queue_start),
    onSelectPdfMode: (PdfExtractionMode) -> Unit,
    onRemoveFile: (Uri) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelOrDismiss: () -> Unit
) {
    if (!state.isVisible) return

    val atelier = LocalEcouteAtelierColors.current
    val supportedCount = state.supportedFiles.size
    val unsupportedCount = state.inspectedFiles.size - supportedCount

    ModalBottomSheet(
        onDismissRequest = onCancelOrDismiss
    ) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.UploadFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (state.isInspecting) {
                                stringResource(R.string.import_queue_inspecting)
                            } else {
                                stringResource(R.string.import_queue_summary, supportedCount, unsupportedCount)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onCancelOrDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("import_queue_close_button")
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }

            if (state.isInspecting) {
                AtelierCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(
                            text = stringResource(R.string.import_queue_inspecting_detail),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.inspectedFiles, key = { it.uri.toString() }) { preview ->
                        InspectedFileRow(
                            preview = preview,
                            canRemove = !state.isImporting && state.inspectedFiles.size > 1,
                            onRemove = { onRemoveFile(preview.uri) }
                        )
                    }
                }

                if (state.hasValidPdf && !state.isImporting) {
                    HorizontalDivider(color = atelier.cardBorder)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.import_pdf_mode_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PdfExtractionMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.pdfMode == mode,
                                    onClick = { onSelectPdfMode(mode) },
                                    label = {
                                        Text(
                                            text = mode.labelAr,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag("pdf_mode_${mode.name.lowercase()}")
                                )
                            }
                        }
                    }
                }

                if (state.isImporting) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_progress_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.activeStageLabel.ifBlank { stringResource(R.string.import_queue_processing) },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                MonospaceMetricPill(
                                    text = "${(state.currentIndex + 1).coerceAtMost(state.totalCount.coerceAtLeast(1))} / ${state.totalCount.coerceAtLeast(1)}"
                                )
                            }
                            if (state.activeFileName.isNotBlank()) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = state.activeFileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { state.currentFileProgress.coerceIn(0f, 1f) },
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCancelOrDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("import_queue_cancel_button")
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirmImport,
                        enabled = !state.isImporting && supportedCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1.4f)
                            .minimumInteractiveComponentSize()
                            .testTag("import_queue_confirm_button")
                    ) {
                        Text(
                            if (state.isImporting) stringResource(R.string.import_queue_in_progress)
                            else "$confirmLabel ($supportedCount)"
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InspectedFileRow(
    preview: FileInspectionPreview,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val icon = when (preview.detectedFormat) {
        SupportedImportFormat.PDF -> Icons.Rounded.PictureAsPdf
        SupportedImportFormat.SRT, SupportedImportFormat.VTT -> Icons.Rounded.Subtitles
        else -> Icons.Rounded.Description
    }
    AtelierCard(
        containerColor = if (preview.isSupported) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
        borderColor = if (preview.isSupported) atelier.cardBorder else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inspected_file_item")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (preview.isSupported) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = preview.fileName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonospaceMetricPill(
                        text = preview.detectedFormat?.code ?: "UNSUPPORTED",
                        containerColor = if (preview.isSupported) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColor = if (preview.isSupported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    if (preview.fileSizeBytes > 0L) {
                        Text(
                            text = formatBytes(preview.fileSizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = StudioMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (preview.isPdfLikelyEncrypted) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.import_pdf_encrypted_cd),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (!preview.isSupported && !preview.unsupportedReason.isNullOrBlank()) {
                    Text(
                        text = preview.unsupportedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (preview.isPdfLikelyEncrypted) {
                    Text(
                        text = stringResource(R.string.import_pdf_encrypted_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (canRemove) {
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(stringResource(R.string.import_queue_remove_file), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Icon(
                    imageVector = if (preview.isSupported) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (preview.isSupported) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportedFormatsLegendCard(modifier: Modifier = Modifier) {
    val grouped = SupportedImportFormat.entries.groupBy { it.category }
    AtelierCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.import_supported_categories_title),
                style = MaterialTheme.typography.labelLarge
            )
            ImportFormatCategory.entries.forEach { category ->
                val formats = grouped[category].orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = category.labelAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        formats.forEach { fmt ->
                            MonospaceMetricPill(
                                text = fmt.code,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
