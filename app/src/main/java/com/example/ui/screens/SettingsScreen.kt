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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.UserSettings
import com.example.domain.AcceleratorMode
import com.example.domain.SpeedControlMode
import com.example.models.ModelCatalog
import com.example.translation.TranslationDirection
import com.example.translation.TranslationManagerUiState
import com.example.translation.TranslationModelCatalog
import com.example.translation.TranslationPackStatus
import com.example.tts.DeviceHardwareInfo

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
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${dir.displayNameAr} (${dir.shortBadge})",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(packState.statusMessage, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (packState.status != TranslationPackStatus.READY) {
                                            Button(
                                                onClick = { onDownloadTranslationPack(dir) },
                                                enabled = packState.status != TranslationPackStatus.DOWNLOADING,
                                                modifier = Modifier.testTag("download_translation_${dir.source.code}")
                                            ) {
                                                Icon(Icons.Rounded.Download, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    if (packState.status == TranslationPackStatus.DOWNLOADING) "جاري التنزيل..."
                                                    else "تنزيل (${dir.shortBadge})"
                                                )
                                            }
                                        } else {
                                            OutlinedButton(onClick = { onDeleteTranslationPack(dir) }) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("حذف الحزمة")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider()
                        Text("مرجع أوزان OPUS-MT الستة (1,836 MB إجمالاً — المرحلة 1 تحصر التنزيل باتجاه العربية):", style = MaterialTheme.typography.labelMedium)
                        TranslationModelCatalog.opusReferenceMatrix.forEach { spec ->
                            Text(
                                text = "• ${spec.directionLabelAr} (${spec.modelId}): ~${spec.approxSizeMb} MB — " +
                                    if (spec.includedInPhase1) "مشمول في المرحلة 1" else "مؤجل لما بعد المرحلة 1",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (spec.includedInPhase1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                        Text("حجم الخط الافتراضي: ${settings.readerFontSizeSp.toInt()} sp")
                        Slider(value = settings.readerFontSizeSp, onValueChange = onFontSizeChange, valueRange = 15f..30f)
                        Text("تباعد الأسطر: ${"%.2f".format(settings.readerLineHeightMultiplier)}x")
                        Slider(value = settings.readerLineHeightMultiplier, onValueChange = onLineHeightChange, valueRange = 1.3f..2.2f)
                        OutlinedButton(onClick = onClearAudioCache) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("مسح ذاكرة الصوت المؤقتة (cacheDir)")
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("معلومات جهاز الاختبار الفعلي", style = MaterialTheme.typography.titleSmall)
                        Text("الطراز: ${hardwareInfo.deviceModel}", style = MaterialTheme.typography.bodySmall)
                        Text("المعمارية (ABI): ${hardwareInfo.primaryAbi}", style = MaterialTheme.typography.bodySmall)
                        Text("الذاكرة الكلية (RAM): ${hardwareInfo.totalRamMb} MB", style = MaterialTheme.typography.bodySmall)
                        Text("النظام: Android ${hardwareInfo.androidVersion} (API ${hardwareInfo.sdkInt})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("سجل التراخيص وحقوق النشر (Section 14)", style = MaterialTheme.typography.titleMedium)
                }
            }

            items(ModelCatalog.licenseLedger) { rec ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(rec.componentName, style = MaterialTheme.typography.titleSmall)
                        Text("${rec.role} • المالك: ${rec.owner}", style = MaterialTheme.typography.bodySmall)
                        Text("ترخيص الأوزان/الكود: ${rec.weightsOrCodeLicense}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("ترخيص الصوت/البيانات: ${rec.voiceOrDataLicense}", style = MaterialTheme.typography.labelSmall)
                        Text("الاستخدام التجاري: ${rec.commercialTerms}", style = MaterialTheme.typography.labelSmall)
                        Text("الإسناد: ${rec.attributionRequirement}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}
