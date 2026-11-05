package com.example.unutma.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The thin-stroke icons from the final design, drawn from the same SVG paths
 * (24 × 24 grid). They are tinted by whatever colour the Icon gets.
 */
object UI {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx},${y}h${w - 2 * rx}a$rx,$rx 0 0 1 $rx,${rx}v${h - 2 * rx}a$rx,$rx 0 0 1 ${-rx},${rx}" +
            "h${-(w - 2 * rx)}a$rx,$rx 0 0 1 ${-rx},${-rx}v${-(h - 2 * rx)}a$rx,$rx 0 0 1 $rx,${-rx}z"

    private fun icon(name: String, stroke: Float, strokes: List<String>, fills: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in strokes) addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            for (d in fills) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()

    val Search = icon("search", 1.4f, listOf(circle(11f, 11f, 7f), "M20 20l-3.5-3.5"))
    val More = icon("more", 1f, emptyList(), listOf(circle(12f, 5f, 1.5f), circle(12f, 12f, 1.5f), circle(12f, 19f, 1.5f)))
    val Plus = icon("plus", 1.4f, listOf("M12 5v14M5 12h14"))
    val Back = icon("back", 1.6f, listOf("M15 5l-7 7 7 7"))
    val Close = icon("close", 1.6f, listOf("M6 6l12 12M18 6L6 18"))
    val Undo = icon("undo", 1.6f, listOf("M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"))
    val Redo = icon("redo", 1.6f, listOf("M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3"))
    // Notification bar pin: a phone with a notification card at the top, so it doesn't look
    // like the push-pin used for "Pin to top". The bold one (pinned) has the card filled in.
    val PinBar = icon("pinbar", 1.6f, listOf(rect(5f, 2f, 14f, 20f, 2.5f), rect(8f, 5.5f, 8f, 4f, 1f), "M10.5 18.5h3"))
    val PinBarBold = icon("pinbarbold", 2f, listOf(rect(5f, 2f, 14f, 20f, 2.5f), "M10.5 18.5h3"), listOf(rect(7.5f, 5f, 9f, 5f, 1.2f)))
    val Bell = icon("bell", 1.6f, listOf("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10 21h4"))
    val BellBold = icon("bellbold", 2.4f, listOf("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10 21h4"))
    val SelectAll = icon("selectall", 1.6f, listOf(rect(8f, 8f, 12f, 12f, 2f), "M11 14l2 2 4-4", "M4 15V6a2 2 0 0 1 2-2h9"))
    val Checkbox = icon("checkbox", 1.6f, listOf(rect(4f, 4f, 16f, 16f, 2f), "M8.5 12l2.5 2.5 4.5-5"))
    val Image = icon("image", 1.6f, listOf(rect(3f, 5f, 18f, 14f, 2f), "M21 16l-5-5-8 8"))
    val Mic = icon("mic", 1.6f, listOf(rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3"))
    val Sliders = icon("sliders", 1.6f, listOf("M4 8h3M11 8h9M4 16h9M17 16h3", circle(9f, 8f, 2f), circle(15f, 16f, 2f)))
    val Chevron = icon("chevron", 2f, listOf("M9 6l6 6-6 6"))
    val External = icon("external", 2f, listOf("M7 17L17 7M9 7h8v8"))
    val Lock = icon("lock", 1.8f, listOf(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4"))
    val Unlock = icon("unlock", 1.8f, listOf(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 7.5-2"))
    val Drag = icon("drag", 1f, emptyList(), listOf(
        circle(9f, 6f, 1.6f), circle(15f, 6f, 1.6f), circle(9f, 12f, 1.6f),
        circle(15f, 12f, 1.6f), circle(9f, 18f, 1.6f), circle(15f, 18f, 1.6f),
    ))
    val Tag = icon("tag", 1.6f, listOf("M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z", circle(7.5f, 7.5f, 1.2f)))
    val Archive = icon("archive", 1.6f, listOf(rect(3f, 4f, 18f, 4f, 1f), "M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8", "M10 12h4"))
    val Trash = icon("trash", 1.6f, listOf("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"))
    val Share = icon("share", 1.6f, listOf("M12 4v12M7 9l5-5 5 5", "M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"))
    val Play = icon("play", 1f, emptyList(), listOf("M8 5v14l11-7z"))
    val Pause = icon("pause", 1f, emptyList(), listOf("M7 5h3v14H7zM14 5h3v14h-3z"))
    val Stop = icon("stop", 1f, emptyList(), listOf("M7 7h10v10H7z"))
    val TextScan = icon("textscan", 1.6f, listOf("M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3", "M8 9h8M12 9v7"))
    val Calendar = icon("calendar", 1.6f, listOf(rect(3f, 5f, 18f, 16f, 2f), "M3 10h18M8 3v4M16 3v4"))
    val Hourglass = icon("hourglass", 1.6f, listOf("M6 3h12M6 21h12", "M7 3c0 5 10 6 10 9s-10 4-10 9", "M17 3c0 5-10 6-10 9s10 4 10 9"))
    val Repeat = icon("repeat", 1.6f, listOf("M4 12a8 8 0 0 1 14-5.3L20 9", "M20 4v5h-5", "M20 12a8 8 0 0 1-14 5.3L4 15", "M4 20v-5h5"))
    val Link = icon("link", 1.6f, listOf("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"))
    val Numbered = icon("numbered", 1.6f, listOf("M10 6h10M10 12h10M10 18h10", "M4 5h1.5v4", "M3.8 15.2a1.3 1.3 0 0 1 2.3.9c0 1-2.3 1.6-2.3 2.9h2.4"))
    val Indent = icon("indent", 1.6f, listOf("M4 6h16M10 12h10M10 18h10", "M4 10l3 2-3 2"))
    val Outdent = icon("outdent", 1.6f, listOf("M4 6h16M10 12h10M10 18h10", "M7 10l-3 2 3 2"))
    val Minus = icon("minus", 1.8f, listOf("M5 12h14"))
    val PlusBold = icon("plusbold", 1.8f, listOf("M12 5v14M5 12h14"))
    val Tick = icon("tick", 2f, listOf("M5 12l5 5 9-10"))
    val Restore = icon("restore", 1.6f, listOf("M4 12a8 8 0 1 0 2.3-5.7L4 8.6", "M4 4v4.6h4.6"))
    val PinTop = icon("pintop", 1.6f, listOf("M12 17v4", "M8 3h8l-1 6 3 3v2H6v-2l3-3z"))
    val Text = icon("text", 1.6f, listOf("M5 6h14M5 12h14M5 18h9"))
    val Copy = icon("copy", 1.6f, listOf(rect(8f, 8f, 12f, 12f, 2f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"))
}
