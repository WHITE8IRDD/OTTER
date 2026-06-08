package com.otter.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Light mode
val LightBackground = Color(0xFFFAFAF9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F7F5)
val LightSurfaceVariant = Color(0xFFF4F4F2)
val LightOutline = Color(0xFFE5E5E3)
val LightOnSurface = Color(0xFF0A0A0A)
val LightOnSurfaceVariant = Color(0xFF6B6B68)
val LightError = Color(0xFFB91C1C)
val LightErrorContainer = Color(0xFFFEF2F2)
val LightOnError = Color(0xFFFFFFFF)
val LightOnErrorContainer = Color(0xFFB91C1C)

// Dark mode
val DarkBackground = Color(0xFF0A0A0A)
val DarkSurface = Color(0xFF161616)
val DarkSurfaceContainerLow = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF1F1F1F)
val DarkOutline = Color(0xFF2A2A2A)
val DarkOnSurface = Color(0xFFF5F5F4)
val DarkOnSurfaceVariant = Color(0xFFA0A09D)
val DarkError = Color(0xFFFCA5A5)
val DarkErrorContainer = Color(0xFF3B1212)
val DarkOnError = Color(0xFF0A0A0A)
val DarkOnErrorContainer = Color(0xFFFCA5A5)

// Accent presets
val Periwinkle = Color(0xFF4E55E0)
val DarkPeriwinkle = Color(0xFF6B72FF)
val LimeGreen = Color(0xFFB8EB6C)
val DarkLimeGreen = Color(0xFF8BC34A)
val GoldenYellow = Color(0xFFF7CD63)
val DarkGoldenYellow = Color(0xFFE6B831)
val PlayfulPink = Color(0xFFFC8FC6)
val DarkPlayfulPink = Color(0xFFE070A0)
val ErrorRed = Color(0xFFE53935)
val PureWhite = Color(0xFFFFFFFF)
val OffBlack = Color(0xFF070707)

data class AccentPreset(val name: String, val light: Color, val dark: Color)

val ACCENT_PRESETS = listOf(
    AccentPreset("Periwinkle", Periwinkle, DarkPeriwinkle),
    AccentPreset("Lime Green", LimeGreen, DarkLimeGreen),
    AccentPreset("Golden Yellow", GoldenYellow, DarkGoldenYellow),
    AccentPreset("Playful Pink", PlayfulPink, DarkPlayfulPink),
    AccentPreset("Electric Blue", Color(0xFF2196F3), Color(0xFF64B5F6)),
    AccentPreset("Sunset Orange", Color(0xFFFF6D00), Color(0xFFFFAB40)),
    AccentPreset("Forest Green", Color(0xFF2E7D32), Color(0xFF66BB6A)),
    AccentPreset("Royal Purple", Color(0xFF7B1FA2), Color(0xFFBA68C8)),
    AccentPreset("Hot Red", Color(0xFFD32F2F), Color(0xFFEF5350)),
    AccentPreset("Cyan", Color(0xFF00ACC1), Color(0xFF4DD0E1)),
    AccentPreset("Teal", Color(0xFF00897B), Color(0xFF4DB6AC)),
    AccentPreset("Magenta", Color(0xFFC2185B), Color(0xFFF06292)),
    AccentPreset("Amber", Color(0xFFFFB300), Color(0xFFFFD54F)),
    AccentPreset("Indigo", Color(0xFF3949AB), Color(0xFF7986CB)),
    AccentPreset("Deep Orange", Color(0xFFBF360C), Color(0xFFFF8A65)),
    AccentPreset("Light Blue", Color(0xFF0288D1), Color(0xFF4FC3F7)),
    AccentPreset("Brown", Color(0xFF5D4037), Color(0xFF8D6E63)),
    AccentPreset("Blue Grey", Color(0xFF455A64), Color(0xFF78909C)),
    AccentPreset("Lime", Color(0xFF9E9D24), Color(0xFFD4E157)),
    AccentPreset("Deep Purple", Color(0xFF512DA8), Color(0xFF9575CD)),
    AccentPreset("White", Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
    AccentPreset("Grey", Color(0xFF808080), Color(0xFF808080)),
    AccentPreset("Black", Color(0xFF000000), Color(0xFF000000))
)

fun Color.lighten(factor: Float): Color {
    val r = red + (1f - red) * factor
    val g = green + (1f - green) * factor
    val b = blue + (1f - blue) * factor
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
}

fun Color.darken(factor: Float): Color {
    val r = red * (1f - factor)
    val g = green * (1f - factor)
    val b = blue * (1f - factor)
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
}

fun Color.saturate(factor: Float): Color {
    val grayLuminance = luminance()
    val r = red + (red - grayLuminance) * factor
    val g = green + (green - grayLuminance) * factor
    val b = blue + (blue - grayLuminance) * factor
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
}
