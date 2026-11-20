package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.ModelInstallStatus
import com.example.domain.StorageBreakdown
import com.example.models.ModelUiEntry
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus

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
                .fillMaxSize()
                .widthIn(max = 760.dp),
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
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = onDismissMessage) { Text("حسناً") }
                        }
                    }
                }
            }

            item {
                Text("كتالوج النماذج الصوتية الفرنسية", style = MaterialTheme.typography.titleMedium)
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
                Text(
                    "حزم الترجمة المحلية المشتركة (المرحلة 1: القارئ + مترجم الملفات)",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(TranslationDirection.entries, key = { it.name }) { dir ->
                val pack = translationState.stateFor(dir)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(dir.displayNameAr, style = MaterialTheme.typography.titleSmall)
                                    Text(pack.activeRuntimeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            AssistChip(onClick = {}, label = { Text(dir.shortBadge) })
                        }
                        Text(pack.statusMessage, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pack.status != TranslationPackStatus.READY) {
                                Button(
                                    onClick = { onDownloadTranslationPack(dir) },
                                    enabled = pack.status != TranslationPackStatus.DOWNLOADING
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pack.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..." else "تنزيل الحزمة")
                                }
                            } else {
                                OutlinedButton(onClick = { onDeleteTranslationPack(dir) }) {
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

@Composable
private fun StorageSummaryCard(storage: StorageBreakdown, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("تفصيل التخزين والمساحة الحرة", style = MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "تحديث المساحة")
                }
            }
            Text(
                "المساحة الحرة بالجهاز: ${formatBytes(storage.freeDeviceBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "النماذج المثبتة (filesDir): ${formatBytes(storage.installedModelsBytes)} • تنزيلات مؤقتة: ${formatBytes(storage.tempDownloadsBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "الكاش الصوتي: ${formatBytes(storage.audioCacheBytes)} • عينات WAV المحفوظة: ${formatBytes(storage.savedSamplesBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "حجم APK: ${formatBytes(storage.apkBytes)} • مكتبات التشغيل الأصلية (.so): ${formatBytes(storage.nativeLibsEstimateBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (entry.isActive) 3.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${spec.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Text(spec.voiceDescriptionAr, style = MaterialTheme.typography.bodySmall)

            Text(
                "الحجم: ${formatBytes(spec.expectedSizeBytes)} • الصيغة: ${spec.format} • الإصدار: ${spec.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "الترخيص: ${spec.licenseName} • SHA-256: ${spec.sha256.take(14)}...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (entry.status == ModelInstallStatus.DOWNLOADING || entry.status == ModelInstallStatus.PAUSED || entry.status == ModelInstallStatus.VERIFYING) {
                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = when (entry.status) {
                        ModelInstallStatus.VERIFYING -> "جاري التحقق من SHA-256 واختبار التحميل الفعلي..."
                        ModelInstallStatus.PAUSED -> "متوقف مؤقتاً: ${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
                        else -> "جاري التنزيل: ${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
                    },
                    style = MaterialTheme.typography.labelSmall
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
                        "مثبّت في الذاكرة الدائمة • SHA-256: ✓ • اختبار التحميل: ✓",
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
                            Text(if (entry.status == ModelInstallStatus.NOT_INSTALLED) "تنزيل" else "إعادة المحاولة")
                        }
                    }
                    ModelInstallStatus.DOWNLOADING -> {
                        FilledTonalButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إيقاف مؤقت")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إلغاء")
                        }
                    }
                    ModelInstallStatus.PAUSED -> {
                        Button(onClick = onDownloadOrResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("استكمال")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Text("إلغاء")
                        }
                    }
                    ModelInstallStatus.VERIFYING -> {
                        Text("جاري الفحص...", style = MaterialTheme.typography.labelMedium)
                    }
                    ModelInstallStatus.INSTALLED -> {
                        if (entry.isActive) {
                            FilledTonalButton(onClick = onActivate) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("نشط حالياً")
                            }
                        } else {
                            Button(
                                onClick = onActivate,
                                modifier = Modifier.testTag("activate_model_${spec.id}")
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
