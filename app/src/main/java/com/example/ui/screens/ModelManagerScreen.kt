package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.ModelCompatibilityStatus
import com.example.domain.ModelInstallStatus
import com.example.domain.StorageBreakdown
import com.example.models.ModelUiEntry
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
    translationState: TranslationManagerUiState,
    actionMessage: String?,
    onStartOrResume: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onActivate: (ModelUiEntry) -> Unit,
    onDelete: (String) -> Unit,
    onDownloadTranslationPack: (TranslationDirection) -> Unit,
    onDeleteTranslationPack: (TranslationDirection) -> Unit,
    onDismissMessage: () -> Unit,
    onRefreshStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val startDownloadWithPermissionCheck: (String) -> Unit = { modelId ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onStartOrResume(modelId)
    }

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
                            OutlinedButton(onClick = onDismissMessage) { Text("حسناً") }
                        }
                    }
                }
            }

            item {
                EditorialSectionHeader(
                    titleAr = "كتالوج النماذج الصوتية",
                    folioTag = "Modèles TTS",
                    countBadge = "${models.size}"
                )
            }

            items(models, key = { it.spec.id }) { entry ->
                ModelCatalogCard(
                    entry = entry,
                    onDownloadOrResume = { startDownloadWithPermissionCheck(entry.spec.id) },
                    onPause = { onPause(entry.spec.id) },
                    onCancel = { onCancel(entry.spec.id) },
                    onActivate = { onActivate(entry) },
                    onDelete = { onDelete(entry.spec.id) }
                )
            }

            item {
                EditorialSectionHeader(
                    titleAr = "حزم الترجمة المحلية المشتركة",
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
                                    Text(if (pack.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..." else "تنزيل الحزمة")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onDeleteTranslationPack(dir) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("حذف الحزمة")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
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
                    Text("تفصيل التخزين والمساحة الحرة", style = MaterialTheme.typography.titleSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonospaceMetricPill(
                        text = "FREE ${formatBytes(storage.freeDeviceBytes)}",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث المساحة")
                    }
                }
            }

            // Segmented bar showing relative footprint inside app storage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
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

            Text(
                "حجم APK: ${formatBytes(storage.apkBytes)} • مكتبات التشغيل الأصلية (.so): ${formatBytes(storage.nativeLibsEstimateBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StorageLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCatalogCard(
    entry: ModelUiEntry,
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
                        "${spec.engineType.displayName} • الصوت: ${spec.voiceId} (${spec.language})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (spec.isPrimaryCandidate) {
                    AssistChip(onClick = {}, label = { Text("مرشح رئيسي") })
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MonospaceMetricPill(
                    text = if (spec.supportedLanguageCodes.size > 1) "FR • EN" else "FR",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.secondary
                )
                MonospaceMetricPill(text = formatBytes(spec.expectedSizeBytes))
                MonospaceMetricPill(text = spec.format)
                MonospaceMetricPill(text = "SHA ${spec.sha256.take(8)}")
                if (!spec.supportsGpuDelegate) {
                    MonospaceMetricPill(text = "CPU ONLY")
                }
                if (spec.supportsWordTimestamps) {
                    MonospaceMetricPill(
                        text = "WORD SYNC",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Surface(
                color = when (compat.status) {
                    ModelCompatibilityStatus.COMPATIBLE -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    ModelCompatibilityStatus.WARNING_LOW_RAM -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = compat.summaryAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (compat.status) {
                            ModelCompatibilityStatus.COMPATIBLE -> MaterialTheme.colorScheme.onTertiaryContainer
                            ModelCompatibilityStatus.WARNING_LOW_RAM -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    if (!compat.detailsAr.isNullOrBlank()) {
                        Text(
                            text = compat.detailsAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (compat.isSupported) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Text(spec.voiceDescriptionAr, style = MaterialTheme.typography.bodySmall)

            Text(
                "الإصدار: ${spec.version} • الترخيص: ${spec.licenseName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                Text(
                    text = when (entry.status) {
                        ModelInstallStatus.VERIFYING -> "جاري التحقق من SHA-256 واختبار التحميل الفعلي..."
                        ModelInstallStatus.PAUSED -> "متوقف مؤقتاً: ${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
                        else -> "جاري التنزيل: ${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
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

            if (entry.status == ModelInstallStatus.INSTALLED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Text(
                        "مثبّت • SHA-256: ${if (entry.verifiedSha256) "✓" else "✗"} • اختبار التحميل: ${if (entry.verifiedLoadTest) "✓" else "✗"}",
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
                                    !compat.isSupported -> "غير متوافق مع الجهاز"
                                    entry.status == ModelInstallStatus.NOT_INSTALLED -> "تنزيل"
                                    else -> "إعادة المحاولة"
                                }
                            )
                        }
                    }
                    ModelInstallStatus.DOWNLOADING -> {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إيقاف مؤقت")
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إلغاء")
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
                            Text("استكمال")
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Text("إلغاء")
                        }
                    }
                    ModelInstallStatus.VERIFYING -> {
                        Text("جاري الفحص...", style = MaterialTheme.typography.labelMedium)
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
                                Text("نشط حالياً")
                            }
                        } else {
                            Button(
                                onClick = onActivate,
                                enabled = compat.isSupported,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("activate_model_${spec.id}")
                            ) {
                                Text("تفعيل النموذج")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onDelete,
                            enabled = !entry.isActive,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_model_${spec.id}")
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "حذف النموذج",
                                tint = if (entry.isActive) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
