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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier
) {
    val atelier = LocalEcouteAtelierColors.current

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text("عتاد التنفيذ والسرعة", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("مسرّع التشغيل لـ Kokoro LiteRT:", style = MaterialTheme.typography.labelMedium)
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

                        Text("آلية تغيير سرعة الصوت:", style = MaterialTheme.typography.labelMedium)
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
                            Text("محركات الترجمة المحلية (المرحلة الأولى: اتجاهان فقط)", style = MaterialTheme.typography.titleMedium)
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
                                                    if (packState.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..."
                                                    else "تنزيل (${dir.shortBadge})"
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onDeleteTranslationPack(dir) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("حذف الحزمة")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = atelier.cardBorder)
                        Text(
                            "مرجع أوزان OPUS-MT الستة (1,836 MB إجمالاً — المرحلة 1 تحصر التنزيل باتجاه العربية):",
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
                        Text("إعدادات القراءة والذاكرة المؤقتة", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الوضع الليلي", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = settings.nightMode, onCheckedChange = onNightModeChange)
                        }
                        Text(
                            "حجم الخط الافتراضي: ${settings.readerFontSizeSp.toInt()} sp",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(value = settings.readerFontSizeSp, onValueChange = onFontSizeChange, valueRange = 15f..30f)
                        Text(
                            "تباعد الأسطر: ${"%.2f".format(settings.readerLineHeightMultiplier)}x",
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
                            Text("مسح ذاكرة الصوت المؤقتة (cacheDir)")
                        }
                    }
                }
            }

            item {
                AtelierCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("معلومات جهاز الاختبار الفعلي", style = MaterialTheme.typography.titleSmall)
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
                        titleAr = "سجل التراخيص وحقوق النشر (Section 14)",
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
