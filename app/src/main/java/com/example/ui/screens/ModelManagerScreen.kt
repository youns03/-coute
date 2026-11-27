package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelInstallStatus
import com.example.domain.ModelRole
import com.example.domain.StorageBreakdown
import com.example.models.ModelUiEntry
import com.example.runtime.MemoryGovernorSnapshot
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.components.AtelierCard
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily

@Composable
fun ModelManagerScreen(
    models: List<ModelUiEntry>,
    storage: StorageBreakdown,
    memorySnapshot: MemoryGovernorSnapshot,
    translationState: TranslationManagerUiState,
    actionMessage: String?,
    onStartOrResume: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onActivate: (ModelUiEntry) -> Unit,
    onDelete: (String) -> Unit,
    onDownloadTranslationPack: (TranslationDirection) -> Unit,
    onDeleteTranslationPack: (TranslationDirection) -> Unit,
    onReleaseIdleEngines: () -> Unit,
    onDismissMessage: () -> Unit,
    onRefreshStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    var pendingDeleteModel by remember { mutableStateOf<ModelUiEntry?>(null) }
    var pendingDeletePack by remember { mutableStateOf<TranslationDirection?>(null) }
    var showTechDetails by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val startDownloadWithPermissionCheck: (String) -> Unit = { modelId ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onStartOrResume(modelId)
    }

    val ttsModels = models.filter { it.spec.role == ModelRole.TTS }
    val asrModels = models.filter { it.spec.role == ModelRole.ASR }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StorageSummaryCard(storage = storage, onRefresh = onRefreshStorage)
            }

            if (actionMessage != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = actionMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = onDismissMessage) { Text(stringResource(R.string.common_ok)) }
                        }
                    }
                }
            }

            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        icon = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null) }
                    ) {
                        Text(stringResource(R.string.models_tab_tts), maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        modifier = Modifier.testTag("models_tab_asr")
                    ) {
                        Text(stringResource(R.string.models_tab_asr), maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        icon = { Icon(Icons.Rounded.Translate, contentDescription = null) }
                    ) {
                        Text(stringResource(R.string.models_tab_translation), maxLines = 1)
                    }
                }
            }

            when (selectedSection) {
                0 -> {
                    item {
                        EditorialSectionHeader(
                            titleAr = stringResource(R.string.models_catalog_title),
                            folioTag = "Lecture vocale",
                            countBadge = "${ttsModels.size}"
                        )
                    }
                    items(ttsModels, key = { it.spec.id }) { entry ->
                        ModelCatalogCard(
                            entry = entry,
                            showTechDetails = showTechDetails,
                            onDownloadOrResume = { startDownloadWithPermissionCheck(entry.spec.id) },
                            onPause = { onPause(entry.spec.id) },
                            onCancel = { onCancel(entry.spec.id) },
                            onActivate = { onActivate(entry) },
                            onDelete = { pendingDeleteModel = entry }
                        )
                    }
                }
                1 -> {
                    item {
                        EditorialSectionHeader(
                            titleAr = stringResource(R.string.models_tab_asr),
                            folioTag = "Transcription offline",
                            countBadge = "${asrModels.size}"
                        )
                    }
                    items(asrModels, key = { it.spec.id }) { entry ->
                        ModelCatalogCard(
                            entry = entry,
                            showTechDetails = showTechDetails,
                            onDownloadOrResume = { startDownloadWithPermissionCheck(entry.spec.id) },
                            onPause = { onPause(entry.spec.id) },
                            onCancel = { onCancel(entry.spec.id) },
                            onActivate = { onActivate(entry) },
                            onDelete = { pendingDeleteModel = entry }
                        )
                    }
                }
                else -> {
                    item {
                        EditorialSectionHeader(
                            titleAr = stringResource(R.string.models_translation_packs_title),
                            folioTag = "OPUS / ML Kit",
                            countBadge = "${TranslationDirection.entries.size}"
                        )
                    }
                    items(TranslationDirection.entries, key = { it.name }) { dir ->
                        val pack = translationState.stateFor(dir)
                        AtelierCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(dir.displayNameAr, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                pack.activeRuntimeLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    MonospaceMetricPill(text = dir.shortBadge)
                                }
                                Text(pack.statusMessage, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (pack.status != TranslationPackStatus.READY) {
                                        Button(
                                            onClick = { onDownloadTranslationPack(dir) },
                                            enabled = pack.status != TranslationPackStatus.DOWNLOADING,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (pack.status == TranslationPackStatus.DOWNLOADING) {
                                                    stringResource(R.string.reader_download_in_progress)
                                                } else {
                                                    stringResource(R.string.models_download_pack)
                                                }
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { pendingDeletePack = dir },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.models_delete_pack))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible Runtime Memory & Technical Details Drawer
            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTechDetails = !showTechDetails }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Text(stringResource(R.string.models_tech_details_toggle), style = MaterialTheme.typography.titleSmall)
                            }
                            Icon(
                                if (showTechDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null
                            )
                        }

                        AnimatedVisibility(visible = showTechDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider(color = LocalEcouteAtelierColors.current.cardBorder)
                                Text(
                                    text = "ذاكرة التطبيق (PSS): ${"%.1f".format(memorySnapshot.processPssMb)} MB • المتاح بالنظام: ${memorySnapshot.availableRamMb} MB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "محرك القراءة محمل: ${if (memorySnapshot.isTtsEngineLoaded) "نعم" else "لا"} • محرك التفريغ محمل: ${if (memorySnapshot.isAsrEngineLoaded) "نعم" else "لا"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!memorySnapshot.lastCleanupReason.isNullOrBlank()) {
                                    Text(
                                        text = memorySnapshot.lastCleanupReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Text(
                                    text = "حجم APK: ${formatBytes(storage.apkBytes)} • المكتبات الأصلية: ${formatBytes(storage.nativeLibsEstimateBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = onReleaseIdleEngines,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.models_unload_idle_ram))
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(84.dp)) }
        }
    }

    pendingDeleteModel?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModel = null },
            title = { Text(stringResource(R.string.confirm_delete_model_title)) },
            text = { Text(stringResource(R.string.confirm_delete_model_message, target.spec.displayName)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteModel = null
                        onDelete(target.spec.id)
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
                TextButton(onClick = { pendingDeleteModel = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSummaryCard(storage: StorageBreakdown, onRefresh: () -> Unit) {
    val modelsB = storage.installedModelsBytes.coerceAtLeast(0L)
    val tempB = storage.tempDownloadsBytes.coerceAtLeast(0L)
    val cacheB = storage.audioCacheBytes.coerceAtLeast(0L)
    val wavB = storage.savedSamplesBytes.coerceAtLeast(0L)
    val appUsed = (modelsB + tempB + cacheB + wavB).coerceAtLeast(1L)

    AtelierCard(
        elevation = 2.dp,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.models_storage_title), style = MaterialTheme.typography.titleSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonospaceMetricPill(
                        text = "FREE ${formatBytes(storage.freeDeviceBytes)}",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    IconButton(onClick = onRefresh, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.models_refresh_storage_cd))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val segments = listOf(
                    modelsB to MaterialTheme.colorScheme.primary,
                    tempB to MaterialTheme.colorScheme.secondary,
                    cacheB to MaterialTheme.colorScheme.tertiary,
                    wavB to LocalEcouteAtelierColors.current.warmGoldAccent
                )
                segments.forEach { (bytes, color) ->
                    if (bytes > 0L) {
                        Box(
                            modifier = Modifier
                                .weight((bytes.toFloat() / appUsed.toFloat()).coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StorageLegendDot("النماذج: ${formatBytes(modelsB)}", MaterialTheme.colorScheme.primary)
                StorageLegendDot("مؤقت: ${formatBytes(tempB)}", MaterialTheme.colorScheme.secondary)
                StorageLegendDot("كاش الصوت: ${formatBytes(cacheB)}", MaterialTheme.colorScheme.tertiary)
                StorageLegendDot("WAV: ${formatBytes(wavB)}", LocalEcouteAtelierColors.current.warmGoldAccent)
            }
        }
    }
}

@Composable
private fun StorageLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCatalogCard(
    entry: ModelUiEntry,
    showTechDetails: Boolean,
    onDownloadOrResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = entry.spec
    val compat = entry.compatibility
    val atelier = LocalEcouteAtelierColors.current

    AtelierCard(
        borderColor = if (entry.isActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f) else atelier.cardBorder,
        elevation = if (entry.isActive) 3.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${spec.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${spec.language} • ${formatBytes(spec.expectedSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.isActive) {
                        MonospaceMetricPill(
                            text = stringResource(R.string.models_active_button),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    } else if (spec.isPrimaryCandidate) {
                        MonospaceMetricPill(
                            text = stringResource(R.string.models_primary_candidate),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Text(spec.voiceDescriptionAr, style = MaterialTheme.typography.bodySmall)

            if (!compat.isSupported || compat.status == ModelCompatibilityStatus.WARNING_LOW_RAM) {
                Surface(
                    color = if (compat.isSupported) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = compat.detailsAr ?: compat.summaryAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (compat.isSupported) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (showTechDetails) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonospaceMetricPill(text = spec.format)
                    MonospaceMetricPill(text = "SHA ${spec.sha256.take(8)}")
                    MonospaceMetricPill(text = if (spec.supportsGpuDelegate) "GPU / CPU" else "CPU")
                    if (spec.supportsWordTimestamps) {
                        MonospaceMetricPill(text = "WORD SYNC")
                    }
                }
                Text(
                    "المحرك: ${spec.engineType.displayName} • الإصدار: ${spec.version} • الترخيص: ${spec.licenseName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (entry.status == ModelInstallStatus.DOWNLOADING || entry.status == ModelInstallStatus.PAUSED || entry.status == ModelInstallStatus.VERIFYING) {
                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                val remainingStr = formatBytes(entry.remainingBytes)
                Text(
                    text = when (entry.status) {
                        ModelInstallStatus.VERIFYING -> "جاري التحقق من سلامة الملفات محلياً..."
                        ModelInstallStatus.PAUSED -> "متوقف مؤقتاً: ${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)} • " +
                            stringResource(R.string.models_remaining_bytes, remainingStr)
                        else -> "جاري التنزيل: ${(entry.progressFraction * 100).toInt()}% • " +
                            stringResource(R.string.models_remaining_bytes, remainingStr)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = StudioMonoFamily)
                )
            }

            if (!entry.lastError.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(entry.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            if (entry.status == ModelInstallStatus.INSTALLED && showTechDetails) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Text(
                        "مثبّت • SHA-256: ${if (entry.verifiedSha256) "✓" else "✗"} • التحميل: ${if (entry.verifiedLoadTest) "✓" else "✗"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (entry.status) {
                    ModelInstallStatus.NOT_INSTALLED, ModelInstallStatus.FAILED, ModelInstallStatus.CORRUPTED -> {
                        Button(
                            onClick = onDownloadOrResume,
                            enabled = compat.isSupported,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("download_model_${spec.id}")
                        ) {
                            Icon(
                                if (entry.status == ModelInstallStatus.NOT_INSTALLED) Icons.Rounded.Download else Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    !compat.isSupported -> stringResource(R.string.models_incompatible_button)
                                    entry.status == ModelInstallStatus.NOT_INSTALLED -> stringResource(R.string.models_download_button)
                                    else -> stringResource(R.string.models_retry_button)
                                }
                            )
                        }
                    }
                    ModelInstallStatus.DOWNLOADING -> {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.models_pause_button))
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                    ModelInstallStatus.PAUSED -> {
                        Button(
                            onClick = onDownloadOrResume,
                            enabled = compat.isSupported,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.models_resume_button))
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                    ModelInstallStatus.VERIFYING -> {
                        Text(stringResource(R.string.models_verifying_button), style = MaterialTheme.typography.labelMedium)
                    }
                    ModelInstallStatus.INSTALLED -> {
                        if (entry.isActive) {
                            FilledTonalButton(
                                onClick = onActivate,
                                enabled = compat.isSupported,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.models_active_button))
                            }
                        } else {
                            Button(
                                onClick = onActivate,
                                enabled = compat.isSupported,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("activate_model_${spec.id}")
                            ) {
                                Text(stringResource(R.string.models_activate_button))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        val canDelete = spec.role == ModelRole.ASR || !entry.isActive
                        IconButton(
                            onClick = onDelete,
                            enabled = canDelete,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_model_${spec.id}")
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.models_delete_model_cd),
                                tint = if (!canDelete) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        "${"%.2f".format(mb / 1024.0)} GB"
    } else {
        "${"%.1f".format(mb)} MB"
    }
}
