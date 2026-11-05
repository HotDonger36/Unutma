package com.example.unutma.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.unutma.Note
import com.example.unutma.data.Cal
import com.example.unutma.data.CalEvent
import com.example.unutma.data.CalInfo
import com.example.unutma.data.Repo
import com.example.unutma.data.Routines
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import com.example.unutma.util.Fmt
import com.example.unutma.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.focusRequester

private fun dayName(day: Long, today: Long): String {
    val ms = Routines.startOf(day)
    val base = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    return when (day) {
        today -> "Today · $base"
        today + 1 -> "Tomorrow · $base"
        else -> base
    }
}

/**
 * Events for [days] days from [fromDay], reloaded whenever [version] changes and when you come
 * back to the app (so events added in Google Calendar show up).
 */
@Composable
fun rememberEvents(fromDay: Long, days: Int, version: Int): List<CalEvent> {
    val ctx = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose { } }
    val events by produceState(emptyList<CalEvent>(), fromDay, days, version, resumed) {
        value = withContext(Dispatchers.IO) { Cal.events(ctx, Routines.startOf(fromDay), Routines.startOf(fromDay + days)) }
    }
    return events
}

/** The coloured ring used for calendar events (notes use a filled dot). */
@Composable
private fun EventRing(color: Color) {
    Box(Modifier.size(8.dp).border(2.dp, color, CircleShape))
}

// ─── Today block on the notes screen ─────────────────────────────────────────

/** Above the labels: what's on today, and a link to the Calendar tab. Only shown once the calendar is connected. */
@Composable
fun TodayBlock(version: Int, onOpenCalendar: () -> Unit, onOpenNote: (Int) -> Unit) {
    val ctx = LocalContext.current
    if (!Cal.canRead(ctx)) return
    val today = Routines.today()
    val week = rememberEvents(today, 7, version)
    val now = System.currentTimeMillis()
    val todays = week.filter { Routines.dayOf(it.begin) <= today && (it.end > now || it.allDay) }
    val later = week.count { Routines.dayOf(it.begin) > today }
    if (todays.isEmpty() && later == 0) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(UI.Calendar, null, tint = C.fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(8.dp))
            Text(dayName(today, today).uppercase(), style = Ty.section, color = C.fg, modifier = Modifier.weight(1f))
            Row(Modifier.clickable(onClickLabel = "Open calendar", onClick = onOpenCalendar), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = Ty.rowSub, color = C.muted)
                Icon(UI.Chevron, null, tint = C.muted, modifier = Modifier.size(12.dp))
            }
        }
        HDivider(C.faint)
        if (todays.isEmpty()) Text("Nothing else today.", style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(vertical = 12.dp))
        todays.take(3).forEach { e -> EventRow(e, compact = true, onOpenNote = onOpenNote) }
        val more = (todays.size - 3).coerceAtLeast(0) + later
        if (more > 0) Text(
            "+ $more more this week", style = Ty.rowSub, color = C.muted,
            modifier = Modifier.padding(top = 10.dp).clickable(onClick = onOpenCalendar),
        )
    }
}

