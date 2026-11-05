package com.example.unutma.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Wraps the app in the Unutma look. [textScale] comes from Settings › Text size and scales
 * every piece of text on top of the phone's own font size.
 */
@Composable
fun UnutmaTheme(
    darkTheme: Boolean = true,
    accentKey: String = "PURPLE",
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val accent = swatch(accentKey).on(darkTheme)
    val colors = if (darkTheme) darkColors(accent) else lightColors(accent)
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = accent, onPrimary = colors.onAccent,
            secondary = accent, onSecondary = colors.onAccent,
            background = colors.bg, onBackground = colors.fg,
            surface = colors.bg, onSurface = colors.fg,
            surfaceVariant = colors.menuBg, onSurfaceVariant = colors.muted,
            surfaceContainer = colors.menuBg, surfaceContainerHigh = colors.menuBg,
            surfaceContainerHighest = colors.selected, surfaceContainerLow = colors.bg,
            outline = colors.menuBorder, outlineVariant = colors.divider,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = colors.onAccent,
            secondary = accent, onSecondary = colors.onAccent,
            background = colors.bg, onBackground = colors.fg,
            surface = colors.bg, onSurface = colors.fg,
            surfaceVariant = colors.selected, onSurfaceVariant = colors.muted,
            surfaceContainer = colors.menuBg, surfaceContainerHigh = colors.menuBg,
            surfaceContainerHighest = colors.selected, surfaceContainerLow = colors.bg,
            outline = colors.menuBorder, outlineVariant = colors.divider,
            error = colors.danger,
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalUnutmaColors provides colors,
        LocalDensity provides Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
