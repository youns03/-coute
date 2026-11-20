package com.example.ui.screens

import android.Manifest
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobEntity
import com.example.translation.ExportMode
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.FrenchSerifFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTranslatorScreen(
    selectedSourceLanguage: SourceLanguage,
    translationManagerState: TranslationManagerUiState,
    jobs: List<TranslationJobEntity>,
    previewJobId: String?,
    previewChunks: List<TranslationChunkEntity>,
    isImporting: Boolean,
    statusBanner: String?,
    onSelectSourceLanguage: (SourceLanguage) -> Unit,
    onImportFile: (Uri) -> Unit,
    onStartOrResumeJob: (TranslationJobEntity) -> Unit,
    onPauseJob: (String) -> Unit,
    onDeleteJob: (String) -> Unit,
    onChangeJobLanguage: (String, SourceLanguage) -> Unit,
    onOpenPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
    onExportJob: (String, Uri, ExportMode) -> Unit,
    onDownloadPack: (TranslationDirection) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingExportRequest by remember { mutableStateOf<Pair<TranslationJobEntity, ExportMode>?>(null) }
    var exportChoiceJob by remember { mutableStateOf<TranslationJobEntity?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportFile(uri)
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val req = pendingExportRequest
        pendingExportRequest = null
        if (uri != null && req != null) {
            onExportJob(req.first.id, uri, req.second)
        }
    }

    val activeDirection = TranslationDirection.forSource(selectedSourceLanguage)
    val activePack = translationManagerState.stateFor(activeDirection)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Workspace Header & Controls
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("مترجم الملفات المحلي", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "ترجمة المستندات الطويلة على دفعات • تستمر في الخلفية دون إنترنت",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text("دون إنترنت") }
                            )
                        }

                        // Source & Target Language Bar (Phase 1: FR or EN -> AR)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("لغة المصدر ← اللغة الهدف (العربية):", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SourceLanguage.entries.forEach { lang ->
                                    val dir = TranslationDirection.forSource(lang)
                                    val packReady = translationManagerState.stateFor(dir).status == TranslationPackStatus.READY
                                    FilterChip(
                                        selected = selectedSourceLanguage == lang,
                                        onClick = { onSelectSourceLanguage(lang) },
                                        label = {
                                            Text("${lang.labelAr} (${dir.shortBadge})" + if (packReady) " ✓" else "")
                                        },
                                        modifier = Modifier.testTag("translator_lang_${lang.code}")
                                    )
                                }
                            }
                        }

                        // Shared Engine Readiness Strip
                        Surface(
                            color = if (activePack.status == TranslationPackStatus.READY) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "المحرك المشترك: ${activeDirection.displayNameAr}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = activePack.statusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (activePack.status != TranslationPackStatus.READY) {
                                    Spacer(Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { onDownloadPack(activeDirection) },
                                        enabled = activePack.status != TranslationPackStatus.DOWNLOADING,
                                        modifier = Modifier.testTag("translator_download_pack_button")
                                    ) {
                                        if (activePack.status == TranslationPackStatus.DOWNLOADING) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (activePack.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..." else "تنزيل المحرك")
                                    }
                                } else {
                                    Icon(
                                        Icons.Rounded.CloudDone,
                                        contentDescription = "جاهز دون اتصال",
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }

                        // Import File Button (TXT / EPUB, noting PDF future support)
                        Button(
                            onClick = {
                                openDocumentLauncher.launch(
                                    arrayOf("text/plain", "application/epub+zip", "application/octet-stream")
                                )
                            },
                            enabled = !isImporting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("translator_import_file_button")
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("استيراد ملف نصي للترجمة (TXT / EPUB)")
                        }

                        Text(
                            text = "يدعم TXT وEPUB حالياً مع الحفاظ على العناوين والفقرات، وقابلية إضافة PDF لاحقاً.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (statusBanner != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissBanner,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "إغلاق التنبيه")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "مهام الترجمة (${jobs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (jobs.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text("لا توجد ملفات قيد الترجمة بعد", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "استورد ملف TXT أو EPUB بالفرنسية أو الإنجليزية؛ سيُقسَّم تلقائياً إلى دفعات وتستمر ترجمته في الخلفية مع حفظ كل جزء منجز.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    TranslationJobCard(
                        job = job,
                        onStartOrResume = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            onStartOrResumeJob(job)
                        },
                        onPause = { onPauseJob(job.id) },
                        onToggleLanguage = {
                            val current = SourceLanguage.fromCode(job.sourceLanguage)
                            val next = if (current == SourceLanguage.FRENCH) SourceLanguage.ENGLISH else SourceLanguage.FRENCH
                            onChangeJobLanguage(job.id, next)
                        },
                        onPreview = { onOpenPreview(job.id) },
                        onExport = { exportChoiceJob = job },
                        onDelete = { onDeleteJob(job.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (previewJobId != null) {
        val activeJob = jobs.firstOrNull { it.id == previewJobId }
        ModalBottomSheet(onDismissRequest = onClosePreview) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "معاينة الترجمة: ${activeJob?.title.orEmpty()}",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "منجز ${activeJob?.completedChunks ?: 0} من ${activeJob?.totalChunks ?: previewChunks.size} جزء (يحفظ كل جزء فوراً دون تغيير الأصل)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(previewChunks, key = { it.id }) { chunk ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (chunk.isHeading) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (chunk.isHeading) "عنوان • جزء ${chunk.chunkIndex + 1}" else "جزء ${chunk.chunkIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = chunk.originalText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FrenchSerifFamily,
                                            fontWeight = if (chunk.isHeading) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    )
                                }
                                HorizontalDivider()
                                if (chunk.isCompleted && !chunk.translatedText.isNullOrBlank()) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Text(
                                            text = chunk.translatedText,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = ArabicSansFamily,
                                                fontWeight = if (chunk.isHeading) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "بانتظار الترجمة...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (exportChoiceJob != null) {
        val jobToExport = exportChoiceJob!!
        ModalBottomSheet(onDismissRequest = { exportChoiceJob = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("تصدير الترجمة إلى ملف مستقل", style = MaterialTheme.typography.titleLarge)
                Text(
                    "يُنشئ ملفاً نصياً جديداً (UTF-8) يحافظ على العناوين والفقرات دون المساس بالملف الأصلي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExportMode.entries.forEach { mode ->
                    Button(
                        onClick = {
                            val suffix = if (mode == ExportMode.ARABIC_ONLY) "_ar.txt" else "_bilingual.txt"
                            val safeName = jobToExport.title.replace(Regex("""[^\p{L}\p{N}_\- ]"""), "_").ifBlank { "translation" }
                            pendingExportRequest = jobToExport to mode
                            exportChoiceJob = null
                            createDocumentLauncher.launch("$safeName$suffix")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(mode.labelAr)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun TranslationJobCard(
    job: TranslationJobEntity,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onToggleLanguage: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val total = job.totalChunks.coerceAtLeast(1)
    val done = job.completedChunks.coerceIn(0, total)
    val remaining = (total - done).coerceAtLeast(0)
    val progress = done.toFloat() / total.toFloat()
    val direction = TranslationDirection.forSource(SourceLanguage.fromCode(job.sourceLanguage))

    val statusLabel = when (job.status) {
        "RUNNING" -> "جاري في الخلفية"
        "PAUSED" -> "متوقف مؤقتاً"
        "COMPLETED" -> "مكتمل"
        "FAILED" -> "توقف بسبب خطأ"
        else -> "جاهز للبدء"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("translation_job_card_${job.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = job.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FrenchSerifFamily),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${job.sourceType} • $statusLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (job.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                AssistChip(
                    onClick = { if (job.status != "RUNNING") onToggleLanguage() },
                    label = { Text(direction.shortBadge) }
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "المنجز: $done / $total جزء • المتبقي: $remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!job.lastError.isNullOrBlank()) {
                Text(
                    text = job.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (job.status) {
                    "RUNNING" -> {
                        FilledTonalButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إيقاف مؤقت")
                        }
                    }
                    "COMPLETED" -> {
                        FilledTonalButton(onClick = onExport) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تصدير")
                        }
                    }
                    else -> {
                        Button(onClick = onStartOrResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (done > 0) "استئناف" else "بدء الترجمة")
                        }
                    }
                }

                OutlinedButton(onClick = onPreview) {
                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("معاينة")
                }

                if (done > 0 && job.status != "COMPLETED") {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "تصدير الأجزاء المنجزة")
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "حذف المهمة",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
