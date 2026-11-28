package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddModalSheet(
    onPickDocument: () -> Unit,
    onPickAudio: () -> Unit,
    onOpenDirectText: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.add_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.add_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            QuickAddOptionCard(
                icon = Icons.Rounded.UploadFile,
                title = stringResource(R.string.add_option_document_title),
                subtitle = stringResource(R.string.add_option_document_desc),
                tag = "add_option_document",
                onClick = {
                    onDismiss()
                    onPickDocument()
                }
            )
            QuickAddOptionCard(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.add_option_audio_title),
                subtitle = stringResource(R.string.add_option_audio_desc),
                tag = "add_option_audio",
                onClick = {
                    onDismiss()
                    onPickAudio()
                }
            )
            QuickAddOptionCard(
                icon = Icons.Rounded.EditNote,
                title = stringResource(R.string.add_option_text_title),
                subtitle = stringResource(R.string.add_option_text_desc),
                tag = "add_option_text",
                onClick = {
                    onDismiss()
                    onOpenDirectText()
                }
            )
            SupportedFormatsLegendCard()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickAddOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit
) {
    AtelierCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DirectTextInputDialog(
    onSave: (title: String, body: String, languageCode: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var selectedLang by remember { mutableStateOf("fr") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.direct_text_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.direct_text_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedLang == "fr",
                        onClick = { selectedLang = "fr" },
                        label = { Text("الفرنسية (FR)") }
                    )
                    FilterChip(
                        selected = selectedLang == "en",
                        onClick = { selectedLang = "en" },
                        label = { Text("الإنجليزية (EN)") }
                    )
                    FilterChip(
                        selected = selectedLang == "ar",
                        onClick = { selectedLang = "ar" },
                        label = { Text("العربية (AR)") }
                    )
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.direct_text_body_label)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("direct_text_body_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), body.trim(), selectedLang) },
                enabled = body.trim().isNotEmpty(),
                modifier = Modifier.testTag("direct_text_save_button")
            ) {
                Text(stringResource(R.string.direct_text_save))
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
fun AudioTranscriptionLanguageDialog(
    onSelectLanguage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_import_lang_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.audio_import_lang_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onSelectLanguage("fr") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                ) {
                    Text("الفرنسية (FR)")
                }
                Button(
                    onClick = { onSelectLanguage("en") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                ) {
                    Text("الإنجليزية (EN)")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun AsrModelMissingDialog(
    onGoToModels: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_asr_missing_title)) },
        text = { Text(stringResource(R.string.audio_asr_missing_desc)) },
        confirmButton = {
            Button(onClick = onGoToModels) {
                Text(stringResource(R.string.audio_asr_go_models))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
