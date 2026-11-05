package com.example.unutma.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun UnutmaTheme(
    darkTheme: Boolean = true,
    accentColor: Color = Accent,
    onAccentColor: Color = Black,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary          = accentColor,
            onPrimary        = onAccentColor,
            background       = Black,
            onBackground     = White,
            surface          = DarkSurface,
            onSurface        = White,
            surfaceVariant   = DarkCard,
            onSurfaceVariant = OffWhite,
            secondary        = accentColor,
            onSecondary      = onAccentColor,
        )
    } else {
        lightColorScheme(
            primary          = accentColor,
            onPrimary        = onAccentColor,
            background       = LightBackground,
            onBackground     = LightText,
            surface          = LightSurface,
            onSurface        = LightText,
            surfaceVariant   = LightCard,
            onSurfaceVariant = LightSubtext,
            secondary        = accentColor,
            onSecondary      = onAccentColor,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