@Composable
private fun EventRow(e: CalEvent, compact: Boolean, onOpenNote: (Int) -> Unit) {
    val ctx = LocalContext.current
    val color = Color(e.color or 0xFF000000.toInt())
    val linked = remember(e.eventId) { Cal.linkedNote(ctx, e.eventId) }
    val soon = Cal.soonText(e)
    Column(Modifier.fillMaxWidth().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 14.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!compact) Column(Modifier.width(48.dp)) {
                Text(if (e.allDay) "all day" else Fmt.time(e.begin), style = Ty.monoValue, color = C.fg)
                if (!e.allDay) Text(Fmt.time(e.end), style = Ty.mono, color = C.faint)
            } else Box(Modifier.padding(top = 7.dp)) { EventRing(color) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!compact) EventRing(color)
                    Text(e.title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val sub = listOfNotNull(if (compact) Cal.timeText(e) else null, e.calendarName.ifBlank { null }, e.location).joinToString(" · ")
                if (linked != null) Row(
                    Modifier.clickable(onClickLabel = "Open the linked note") { onOpenNote(linked) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(UI.Text, null, tint = C.accent, modifier = Modifier.size(12.dp))
                    Text("Linked note", style = Ty.rowSub, color = C.accent)
                } else if (sub.isNotEmpty()) Text(sub, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (soon != null) Text(soon, style = Ty.mono, color = color, modifier = Modifier.padding(top = 3.dp))
            else if (compact && !e.allDay) Text(Fmt.time(e.begin), style = Ty.mono, color = C.muted, modifier = Modifier.padding(top = 3.dp))
        }
        HDivider()
    }
}

// ─── Calendar tab ────────────────────────────────────────────────────────────

/**
 * Seven days to pick from, then the agenda from the picked day. Swipe an event to the left to
 * delete it; + adds one.
 */
@Composable
fun CalendarScreen(notes: List<Note>, onOpenNote: (Int) -> Unit, openNewEvent: Boolean = false, onNewEventShown: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Cal.canRead(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r[Manifest.permission.READ_CALENDAR] == true
        if (granted) Widgets.refreshAll(ctx) // calendar widgets can show events now
    }
    var version by remember { mutableIntStateOf(0) }
    val today = Routines.today()
    var picked by remember { mutableLongStateOf(today) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CalEvent?>(null) }
    LaunchedEffect(openNewEvent, granted) { if (openNewEvent && granted) { creating = true; onNewEventShown() } }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = Ty.screenTitle, color = C.fg, modifier = Modifier.weight(1f))
                Text(SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(Routines.startOf(picked))).uppercase(), style = Ty.mono, color = C.muted)
            }
            if (!granted) {
                Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "See your Google Calendar next to your notes, and add or delete events from here.\nUnutma reads the calendars already on your phone; nothing is sent anywhere.",
                        style = Ty.rowSub, color = C.muted, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    TextButtonU("Connect calendar", { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
                }
                return@Box
            }
            // Seven days, starting today.
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val dots = rememberEvents(today, 7, version).groupBy { Routines.dayOf(it.begin) }
                for (i in 0..6) {
                    val d = today + i
                    val on = d == picked
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.accent else Color.Transparent)
                            .clickable(role = Role.Tab) { picked = d }.semantics { contentDescription = dayName(d, today) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(Routines.DAY_NAMES[Routines.weekday(d) - 1].uppercase(), style = Ty.mono, color = if (on) C.bg else C.muted)
                        Text(SimpleDateFormat("d", Locale.getDefault()).format(Date(Routines.startOf(d))), style = Ty.monoValue, color = if (on) C.bg else C.fg)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp).padding(top = 2.dp)) {
                            dots[d].orEmpty().map { it.color }.distinct().take(3).forEach { col ->
                                Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) C.bg else Color(col or 0xFF000000.toInt())))
                            }
                        }
                    }
                }
            }
            val events = rememberEvents(picked, 14, version)
            val byDay = events.groupBy { Routines.dayOf(it.begin).coerceAtLeast(picked) }
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing in the next two weeks.", style = Ty.rowSub, color = C.muted)
                }
            } else LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 120.dp)) {
                byDay.toSortedMap().forEach { (day, list) ->
                    item(key = "d$day") {
                        Text(dayName(day, today).uppercase(), style = Ty.section, color = C.fg, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        HDivider(C.faint)
                    }
                    items(list, key = { "e${it.eventId}_${it.begin}" }) { e ->
                        SwipeToDelete(onDelete = { deleting = e }) { EventRow(e, compact = false, onOpenNote = onOpenNote) }
                    }
                }
            }
        }
        if (granted) Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 32.dp).size(56.dp).clip(CircleShape).background(C.bg)
                .border(1.dp, C.accent, CircleShape).clickable(role = Role.Button) { creating = true }
                .semantics { contentDescription = "New event" },
            contentAlignment = Alignment.Center,
        ) { Icon(UI.Plus, null, tint = C.accent, modifier = Modifier.size(22.dp)) }
    }

    if (creating) NewEventSheet(notes, picked, onDismiss = { creating = false }, onSaved = { version++; Widgets.refreshAll(ctx) })
    deleting?.let { e ->
        ConfirmDialog(
            "Delete “${e.title}”?",
            if (e.repeats) "This deletes every repeat of the event from ${e.calendarName.ifBlank { "your calendar" }}." else "It's removed from ${e.calendarName.ifBlank { "your calendar" }}.",
            "Delete",
            {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Cal.delete(ctx, e.eventId) }
                    if (!ok) android.widget.Toast.makeText(ctx, "Couldn't delete that event (the calendar may be read-only).", android.widget.Toast.LENGTH_LONG).show()
                    version++; Widgets.refreshAll(ctx)
                }
            },
            { deleting = null },
        )
    }
}

