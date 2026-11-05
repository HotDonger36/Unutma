package com.example.unutma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unutma.Label
import com.example.unutma.Note
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_NONE
import com.example.unutma.RESET_WEEKLY
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.Routines
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.stripMarkdown
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.labelColor

/** Everything a routine row needs, worked out once per note change. */
data class RoutineInfo(
    val note: Note,
    val title: String,
    val color: Color,
    val nextIndex: Int,        // index of the next box to tick (among boxes with text), -1 = none
    val nextText: String?,
    val done: Int,
    val total: Int,
    val dueToday: Boolean,
    val streak: Int,
    val best: Int,
    val week: List<Routines.DaySquare>,
)

@Composable
fun rememberRoutines(notes: List<Note>, labels: List<Label>): List<RoutineInfo> {
    val ctx = LocalContext.current
    val accent = C.accent
    val labelColors = labels.associate { it.id to labelColor(it.color) }
    return remember(notes, labels, accent) {
        val today = Routines.today()
        notes.filter { Routines.isRoutine(it) && parseBlocks(it).any { b -> b is CheckBlock } }
            .sortedBy { noteDisplayTitle(it).lowercase() }
            .map { n ->
                val checks = parseBlocks(n).filterIsInstance<CheckBlock>().filter { it.text.isNotBlank() }
                val next = checks.indexOfFirst { !it.checked }
                val hist = Routines.history(ctx, n.id)
                RoutineInfo(
                    note = n,
                    title = noteDisplayTitle(n),
                    color = n.labelId?.let { labelColors[it] } ?: accent,
                    nextIndex = if (n.isLocked) -1 else next,
                    nextText = if (n.isLocked || next < 0) null else stripMarkdown(checks[next].text).trim(),
                    done = checks.count { it.checked },
                    total = checks.size,
                    dueToday = n.resetMode == RESET_WEEKLY || Routines.isScheduled(n.resetMode, today),
                    streak = Routines.streak(n.resetMode, hist, today),
                    best = Routines.best(ctx, n),
                    week = Routines.week(n.resetMode, hist, today),
                )
            }
    }
}

/**
 * Monday to Sunday. Each square fills from the bottom to the share of boxes ticked that day:
 * see-through while unfinished, solid once finished. Today has a coloured border; days the
 * routine isn't due show a small dot.
 */
@Composable
fun WeekStrip(week: List<Routines.DaySquare>, color: Color, modifier: Modifier = Modifier, square: androidx.compose.ui.unit.Dp = 18.dp) {
    val track = C.track
    val described = week.joinToString(", ") { sq ->
        Routines.DAY_NAMES[Routines.weekday(sq.day) - 1] + " " + when {
            !sq.scheduled -> "not due"
            sq.future -> "to come"
            else -> "${sq.percent}%"
        }
    }
    Row(modifier.semantics { contentDescription = "This week: $described" }, horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEach { sq ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(square)) {
                    val r = CornerRadius(4.dp.toPx())
                    if (!sq.scheduled) {
                        drawCircle(track, radius = 1.6.dp.toPx(), center = center)
                        return@Canvas
                    }
                    if (sq.percent > 0 && !sq.future) {
                        val top = size.height * (1 - sq.percent / 100f)
                        clipRect(top = top) {
                            drawRoundRect(color.copy(alpha = if (sq.percent >= 100) 1f else 0.4f), cornerRadius = r)
                        }
                    }
                    val sw = (if (sq.isToday) 1.6.dp else 1.dp).toPx()
                    drawRoundRect(
                        if (sq.isToday || sq.percent >= 100) color else track,
                        topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                        cornerRadius = r, style = Stroke(sw),
                    )
                }
                Text(
                    Routines.DAY_NAMES[Routines.weekday(sq.day) - 1].take(1),
                    style = Ty.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = if (sq.isToday) C.fg else C.faint,
                )
            }
        }
    }
}

/** A small tickable box for "the next task" (routines tab, home card). */
@Composable
private fun TickBox(color: Color, description: String, onTick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox, onClick = onTick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(16.dp).border(1.dp, color, RoundedCornerShape(4.dp))) }
}

private fun streakText(r: RoutineInfo): String {
    val unit = if (r.note.resetMode == RESET_WEEKLY) "week" else "day"
    val s = if (r.streak == 1) "1 $unit" else "${r.streak} ${unit}s"
    return if (r.best > r.streak) "$s · best ${r.best}" else s
}

// ─── Routines tab ────────────────────────────────────────────────────────────

