package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class EcouteAtelierColors(
    val isDark: Boolean,
    val cardBorder: Color,
    val ambientGlowTop: Color,
    val ambientGlowBottom: Color,
    val wordHighlightBg: Color,
    val warmGoldAccent: Color,
    val activeParagraphStripe: Color
)

private val LightAtelierColors = EcouteAtelierColors(
    isDark = false,
    cardBorder = OutlineVariantLight,
    ambientGlowTop = Color(0x18B84A24),
    ambientGlowBottom = Color(0x14162B49),
    wordHighlightBg = WordHighlightBgLight,
    warmGoldAccent = AtelierWarmGoldLight,
    activeParagraphStripe = TerracottaSecondaryLight
)

private val DarkAtelierColors = EcouteAtelierColors(
    isDark = true,
    cardBorder = OutlineVariantDark,
    ambientGlowTop = Color(0x22264B80),
    ambientGlowBottom = Color(0x1AF2A07B),
    wordHighlightBg = WordHighlightBgDark,
    warmGoldAccent = AtelierWarmGoldDark,
    activeParagraphStripe = TerracottaSecondaryDark
)

val LocalEcouteAtelierColors = staticCompositionLocalOf { LightAtelierColors }

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimaryDark,
    onPrimary = OnIndigoDark,
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = OnIndigoContainerDark,
    secondary = TerracottaSecondaryDark,
    onSecondary = OnTerracottaDark,
    secondaryContainer = TerracottaContainerDark,
    onSecondaryContainer = OnTerracottaContainerDark,
    tertiary = SageTertiaryDark,
    onTertiary = OnSageDark,
    tertiaryContainer = SageContainerDark,
    onTertiaryContainer = OnSageContainerDark,
    background = InkBackgroundDark,
    onBackground = OnInkDark,
    surface = InkSurfaceDark,
    onSurface = OnInkDark,
    surfaceVariant = InkSurfaceVariantDark,
    onSurfaceVariant = OnInkVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoPrimaryLight,
    onPrimary = OnIndigoLight,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = OnIndigoContainerLight,
    secondary = TerracottaSecondaryLight,
    onSecondary = OnTerracottaLight,
    secondaryContainer = TerracottaContainerLight,
    onSecondaryContainer = OnTerracottaContainerLight,
    tertiary = SageTertiaryLight,
    onTertiary = OnSageLight,
    tertiaryContainer = SageContainerLight,
    onTertiaryContainer = OnSageContainerLight,
    background = PaperBackgroundLight,
    onBackground = OnPaperLight,
    surface = PaperSurfaceLight,
    onSurface = OnPaperLight,
    surfaceVariant = PaperSurfaceVariantLight,
    onSurfaceVariant = OnPaperVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

val EcouteShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EcouteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val atelierColors = if (darkTheme) DarkAtelierColors else LightAtelierColors

    CompositionLocalProvider(LocalEcouteAtelierColors provides atelierColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = EcouteShapes,
            content = content
        )
    }
}
