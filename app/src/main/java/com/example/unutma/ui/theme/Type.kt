package com.example.unutma.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.unutma.R

/** Geist for text, Geist Mono for the name, counts, dates and times — bundled in res/font. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_light, FontWeight.Light),
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/** Text styles taken from the final design. */
object Ty {
    /** "UNUTMA": thin, widely spaced monospace capitals. */
    val logo = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Light, fontSize = 22.sp, letterSpacing = 0.5.em)
    val section = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.1.em)
    val settingsHeader = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.1.em)
    val tab = TextStyle(fontFamily = Geist, fontSize = 14.sp)
    val rowTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val rowSub = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
    val mono = TextStyle(fontFamily = GeistMono, fontSize = 12.sp)
    val monoSmall = TextStyle(fontFamily = GeistMono, fontSize = 11.sp)
    val monoValue = TextStyle(fontFamily = GeistMono, fontSize = 13.sp)
    val screenTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = (-0.2).sp)
    val editorTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 26.sp, letterSpacing = (-0.3).sp, lineHeight = 32.sp)
    val body = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 26.sp)
    val row = TextStyle(fontFamily = Geist, fontSize = 15.sp)
    val menu = TextStyle(fontFamily = Geist, fontSize = 15.sp)
    val button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp)
}

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Geist),
    displayMedium = base.displayMedium.copy(fontFamily = Geist),
    displaySmall = base.displaySmall.copy(fontFamily = Geist),
    headlineLarge = base.headlineLarge.copy(fontFamily = Geist),
    headlineMedium = base.headlineMedium.copy(fontFamily = Geist),
    headlineSmall = base.headlineSmall.copy(fontFamily = Geist),
    titleLarge = base.titleLarge.copy(fontFamily = Geist),
    titleMedium = base.titleMedium.copy(fontFamily = Geist),
    titleSmall = base.titleSmall.copy(fontFamily = Geist),
    bodyLarge = base.bodyLarge.copy(fontFamily = Geist, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Geist, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Geist),
    labelLarge = base.labelLarge.copy(fontFamily = Geist),
    labelMedium = base.labelMedium.copy(fontFamily = Geist),
    labelSmall = base.labelSmall.copy(fontFamily = Geist),
)
