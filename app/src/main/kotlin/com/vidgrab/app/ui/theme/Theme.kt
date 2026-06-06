package com.vidgrab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

fun lightColorSchemeFromAccent(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.7f) OffBlack else PureWhite,
    primaryContainer = accent.copy(alpha = 0.10f),
    onPrimaryContainer = accent,
    secondary = accent.lighten(0.35f).saturate(1.2f),
    onSecondary = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.5f),
    error = ErrorRed,
    onError = PureWhite,
    errorContainer = ErrorRed.copy(alpha = 0.10f),
    onErrorContainer = ErrorRed
)

fun darkColorSchemeFromAccent(accent: Color) = darkColorScheme(
    primary = accent.lighten(0.15f),
    onPrimary = if (accent.lighten(0.15f).luminance() > 0.5f) DarkBackground else PureWhite,
    primaryContainer = accent.copy(alpha = 0.15f),
    onPrimaryContainer = accent.lighten(0.3f),
    secondary = accent.lighten(0.3f).saturate(1.2f),
    onSecondary = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline.copy(alpha = 0.5f),
    error = ErrorRed,
    onError = PureWhite,
    errorContainer = ErrorRed.copy(alpha = 0.15f),
    onErrorContainer = ErrorRed
)

@Composable
fun OtterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = Periwinkle,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorSchemeFromAccent(accentColor)
    } else {
        lightColorSchemeFromAccent(accentColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = OtterShapes,
        content = content
    )
}
