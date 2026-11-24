package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.audio.PlaybackStatus
import com.example.audio.PlaybackUiState
import com.example.data.BenchmarkReportEntity
import com.example.data.UserSettings
import com.example.domain.BenchmarkCorpus
import com.example.domain.BenchmarkItem
import com.example.domain.ModelInstallStatus
import com.example.models.ModelUiEntry
import com.example.tts.EngineRuntimeState
import com.example.ui.theme.FrenchSerifFamily
import com.example.ui.viewmodel.BenchmarkLabUiState

@Composable
fun PlayerAndLabScreen(
    playbackState: PlaybackUiState,
    engineState: EngineRuntimeState,
    settings: UserSettings,
    models: List<ModelUiEntry>,
    labState: BenchmarkLabUiState,
    reports: List<BenchmarkReportEntity>,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplaySentence: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onReleaseEngine: () -> Unit,
    onRunSingleBenchmark: (String, BenchmarkItem, Float) -> Unit,
    onRunFullBenchmark: () -> Unit,
    onPlayWavSample: (String) -> Unit,
    onClearReports: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) }
                ) {
                    Text("مشغّل الصوت")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Rounded.Science, contentDescription = null) },
                    modifier = Modifier.testTag("audio_lab_tab")
                ) {
                    Text("مختبر الجودة (المرحلة 0)")
                }
            }

            if (selectedTab == 0) {
                PlayerTabContent(
                    playbackState = playbackState,
                    engineState = engineState,
                    settings = settings,
                    onTogglePlayPause = onTogglePlayPause,
                    onStop = onStop,
                    onNext = onNext,
                    onPrev = onPrev,
                    onReplaySentence = onReplaySentence,
                    onToggleRepeat = onToggleRepeat,
                    onSetSpeed = onSetSpeed,
                    onReleaseEngine = onReleaseEngine
                )
            } else {
                AudioLabTabContent(
                    models = models,
                    labState = labState,
                    reports = reports,
                    onRunSingle = onRunSingleBenchmark,
                    onRunFullSuite = onRunFullBenchmark,
                    onPlayWav = onPlayWavSample,
                    onClearReports = onClearReports
                )
            }
        }
    }
}