/** Swipe the row to the left past the red "Delete" to delete it (it asks first). Screen readers get a Delete action. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val limit = with(density) { 92.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (dragging) offset else 0f, label = "swipe")
    Box(
        Modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
        },
    ) {
        Row(Modifier.matchParentSize().background(C.danger), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(UI.Trash, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Delete", style = Ty.rowSub, color = Color.White)
            }
        }
        Box(
            Modifier.graphicsLayer { translationX = shown }.draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> offset = (offset + d).coerceIn(-limit * 1.5f, 0f) },
                onDragStarted = { dragging = true },
                onDragStopped = { if (offset < -limit) onDelete(); offset = 0f; dragging = false },
            ),
        ) { content() }
    }
}

// ─── New event ───────────────────────────────────────────────────────────────

private val REMINDERS = listOf<Pair<Int?, String>>(null to "None", 0 to "At the start", 10 to "10 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before")
private val REPEATS = listOf("NONE" to "Never", "DAILY" to "Every day", "WEEKLY" to "Every week", "MONTHLY" to "Every month", "YEARLY" to "Every year")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewEventSheet(notes: List<Note>, day: Long, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val calendars by produceState(emptyList<CalInfo>()) { value = withContext(Dispatchers.IO) { Cal.calendars(ctx).filter { it.writable } } }
    var title by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    val firstStart = remember {
        val next = Cal.nextHour()
        if (day == Routines.today()) next else Routines.startOf(day) + 9 * 60 * 60_000L
    }
    var start by remember { mutableLongStateOf(firstStart) }
    var end by remember { mutableLongStateOf(firstStart + 60 * 60_000L) }
    var calId by remember { mutableStateOf<Long?>(null) }
    var reminder by remember { mutableStateOf<Int?>(30) }
    var repeat by remember { mutableStateOf("NONE") }
    var noteId by remember { mutableStateOf<Int?>(null) }
    var picker by remember { mutableStateOf("") } // which sub-dialog is open
    val cal = calendars.firstOrNull { it.id == calId } ?: calendars.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.menuBg, contentColor = C.fg,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButtonU("Cancel", onDismiss, color = C.muted)
                Text("New event", style = Ty.rowTitle, color = C.fg, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButtonU("Save", {
                    val c = cal ?: return@TextButtonU
                    val name = title.trim().ifEmpty { "New event" }
                    Repo.scope.launch {
                        val id = Cal.insert(ctx, c.id, name, start, end, allDay, reminder, repeat)
                        if (id != null && noteId != null) Cal.setLinkedNote(ctx, id, noteId)
                        withContext(Dispatchers.Main) {
                            if (id == null) android.widget.Toast.makeText(ctx, "Couldn't add the event.", android.widget.Toast.LENGTH_LONG).show()
                            onSaved(); onDismiss()
                        }
                    }
                }, enabled = cal != null)
            }
            UField(title, { title = it }, "Event title", Modifier.fillMaxWidth().padding(vertical = 8.dp), style = Ty.editorTitle,
                fieldModifier = Modifier.focusRequester(rememberAutoFocus()))
            HDivider(C.accent)
            SheetSwitchRow("All day", allDay) { allDay = it }
            SheetValueRow("Starts", if (allDay) Fmt.date(start) else Fmt.dateTime(start)) { picker = "start" }
            SheetValueRow("Ends", if (allDay) Fmt.date(end) else Fmt.dateTime(end)) { picker = "end" }
            SheetValueRow("Google calendar", cal?.name ?: "No calendar you can add to") { if (calendars.size > 1) picker = "cal" }
            SheetValueRow("Reminder", REMINDERS.first { it.first == reminder }.second) { picker = "reminder" }
            SheetValueRow("Repeat", REPEATS.first { it.first == repeat }.second) { picker = "repeat" }
            SheetValueRow("Linked note", noteId?.let { id -> notes.firstOrNull { it.id == id }?.let { noteDisplayTitle(it) } } ?: "None") { picker = "note" }
        }
    }

    when (picker) {
        "start" -> DateTimePicker(start, withTime = !allDay, onPicked = { at ->
            val len = end - start
            start = at; end = at + len.coerceAtLeast(if (allDay) 0 else 15 * 60_000L); picker = ""
        }, onDismiss = { picker = "" })
        "end" -> DateTimePicker(end, withTime = !allDay, onPicked = { at -> end = at.coerceAtLeast(start); picker = "" }, onDismiss = { picker = "" })
        "cal" -> OptionDialog("Calendar", calendars.map { it.id to (it.name + if (it.account.isNotBlank() && it.account != it.name) " · ${it.account}" else "") },
            cal?.id, { calId = it }, { picker = "" })
        "reminder" -> OptionDialog("Reminder", REMINDERS, reminder, { reminder = it }, { picker = "" })
        "repeat" -> OptionDialog("Repeat", REPEATS, repeat, { repeat = it }, { picker = "" })
        "note" -> OptionDialog("Linked note", listOf<Pair<Int?, String>>(null to "None") + notes.sortedByDescending { it.updatedAt }.take(40).map { it.id to noteDisplayTitle(it) },
            noteId, { noteId = it }, { picker = "" })
    }
}

@Composable
private fun SheetValueRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Text(value, style = Ty.monoValue, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            Spacer(Modifier.width(6.dp))
            Icon(UI.Chevron, null, tint = C.faint, modifier = Modifier.size(14.dp))
        }
        HDivider()
    }
}

@Composable
private fun SheetSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            MiniSwitch(checked, onChange, title)
        }
        HDivider()
    }
}
