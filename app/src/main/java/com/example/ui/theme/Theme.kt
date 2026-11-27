package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val RiyaDarkColorScheme =
  darkColorScheme(
    primary = CyanGlow,
    onPrimary = MidnightDeep,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanGlow,
    secondary = GoldAccent,
    onSecondary = MidnightDeep,
    secondaryContainer = GoldContainer,
    onSecondaryContainer = GoldAccent,
    tertiary = VioletPremium,
    onTertiary = PearlText,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = PearlText,
    background = MidnightDeep,
    onBackground = PearlText,
    surface = MidnightSurface,
    onSurface = PearlText,
    surfaceVariant = MidnightElevated,
    onSurfaceVariant = SlateText,
    outline = MidnightBorder,
    error = CoralAlert,
    onError = PearlText,
    errorContainer = CoralContainer,
    onErrorContainer = CoralAlert,
  )

private val RiyaShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun RiyaAiTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = RiyaDarkColorScheme,
    typography = RiyaTypography,
    shapes = RiyaShapes,
    content = content,
  )
}
