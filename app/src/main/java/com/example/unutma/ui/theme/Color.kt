package com.example.unutma.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A label / accent colour, with a deeper shade so it stays readable on white. */
data class Swatch(val key: String, val name: String, val dark: Color, val light: Color) {
    fun on(isDark: Boolean) = if (isDark) dark else light
}

val LABEL_COLOURS: List<Swatch> = listOf(
    Swatch("PURPLE", "Purple", Color(0xFFA98BFF), Color(0xFF6B4FD8)),
    Swatch("AMBER", "Amber", Color(0xFFF2B84B), Color(0xFF9A6400)),
    Swatch("GREEN", "Green", Color(0xFF6DD3A0), Color(0xFF1C7F51)),
    Swatch("BLUE", "Blue", Color(0xFF6CB4FF), Color(0xFF1F67C2)),
    Swatch("CORAL", "Coral", Color(0xFFFF8C7A), Color(0xFFBC3F2B)),
    Swatch("PINK", "Pink", Color(0xFFF28BBD), Color(0xFFB2326C)),
    Swatch("TEAL", "Teal", Color(0xFF5FD0D0), Color(0xFF0F7A7A)),
    Swatch("GREY", "Grey", Color(0xFFA8A8A8), Color(0xFF626262)),
)

/** Accent colours offered in Settings (the first six label colours). */
val ACCENTS: List<Swatch> = LABEL_COLOURS.take(6)

fun swatch(key: String?): Swatch = LABEL_COLOURS.firstOrNull { it.key == key } ?: LABEL_COLOURS[0]

/** Every colour the screens use, so dark and light mode stay consistent with the design. */
@Immutable
data class UnutmaColors(
    val isDark: Boolean,
    val bg: Color,
    val fg: Color,          // main text
    val body: Color,        // note body text
    val logo: Color,        // "UNUTMA" and the FAB icon
    val muted: Color,       // secondary text
    val faint: Color,       // disabled icons, placeholders
    val icon: Color,        // header icons
    val divider: Color,
    val menuBg: Color,
    val menuBorder: Color,
    val selected: Color,    // selected segment
    val done: Color,        // ticked-off text
    val track: Color,       // empty progress ring
    val fabBorder: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
)

fun darkColors(accent: Color) = UnutmaColors(
    isDark = true,
    bg = Color(0xFF000000), fg = Color(0xFFEDEDED), body = Color(0xFFD4D4D4), logo = Color(0xFFE2E2E2),
    muted = Color(0xFF8A8A8A), faint = Color(0xFF5A5A5A), icon = Color(0xFFA8A8A8),
    divider = Color(0xFF1E1E1E), menuBg = Color(0xFF111111), menuBorder = Color(0xFF262626),
    selected = Color(0xFF1C1C1C), done = Color(0xFF6B6B6B), track = Color(0xFF333333),
    fabBorder = Color(0xFFCFCFCF), accent = accent, onAccent = Color(0xFF0A0A0A), danger = Color(0xFFFF8C7A),
)

fun lightColors(accent: Color) = UnutmaColors(
    isDark = false,
    bg = Color(0xFFFFFFFF), fg = Color(0xFF141414), body = Color(0xFF2A2A2A), logo = Color(0xFF1C1C1C),
    muted = Color(0xFF6B6B6B), faint = Color(0xFFA6A6A6), icon = Color(0xFF555555),
    divider = Color(0xFFE6E6E6), menuBg = Color(0xFFFFFFFF), menuBorder = Color(0xFFE0E0E0),
    selected = Color(0xFFF0F0F0), done = Color(0xFF9E9E9E), track = Color(0xFFDDDDDD),
    fabBorder = Color(0xFF2A2A2A), accent = accent, onAccent = Color(0xFFFFFFFF), danger = Color(0xFFBC3F2B),
)

val LocalUnutmaColors = staticCompositionLocalOf { darkColors(LABEL_COLOURS[0].dark) }

/** Short alias used throughout the UI: `C.fg`, `C.muted`… */
val C: UnutmaColors
    @Composable get() = LocalUnutmaColors.current

/** A label's colour in the current mode. */
@Composable
fun labelColor(key: String?): Color = swatch(key).on(LocalUnutmaColors.current.isDark)