@Composable
private fun PlayerTabContent(
    playbackState: PlaybackUiState,
    engineState: EngineRuntimeState,
    settings: UserSettings,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplaySentence: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onReleaseEngine: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playbackState.bookTitle.ifBlank { "لا يوجد تشغيل نشط" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (playbackState.totalSentencesInChapter > 0) {
                                    "${playbackState.chapterTitle} • جملة ${playbackState.sentenceIndex + 1} / ${playbackState.totalSentencesInChapter}"
                                } else {
                                    "ابدأ التشغيل من القارئ أو جرّب جملاً في مختبر الصوت"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = playbackState.status.name,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = playbackState.currentSentenceText.ifBlank {
                                    "Sélectionnez un livre dans la bibliothèque pour écouter la prononciation française locale."
                                },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = FrenchSerifFamily,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    if (playbackState.status == PlaybackStatus.BUFFERING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // Transport Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrev, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "الجملة السابقة")
                        }
                        IconButton(onClick = onReplaySentence, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Replay, contentDescription = "إعادة الجملة الحالية")
                        }
                        Button(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_play_pause_button")
                        ) {
                            Icon(
                                if (playbackState.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause
                                else Icons.Rounded.PlayArrow,
                                contentDescription = "تشغيل أو إيقاف"
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (playbackState.status == PlaybackStatus.PLAYING) "إيقاف مؤقت" else "تشغيل")
                        }
                        IconButton(onClick = onNext, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "الجملة التالية")
                        }
                        IconButton(onClick = onStop, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Rounded.Stop, contentDescription = "إيقاف كامل")
                        }
                    }

                    // Speed Pills & Repeat Current Sentence
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("سرعة القراءة:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { speed ->
                                FilterChip(
                                    selected = settings.playbackSpeed == speed,
                                    onClick = { onSetSpeed(speed) },
                                    label = { Text("${speed}x") }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            FilterChip(
                                selected = playbackState.repeatCurrentSentence,
                                onClick = onToggleRepeat,
                                leadingIcon = { Icon(Icons.Rounded.RepeatOne, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("تكرار") }
                            )
                        }
                    }
                }
            }
        }

        // Live Engine & Memory Telemetry Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("حالة المحرك والذاكرة في الوقت الفعلي", style = MaterialTheme.typography.titleSmall)
                        }
                        OutlinedButton(onClick = onReleaseEngine) {
                            Text("تحرير RAM")
                        }
                    }
                    Text(
                        text = "النموذج المحمّل: ${engineState.loadedModelName ?: "غير محمل (يُحمّل عند التشغيل)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "المُسرّع الفعلي: ${engineState.acceleratorLabel}" +
                            if (engineState.fellBackToCpu) " (تراجع تلقائي لـ CPU)" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "RAM قبل التحميل: ${"%.1f".format(engineState.ramBeforeLoadMb)} MB • بعد التحميل: ${"%.1f".format(engineState.ramAfterLoadMb)} MB • الذروة: ${"%.1f".format(engineState.ramPeakSynthesisMb)} MB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "وضع السرعة: ${settings.speedControlMode.labelAr} • الجمل المسبقة بالرتل: ${playbackState.bufferedAheadCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioLabTabContent(
    models: List<ModelUiEntry>,
    labState: BenchmarkLabUiState,
    reports: List<BenchmarkReportEntity>,
    onRunSingle: (String, BenchmarkItem, Float) -> Unit,
    onRunFullSuite: () -> Unit,
    onPlayWav: (String) -> Unit,
    onClearReports: () -> Unit
) {
    val installedModels = models.filter { it.status == ModelInstallStatus.INSTALLED }
    var selectedModelId by remember(installedModels) {
        mutableStateOf(installedModels.firstOrNull()?.spec?.id ?: "")
    }
    var testSpeed by remember { mutableStateOf(1.0f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("مختبر قياس جودة الصوت (Section 13)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "يقيس الأداء الفعلي على جهازك الحالي: زمن التحميل، زمن أول صوت (TTFA)، معامل الزمن الحقيقي (RTF)، واستهلاك RAM الحقيقي مع حفظ عينات WAV للاستماع البشري.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (installedModels.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "نزّل نموذجاً واحداً على الأقل (Kokoro أو Piper SIWIS أو Piper TOM) من شاشة إدارة النماذج لتشغيل اختبارات المرحلة صفر.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Text("اختر النموذج للاختبار:", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            installedModels.forEach { entry ->
                                FilterChip(
                                    selected = selectedModelId == entry.spec.id,
                                    onClick = { selectedModelId = entry.spec.id },
                                    label = { Text(entry.spec.displayName) }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1.0f, 1.5f).forEach { sp ->
                                FilterChip(
                                    selected = testSpeed == sp,
                                    onClick = { testSpeed = sp },
                                    label = { Text("${sp}x") }
                                )
                            }
                        }

                        Button(
                            onClick = onRunFullSuite,
                            enabled = !labState.isRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_full_benchmark_button")
                        ) {
                            if (labState.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(labState.currentStepLabel)
                            } else {
                                Text("تشغيل الاختبار الشامل على جميع النماذج المثبّتة")
                            }
                        }
                    }

                    if (!labState.lastError.isNullOrBlank()) {
                        Text(
                            text = labState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (installedModels.isNotEmpty() && selectedModelId.isNotBlank()) {
            item {
                Text("مجموعة النصوص المرجعية (Section 13):", style = MaterialTheme.typography.titleSmall)
            }
            items(BenchmarkCorpus.items, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.categoryAr, style = MaterialTheme.typography.titleSmall)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = item.frenchText.take(180) + if (item.frenchText.length > 180) "..." else "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FrenchSerifFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { onRunSingle(selectedModelId, item, testSpeed) },
                            enabled = !labState.isRunning
                        ) {
                            Text("اختبار هذه العينة")
                        }
                    }
                }
            }
        }

        if (reports.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("النتائج الفعلية المسجلة (${reports.size})", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClearReports) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "مسح التقارير")
                    }
                }
            }

            items(reports, key = { it.id }) { r ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${r.modelDisplayName} • ${r.corpusCategory}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "الجهاز: ${r.deviceModel} (${r.socOrAbi}, ${r.totalRamMb} MB RAM, ${r.androidVersion})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "المسرّع: ${r.acceleratorUsed} | سرعة: ${r.speedFactor}x",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "التحميل: ${r.modelLoadTimeMs} ms | TTFA: ${r.ttfaMs} ms | التوليد: ${r.totalSynthesisMs} ms | طول الصوت: ${r.audioDurationMs} ms | RTF: ${"%.2f".format(r.realTimeFactor)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "RAM قبل: ${"%.1f".format(r.ramBeforeLoadMb)} MB | بعد التحميل: ${"%.1f".format(r.ramAfterLoadMb)} MB | الذروة: ${"%.1f".format(r.ramPeakSynthesisMb)} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (r.notes.isNotBlank()) {
                            Text(r.notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!r.wavSamplePath.isNullOrBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            OutlinedButton(
                                onClick = { onPlayWav(r.wavSamplePath) },
                                enabled = labState.playingSamplePath != r.wavSamplePath
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (labState.playingSamplePath == r.wavSamplePath) "جاري تشغيل WAV..."
                                    else "استماع للعينة المحفوظة (WAV)"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
