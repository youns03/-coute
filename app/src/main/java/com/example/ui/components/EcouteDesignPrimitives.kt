package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.theme.EditorialDisplayFamily
import com.example.ui.theme.LocalEcouteAtelierColors
import com.example.ui.theme.StudioMonoFamily
import kotlin.math.PI
import kotlin.math.sin

/**
 * Subtle ambient parchment & acoustic atmosphere backdrop behind screens.
 * Keeps contrast calm and reading surfaces effortless without visual clutter.
 */
@Composable
fun EcouteAtelierBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val atelier = LocalEcouteAtelierColors.current
    val baseBg = MaterialTheme.colorScheme.background
    val ringColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (atelier.isDark) 0.25f else 0.38f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(atelier.ambientGlowTop, Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                        radius = size.maxDimension * 0.65f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(atelier.ambientGlowBottom, Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.92f),
                        radius = size.maxDimension * 0.60f
                    )
                )
                if (!atelier.isDark) {
                    val arcCenter = Offset(size.width * 0.94f, size.height * 0.04f)
                    for (i in 1..3) {
                        drawCircle(
                            color = ringColor,
                            radius = size.minDimension * (0.18f * i),
                            center = arcCenter,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            },
        content = content
    )
}

@Composable
fun AtelierCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = LocalEcouteAtelierColors.current.cardBorder,
    shape: Shape = MaterialTheme.shapes.large,
    elevation: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            shape = shape,
            modifier = modifier,
            content = content
        )
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            shape = shape,
            modifier = modifier,
            content = content
        )
    }
}

@Composable
fun EditorialSectionHeader(
    titleAr: String,
    folioTag: String? = null,
    countBadge: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Text(
                text = titleAr,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!folioTag.isNullOrBlank()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = folioTag,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = EditorialDisplayFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!countBadge.isNullOrBlank()) {
                MonospaceMetricPill(
                    text = countBadge,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MonospaceMetricPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = StudioMonoFamily),
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Tactile animated harmonic bars representing live local TTS synthesis/playback.
 */
@Composable
fun AcousticWaveIndicator(
    isPlaying: Boolean,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.secondary,
    barCount: Int = 5
) {
    val transition = rememberInfiniteTransition(label = "acoustic_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBuffering) 900 else 1150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Canvas(modifier = modifier) {
        val count = barCount.coerceAtLeast(3)
        val gap = size.width / (count * 2f - 1f)
        val barWidth = gap.coerceAtLeast(2.dp.toPx())
        val totalUsed = count * barWidth + (count - 1) * (barWidth * 0.65f)
        val startX = (size.width - totalUsed) / 2f

        for (i in 0 until count) {
            val ratio = when {
                isPlaying -> {
                    val offset = i * 0.9f
                    0.28f + 0.70f * ((sin(phase + offset) + 1f) * 0.5f)
                }
                isBuffering -> {
                    val offset = i * 0.55f
                    0.22f + 0.35f * ((sin(phase + offset) + 1f) * 0.5f)
                }
                else -> {
                    // Calm atelier crest when idle or paused
                    val centerDist = kotlin.math.abs(i - (count - 1) / 2f) / ((count - 1) / 2f).coerceAtLeast(1f)
                    0.30f + (1f - centerDist) * 0.35f
                }
            }
            val barH = (size.height * ratio).coerceAtLeast(barWidth)
            val x = startX + i * (barWidth * 1.65f)
            val y = (size.height - barH) / 2f
            drawRoundRect(
                color = if (isPlaying || isBuffering) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
