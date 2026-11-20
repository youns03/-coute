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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
    outline = OutlineDark
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
    outline = OutlineLight
)

val EcouteShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = EcouteShapes,
        content = content
    )
}