@Composable
fun RoutinesScreen(routines: List<RoutineInfo>, onOpen: (Note) -> Unit, onTick: (Note, Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Text("Routines", style = Ty.screenTitle, color = C.fg, modifier = Modifier.padding(start = 24.dp, top = 30.dp, bottom = 6.dp))
        Text(
            "Checklists that untick themselves on a schedule. Finish every box on the days they're due to grow the streak.",
            style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (routines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No routines yet.\nOpen a checklist, tap Details › Repeat, and choose when it should come back.",
                    style = Ty.rowSub, color = C.muted, textAlign = TextAlign.Center,
                )
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 40.dp)) {
            items(routines, key = { it.note.id }) { r ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.note) }.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dot(r.color, 8.dp)
                        Text(r.title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(streakText(r), style = Ty.mono, color = r.color)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            r.note.isLocked -> Text("Locked", style = Ty.rowSub, color = C.muted)
                            r.nextText != null -> {
                                TickBox(r.color, "Tick ${r.nextText}") { onTick(r.note, r.nextIndex) }
                                Text(r.nextText, style = Ty.row, color = C.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            else -> Text(if (r.dueToday) "All done" else "Not due today", style = Ty.rowSub, color = if (r.dueToday) r.color else C.muted)
                        }
                        Spacer(Modifier.weight(0.01f))
                        Text("${r.done}/${r.total}", style = Ty.mono, color = C.muted)
                    }
                    WeekStrip(r.week, r.color, Modifier.fillMaxWidth())
                    Text(Routines.describe(r.note.resetMode), style = Ty.mono, color = C.faint)
                }
                HDivider()
            }
        }
    }
}

// ─── Daily card on the notes screen ──────────────────────────────────────────

/**
 * Above the labels: today's unfinished routines with their next task (tick it right there) and
 * streak. Tapping the card opens the Routines tab.
 */
@Composable
fun RoutineHomeCard(routines: List<RoutineInfo>, onTick: (Note, Int) -> Unit, onOpenTab: () -> Unit, modifier: Modifier = Modifier) {
    val due = routines.filter { it.dueToday }
    if (due.isEmpty()) return
    val open = due.filter { it.nextText != null }
    val best = due.maxByOrNull { it.streak }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.menuBorder, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Open routines", onClick = onOpenTab).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY", style = Ty.section, color = C.fg, modifier = Modifier.weight(1f))
            if (best != null && best.streak > 0) Text("${best.streak} day streak", style = Ty.mono, color = best.color)
            Icon(UI.Chevron, null, tint = C.faint, modifier = Modifier.padding(start = 6.dp).size(12.dp))
        }
        if (open.isEmpty()) {
            Text("Every routine is done for today.", style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(vertical = 6.dp))
        } else open.take(3).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickBox(r.color, "Tick ${r.nextText}") { onTick(r.note, r.nextIndex) }
                Column(Modifier.weight(1f)) {
                    Text(r.nextText!!, style = Ty.row, color = C.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.title, style = Ty.mono, color = C.muted, maxLines = 1)
                }
                Text("${r.done}/${r.total}", style = Ty.mono, color = r.color)
            }
        }
    }
}

// ─── Picking a schedule ──────────────────────────────────────────────────────

/** Details › Repeat: off, every day, every week, on chosen weekdays, or every N days. */
@Composable
fun RoutineDialog(mode: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(when {
        mode.startsWith("DAYS:") -> "DAYS"; mode.startsWith("EVERY:") -> "EVERY"; else -> mode
    }) }
    var days by remember { mutableStateOf(Routines.weekdays(mode).ifEmpty { setOf(1, 2, 3, 4, 5) }) }
    var every by remember { mutableIntStateOf(Routines.every(mode)?.first ?: 2) }
    UDialog(onDismiss, "Repeat") {
        Text(
            "A routine unticks its boxes when it's due again, and keeps a streak of the days you finish it.",
            style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        listOf(RESET_NONE to "Off", RESET_DAILY to "Every day", RESET_WEEKLY to "Every week (unticks on Monday)", "DAYS" to "On chosen days", "EVERY" to "Every few days")
            .forEach { (k, name) -> DialogRow(name, { kind = k }, if (kind == k) UI.Tick else null, iconTint = C.accent) }
        if (kind == "DAYS") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (d in 1..7) {
                val on = d in days
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (on) C.accent else Color.Transparent)
                        .border(1.dp, if (on) C.accent else C.menuBorder, CircleShape)
                        .clickable(role = Role.Checkbox) { days = if (on) days - d else days + d }
                        .semantics { contentDescription = Routines.DAY_NAMES[d - 1] + if (on) ", on" else ", off" },
                    contentAlignment = Alignment.Center,
                ) { Text(Routines.DAY_NAMES[d - 1].take(2), style = Ty.mono, color = if (on) C.bg else C.muted) }
            }
        }
        if (kind == "EVERY") Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Every", style = Ty.row, color = C.fg)
            IconBtn(UI.Minus, "Fewer days", { every = (every - 1).coerceAtLeast(2) }, iconSize = 16.dp)
            Text("$every", style = Ty.monoValue, color = C.fg)
            IconBtn(UI.PlusBold, "More days", { every = (every + 1).coerceAtMost(30) }, iconSize = 16.dp)
            Text("days, from today", style = Ty.row, color = C.fg)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            TextButtonU("Cancel", onDismiss, color = C.muted)
            TextButtonU("Save", {
                val picked = when (kind) {
                    "DAYS" -> if (days.isEmpty()) RESET_NONE else Routines.weekdaysMode(days)
                    "EVERY" -> if (mode.startsWith("EVERY:") && Routines.every(mode)?.first == every) mode else Routines.everyMode(every)
                    else -> kind
                }
                onPick(picked); onDismiss()
            })
        }
    }
}
