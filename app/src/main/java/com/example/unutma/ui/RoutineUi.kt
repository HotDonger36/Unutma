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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * A row of day squares (a week, Monday first). Each square fills from the bottom to the share of boxes
 * ticked that day: see-through while unfinished, solid once finished. Today has a coloured border;
 * days the routine isn't due show a small dot. Under each square: the weekday letter and the date.
 * With [onDay], tapping a day opens its details.
 */
@Composable
fun WeekStrip(
    week: List<Routines.DaySquare>,
    color: Color,
    modifier: Modifier = Modifier,
    square: androidx.compose.ui.unit.Dp = 18.dp,
    onDay: ((Routines.DaySquare) -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEach { sq ->
            Column(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .then(if (onDay != null) Modifier.clickable(role = Role.Button) { onDay(sq) } else Modifier)
                    .semantics { contentDescription = daySummary(sq) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                DaySquareBox(sq, color, square)
                Text(
                    Routines.DAY_NAMES[Routines.weekday(sq.day) - 1].take(1),
                    style = Ty.mono.copy(fontSize = 9.sp),
                    color = if (sq.isToday) C.fg else C.faint,
                )
                Text(dateOf(sq.day), style = Ty.mono.copy(fontSize = 10.sp), color = if (sq.isToday) C.fg else C.muted)
            }
        }
    }
}

private fun dateOf(day: Long) = SimpleDateFormat("d", Locale.UK).format(Date(Routines.startOf(day)))

private fun daySummary(sq: Routines.DaySquare): String =
    SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(Routines.startOf(sq.day))) + ": " + when {
        !sq.scheduled -> "not due"
        sq.future -> "to come"
        else -> "${sq.percent}% done"
    }

/** One day's square, filled to its %. */
@Composable
private fun DaySquareBox(sq: Routines.DaySquare, color: Color, size: androidx.compose.ui.unit.Dp) {
    val track = C.track
    Canvas(Modifier.size(size)) {
        val r = CornerRadius(4.dp.toPx())
        if (!sq.scheduled) {
            drawCircle(track, radius = 1.6.dp.toPx(), center = center)
            return@Canvas
        }
        if (sq.percent > 0 && !sq.future) {
            val top = this.size.height * (1 - sq.percent / 100f)
            clipRect(top = top) {
                drawRoundRect(color.copy(alpha = if (sq.percent >= 100) 1f else 0.4f), cornerRadius = r)
            }
        }
        val sw = (if (sq.isToday) 1.6.dp else 1.dp).toPx()
        drawRoundRect(
            if (sq.isToday || sq.percent >= 100) color else track,
            topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
            cornerRadius = r, style = Stroke(sw),
        )
    }
}

/** A small tickable box for "the next task". */
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

/** What happened on a past day: its %, and which items were ticked. */
@Composable
private fun DayDetailsDialog(r: RoutineInfo, sq: Routines.DaySquare, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ticked = remember(r.note.id, sq.day) { Routines.ticked(ctx, r.note.id, sq.day) }
    UDialog(onDismiss, SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(Routines.startOf(sq.day)))) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DaySquareBox(sq, r.color, 28.dp)
                Text(
                    when {
                        !sq.scheduled -> "Not due this day"
                        sq.future -> "Still to come"
                        else -> "${sq.percent}% done"
                    },
                    style = Ty.rowTitle, color = C.fg,
                )
            }
            when {
                sq.future -> Unit
                ticked == null -> Text("Which items were ticked is only kept from version 1.2 on.", style = Ty.rowSub, color = C.muted)
                ticked.isEmpty() -> Text("Nothing was ticked.", style = Ty.rowSub, color = C.muted)
                else -> ticked.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(r.color))
                        Text(t, style = Ty.row, color = C.body)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            TextButtonU("Close", onDismiss)
        }
    }
}

// ─── Routines tab ────────────────────────────────────────────────────────────

