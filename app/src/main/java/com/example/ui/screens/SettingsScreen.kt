package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.UserSettings
import com.example.domain.AcceleratorMode
import com.example.domain.SpeedControlMode
import com.example.models.ModelCatalog
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationModelCatalog
import com.example.translation.TranslationPackStatus
import com.example.tts.DeviceHardwareInfo
import com.example.ui.components.AtelierCard
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily

@Composable
fun SettingsScreen(
    settings: UserSettings,
    translationState: TranslationManagerUiState,
    hardwareInfo: DeviceHardwareInfo,
    onAcceleratorSelected: (AcceleratorMode) -> Unit,
    onSpeedModeSelected: (SpeedControlMode) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onDownloadTranslationPack: (TranslationDirection) -> Unit,
    onDeleteTranslationPack: (TranslationDirection) -> Unit,
    onClearAudioCache: () -> Unit,
    onOpenReader: () -> Unit = {},
    onOpenTranslator: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenVocab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    var pendingDeletePack by remember { mutableStateOf<TranslationDirection?>(null) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_studio_tools_title), style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenModels,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_open_models")
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.nav_models), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onOpenTranslator,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_open_translator")
                            ) {
                                Icon(Icons.Rounded.Translate, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.nav_translator), maxLines = 1)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenVocab,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_open_vocab")
                            ) {
                                Icon(Icons.Rounded.Bookmarks, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.nav_vocab), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onOpenReader,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_open_reader")
                            ) {
                                Icon(Icons.Rounded.AutoStories, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.nav_reader), maxLines = 1)
                            }
                        }
                    }
                }
            }

            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_hardware_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.settings_kokoro_accelerator), style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AcceleratorMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.acceleratorMode == mode,
                                    onClick = { onAcceleratorSelected(mode) },
                                    label = { Text(mode.labelAr) }
                                )
                            }
                        }

                        HorizontalDivider(color = atelier.cardBorder.copy(alpha = 0.6f))

                        Text(stringResource(R.string.settings_speed_mode), style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SpeedControlMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.speedControlMode == mode,
                                    onClick = { onSpeedModeSelected(mode) },
                                    label = { Text(mode.labelAr) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_translation_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            translationState.qualityDisclaimer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TranslationDirection.entries.forEach { dir ->
                            val packState = translationState.stateFor(dir)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, atelier.cardBorder),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${dir.displayNameAr} (${dir.shortBadge})",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        MonospaceMetricPill(text = dir.shortBadge)
                                    }
                                    Text(packState.statusMessage, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (packState.status != TranslationPackStatus.READY) {
                                            Button(
                                                onClick = { onDownloadTranslationPack(dir) },
                                                enabled = packState.status != TranslationPackStatus.DOWNLOADING,
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("download_translation_${dir.source.code}")
                                            ) {
                                                Icon(Icons.Rounded.Download, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    if (packState.status == TranslationPackStatus.DOWNLOADING) {
                                                        stringResource(R.string.reader_download_in_progress)
                                                    } else {
                                                        stringResource(R.string.reader_download_translation_pack, dir.shortBadge)
                                                    }
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { pendingDeletePack = dir },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.models_delete_pack))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = atelier.cardBorder)
                        Text(
                            stringResource(R.string.settings_opus_reference_note),
                            style = MaterialTheme.typography.labelMedium
                        )
                        TranslationModelCatalog.opusReferenceMatrix.forEach { spec ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "• ${spec.directionLabelAr} (${spec.modelId})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (spec.includedInPhase1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                MonospaceMetricPill(
                                    text = "${spec.approxSizeMb} MB",
                                    containerColor = if (spec.includedInPhase1) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (spec.includedInPhase1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_reading_cache_title), style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_night_mode), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = settings.nightMode, onCheckedChange = onNightModeChange)
                        }
                        Text(
                            stringResource(R.string.settings_font_size, settings.readerFontSizeSp.toInt()),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(value = settings.readerFontSizeSp, onValueChange = onFontSizeChange, valueRange = 15f..30f)
                        Text(
                            stringResource(R.string.settings_line_height, "%.2f".format(settings.readerLineHeightMultiplier)),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = settings.readerLineHeightMultiplier,
                            onValueChange = onLineHeightChange,
                            valueRange = 1.3f..2.2f
                        )

                        // Live Bilingual Typography Preview
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                            border = BorderStroke(1.dp, atelier.cardBorder),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "La lecture éclaire la pensée.",
                                        fontFamily = FrenchSerifFamily,
                                        fontSize = settings.readerFontSizeSp.sp,
                                        lineHeight = (settings.readerFontSizeSp * settings.readerLineHeightMultiplier).sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                HorizontalDivider(color = atelier.cardBorder.copy(alpha = 0.6f))
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text(
                                        text = "القراءة تُنير الفكر.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ArabicSansFamily),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onClearAudioCache,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_clear_audio_cache))
                        }
                    }
                }
            }

            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_device_info_title), style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HardwareSpecBox("الطراز", hardwareInfo.deviceModel, Modifier.weight(1f))
                            HardwareSpecBox("المعمارية", hardwareInfo.primaryAbi, Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HardwareSpecBox("الذاكرة", "${hardwareInfo.totalRamMb} MB", Modifier.weight(1f))
                            HardwareSpecBox(
                                "النظام",
                                "Android ${hardwareInfo.androidVersion} (${hardwareInfo.sdkInt})",
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    EditorialSectionHeader(
                        titleAr = stringResource(R.string.settings_licenses_title),
                        folioTag = "Licences",
                        countBadge = "${ModelCatalog.licenseLedger.size}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            items(ModelCatalog.licenseLedger) { rec ->
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                rec.componentName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            MonospaceMetricPill(text = rec.weightsOrCodeLicense)
                        }
                        Text("${rec.role} • المالك: ${rec.owner}", style = MaterialTheme.typography.bodySmall)
                        Text("ترخيص الصوت/البيانات: ${rec.voiceOrDataLicense}", style = MaterialTheme.typography.labelSmall)
                        Text("الاستخدام التجاري: ${rec.commercialTerms}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "الإسناد: ${rec.attributionRequirement}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    pendingDeletePack?.let { targetDir ->
        AlertDialog(
            onDismissRequest = { pendingDeletePack = null },
            title = { Text(stringResource(R.string.confirm_delete_translation_pack_title)) },
            text = { Text(stringResource(R.string.confirm_delete_translation_pack_message, targetDir.displayNameAr)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletePack = null
                        onDeleteTranslationPack(targetDir)
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
                TextButton(onClick = { pendingDeletePack = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun HardwareSpecBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, atelier.cardBorder),
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = StudioMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
