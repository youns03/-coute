package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.TranslationChunkEntity
import com.example.data.TranslationJobEntity
import com.example.translation.ExportMode
import com.example.translation.SourceLanguage
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationPackStatus
import com.example.ui.components.AtelierCard
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.MonospaceMetricPill
import com.example.ui.theme.ArabicSansFamily
import com.example.ui.theme.EditorialDisplayFamily
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily

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
    onResetAndChangeJobLanguage: (String, SourceLanguage) -> Unit,
    onOpenPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
    onExportJob: (String, Uri, ExportMode) -> Unit,
    onDownloadPack: (TranslationDirection) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current
    var pendingExportRequest by remember { mutableStateOf<Pair<TranslationJobEntity, ExportMode>?>(null) }
    var exportChoiceJob by remember { mutableStateOf<TranslationJobEntity?>(null) }
    var languageResetConfirm by remember { mutableStateOf<Pair<TranslationJobEntity, SourceLanguage>?>(null) }
    var pendingDeleteJob by remember { mutableStateOf<TranslationJobEntity?>(null) }

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
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Workspace Header & Controls
            item {
                AtelierCard(
                    elevation = 2.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Translate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            "Atelier Traduction",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = EditorialDisplayFamily,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    Text(stringResource(R.string.title_translator), style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        stringResource(R.string.translator_header_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            MonospaceMetricPill(
                                text = "OFFLINE",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }

                        // Source -> Target Arabic directional selector
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                            border = BorderStroke(1.dp, atelier.cardBorder),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(stringResource(R.string.translator_source_lang), style = MaterialTheme.typography.labelMedium)
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        stringResource(R.string.translator_target_lang),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
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
                        }

                        // Shared Engine Readiness Strip
                        Surface(
                            color = if (activePack.status == TranslationPackStatus.READY) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (activePack.status == TranslationPackStatus.READY) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                } else {
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                                }
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.translator_shared_engine, activeDirection.displayNameAr),
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
                                        Text(
                                            if (activePack.status == TranslationPackStatus.DOWNLOADING) {
                                                stringResource(R.string.reader_download_in_progress)
                                            } else {
                                                stringResource(R.string.translator_download_engine)
                                            }
                                        )
                                    }
                                } else {
                                    Icon(
                                        Icons.Rounded.CloudDone,
                                        contentDescription = stringResource(R.string.translator_ready_offline_cd),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }

                        // Import File Button (TXT / EPUB)
                        Button(
                            onClick = {
                                openDocumentLauncher.launch(
                                    arrayOf("text/plain", "application/epub+zip", "application/octet-stream")
                                )
                            },
                            enabled = !isImporting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("translator_import_file_button")
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            } else {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.translator_import_button))
                        }

                        Text(
                            text = stringResource(R.string.translator_supported_formats),
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
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
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
                EditorialSectionHeader(
                    titleAr = stringResource(R.string.translator_jobs_title),
                    folioTag = "Tâches",
                    countBadge = "${jobs.size}"
                )
            }

            if (jobs.isEmpty()) {
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
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Text(stringResource(R.string.translator_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.translator_empty_subtitle),
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
                            if (job.completedChunks > 0) {
                                languageResetConfirm = job to next
                            } else {
                                onChangeJobLanguage(job.id, next)
                            }
                        },
                        onPreview = { onOpenPreview(job.id) },
                        onExport = { exportChoiceJob = job },
                        onDelete = { pendingDeleteJob = job }
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
                MonospaceMetricPill(
                    text = "منجز ${activeJob?.completedChunks ?: 0} / ${activeJob?.totalChunks ?: previewChunks.size}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(previewChunks, key = { it.id }) { chunk ->
                        AtelierCard(
                            containerColor = if (chunk.isHeading) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(13.dp),
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
                                HorizontalDivider(color = atelier.cardBorder)
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
        val availableModes = if (jobToExport.completedChunks >= jobToExport.totalChunks) {
            listOf(ExportMode.ARABIC_COMPLETED_ONLY, ExportMode.BILINGUAL_COMPLETED_ONLY)
        } else {
            ExportMode.entries
        }
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
                availableModes.forEach { mode ->
                    Button(
                        onClick = {
                            val safeName = jobToExport.title.replace(Regex("""[^\p{L}\p{N}_\- ]"""), "_").ifBlank { "translation" }
                            pendingExportRequest = jobToExport to mode
                            exportChoiceJob = null
                            createDocumentLauncher.launch("$safeName${mode.fileSuffix}")
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
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

    if (languageResetConfirm != null) {
        val (targetJob, nextLang) = languageResetConfirm!!
        AlertDialog(
            onDismissRequest = { languageResetConfirm = null },
            title = { Text("تأكيد تغيير لغة المصدر") },
            text = {
                Text(
                    "تحتوي مهمة «${targetJob.title}» على ${targetJob.completedChunks} جزءاً مترجماً بالفعل.\n\n" +
                        "لمنع خلط ترجمات الفرنسية والإنجليزية في ملف واحد، سيؤدي التحويل إلى «${nextLang.labelAr}» " +
                        "إلى مسح جميع الترجمات المحفوظة وإعادة المهمة من البداية (0 / ${targetJob.totalChunks})."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val j = targetJob
                        val l = nextLang
                        languageResetConfirm = null
                        onResetAndChangeJobLanguage(j.id, l)
                    }
                ) {
                    Text("مسح الترجمات وتغيير اللغة")
                }
            },
            dismissButton = {
                TextButton(onClick = { languageResetConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingDeleteJob?.let { targetJob ->
        AlertDialog(
            onDismissRequest = { pendingDeleteJob = null },
            title = { Text(stringResource(R.string.confirm_delete_job_title)) },
            text = { Text(stringResource(R.string.confirm_delete_job_message, targetJob.title)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteJob = null
                        onDeleteJob(targetJob.id)
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
                TextButton(onClick = { pendingDeleteJob = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
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

    AtelierCard(
        borderColor = if (job.status == "RUNNING") {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        } else {
            LocalEcouteAtelierColors.current.cardBorder
        },
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
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        color = when (job.status) {
                            "FAILED" -> MaterialTheme.colorScheme.error
                            "COMPLETED" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                }

                AssistChip(
                    onClick = onToggleLanguage,
                    enabled = job.status != "RUNNING",
                    label = {
                        Text(
                            direction.shortBadge,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = StudioMonoFamily)
                        )
                    }
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                color = if (job.status == "COMPLETED") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "المنجز: $done / $total جزء • المتبقي: $remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MonospaceMetricPill(
                    text = "${(progress * 100).toInt()}%",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    contentColor = MaterialTheme.colorScheme.primary
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
                        FilledTonalButton(
                            onClick = onPause,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إيقاف مؤقت")
                        }
                    }
                    "COMPLETED" -> {
                        FilledTonalButton(
                            onClick = onExport,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تصدير")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStartOrResume,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (done > 0) "استئناف" else "بدء الترجمة")
                        }
                    }
                }

                OutlinedButton(
                    onClick = onPreview,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
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