@Composable
fun RoutinesScreen(routines: List<RoutineInfo>, onOpen: (Note) -> Unit, onTick: (Note, Int) -> Unit) {
    var dayFor by remember { mutableStateOf<Pair<RoutineInfo, Routines.DaySquare>?>(null) }
    var monthFor by remember { mutableStateOf<RoutineInfo?>(null) }
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
                // Swipe the week back to earlier weeks (0 = this week).
                var weeksBack by remember(r.note.id) { mutableIntStateOf(0) }
                val ctx = LocalContext.current
                val hist = remember(r.note) { Routines.history(ctx, r.note.id) }
                val monday = Routines.mondayOf(Routines.today()) - 7L * weeksBack
                val week = remember(r.note, weeksBack) { Routines.days(r.note.resetMode, hist, monday, 7) }
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.note) }.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dot(r.color, 8.dp)
                        Text(r.title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(streakText(r), style = Ty.mono, color = r.color)
                        IconBtn(UI.Calendar, "Month view", { monthFor = r }, tint = C.muted, iconSize = 16.dp)
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
                    SwipeWeeks(
                        weeksBack,
                        onChange = { weeksBack = it.coerceAtLeast(0) },
                        label = if (weeksBack == 0) "This week" else "Week of " + SimpleDateFormat("d MMM", Locale.UK).format(Date(Routines.startOf(monday))),
                    ) { WeekStrip(week, r.color, Modifier.fillMaxWidth(), onDay = { sq -> dayFor = r to sq }) }
                    Text(Routines.describe(r.note.resetMode), style = Ty.mono, color = C.faint)
                }
                HDivider()
            }
        }
    }
    dayFor?.let { (r, sq) -> DayDetailsDialog(r, sq) { dayFor = null } }
    monthFor?.let { r -> RoutineMonthSheet(r, onDay = { sq -> dayFor = r to sq }) { monthFor = null } }
}

/**
 * Wraps a strip you can swipe sideways: right for further back, left to come forward again. [page]
 * counts back from the current one. Screen readers get "Earlier" / "Later" actions.
 */
@Composable
private fun SwipeWeeks(page: Int, onChange: (Int) -> Unit, label: String, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val limit = with(density) { 56.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Ty.mono, color = C.muted, modifier = Modifier.weight(1f))
            if (page > 0) Text("Back to now", style = Ty.mono, color = C.accent, modifier = Modifier.clickable { onChange(0) })
        }
        Box(
            Modifier
                .graphicsLayer { translationX = dx / 3 }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> dx += d },
                    onDragStopped = {
                        if (dx > limit) onChange(page + 1) else if (dx < -limit) onChange(page - 1)
                        dx = 0f
                    },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Earlier") { onChange(page + 1); true },
                        CustomAccessibilityAction("Later") { onChange(page - 1); true },
                    )
                },
        ) { content() }
    }
}

/**
 * A month of the routine: every day a square filled to that day's %, Monday first. Swipe (or use
 * the arrows) for earlier months; tap a day for its details. Shows the best streak and the month's average.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineMonthSheet(r: RoutineInfo, onDay: (Routines.DaySquare) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var monthsBack by remember { mutableIntStateOf(0) }
    val hist = remember(r.note) { Routines.history(ctx, r.note.id) }
    val cal = remember(monthsBack) {
        Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -monthsBack) }
    }
    val first = Routines.dayOf(cal.apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis)
    val length = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val days = Routines.days(r.note.resetMode, hist, first, length)
    val avg = Routines.average(r.note.resetMode, hist, first until first + length)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.menuBg, contentColor = C.fg,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(r.color, 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(r.title, style = Ty.rowTitle, color = C.fg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(UI.Back, "Earlier month", { monthsBack++ }, tint = C.fg, iconSize = 16.dp)
                Text(
                    SimpleDateFormat("MMMM yyyy", Locale.UK).format(cal.time),
                    style = Ty.rowTitle, color = C.fg, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                IconBtn(UI.Chevron, "Later month", { monthsBack = (monthsBack - 1).coerceAtLeast(0) }, tint = C.fg, enabled = monthsBack > 0, iconSize = 16.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column { Text("BEST STREAK", style = Ty.settingsHeader, color = C.muted); Text("${r.best}", style = Ty.monoValue, color = r.color) }
                Column { Text("MONTH AVERAGE", style = Ty.settingsHeader, color = C.muted); Text(avg?.let { "$it%" } ?: "–", style = Ty.monoValue, color = r.color) }
            }
            SwipeWeeks(monthsBack, onChange = { monthsBack = it.coerceAtLeast(0) }, label = "Swipe for other months") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Routines.DAY_NAMES.forEach { n ->
                            Text(n.take(1), style = Ty.mono, color = C.faint, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        }
                    }
                    // Blank cells before the 1st, so every column is one weekday.
                    val lead = Routines.weekday(first) - 1
                    val cells: List<Routines.DaySquare?> = List(lead) { null } + days
                    cells.chunked(7).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            for (i in 0 until 7) {
                                val sq = row.getOrNull(i)
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    if (sq != null) Column(
                                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDay(sq) }
                                            .semantics { contentDescription = daySummary(sq) }.padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        DaySquareBox(sq, r.color, 24.dp)
                                        Text(dateOf(sq.day), style = Ty.mono.copy(fontSize = 10.sp), color = if (sq.isToday) C.fg else C.muted)
                                    }
                                }
                            }
                        }
                    }
                }
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
