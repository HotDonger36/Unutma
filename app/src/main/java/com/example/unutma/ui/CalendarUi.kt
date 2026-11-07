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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import com.example.unutma.notify.CalendarWatch
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

/** One event in the agenda: tap it to edit it; the linked note opens from its own line. */
@Composable
private fun EventRow(e: CalEvent, onOpenNote: (Int) -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val color = Color(e.color or 0xFF000000.toInt())
    val linked = remember(e.eventId) { Cal.linkedNote(ctx, e.eventId) }
    val soon = Cal.soonText(e)
    Column(Modifier.fillMaxWidth().background(C.bg).clickable(onClickLabel = "Edit event", onClick = onEdit)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(48.dp)) {
                Text(if (e.allDay) "all day" else Fmt.time(e.begin), style = Ty.monoValue, color = C.fg)
                if (!e.allDay) Text(Fmt.time(e.end), style = Ty.mono, color = C.faint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventRing(color)
                    Text(e.title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val sub = listOfNotNull(e.calendarName.ifBlank { null }, e.location).joinToString(" · ")
                if (linked != null) Row(
                    Modifier.clickable(onClickLabel = "Open the linked note") { onOpenNote(linked) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(UI.Text, null, tint = C.accent, modifier = Modifier.size(12.dp))
                    Text("Linked note", style = Ty.rowSub, color = C.accent)
                } else if (sub.isNotEmpty()) Text(sub, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (soon != null) Text(soon, style = Ty.mono, color = color, modifier = Modifier.padding(top = 3.dp))
        }
        HDivider()
    }
}

// ─── Calendar tab ────────────────────────────────────────────────────────────

/** Weeks you can swipe through: about 10 years back and forward from today. */
private const val WEEK_PAGES = 1041
private const val WEEK_MID = WEEK_PAGES / 2

/** How far ahead the agenda looks when no day is picked. */
private const val UPCOMING_DAYS = 30

/**
 * Seven days to pick from (swipe the strip for earlier or later weeks, tap the month to jump to a
 * date), then the picked day's agenda. Tap the picked day again to unselect it and see the next
 * 30 days instead. Tap an event to edit or delete it, or swipe it to the left to delete it; + adds
 * one on the picked day.
 */
@Composable
fun CalendarScreen(notes: List<Note>, onOpenNote: (Int) -> Unit, openNewEvent: Boolean = false, onNewEventShown: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Cal.canRead(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r[Manifest.permission.READ_CALENDAR] == true
        if (granted) { Widgets.refreshAll(ctx); CalendarWatch.schedule(ctx) } // calendar widgets can show events now
    }
    var version by remember { mutableIntStateOf(0) }
    val today = Routines.today()
    // The picked day, or null when you tap it again to unselect it: then the agenda shows the next 30 days.
    var picked by remember { mutableStateOf<Long?>(today) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CalEvent?>(null) }
    var editing by remember { mutableStateOf<CalEvent?>(null) }
    LaunchedEffect(openNewEvent, granted) { if (openNewEvent && granted) { creating = true; onNewEventShown() } }

    // The week strip is a pager you swipe left/right: page MID is the seven days from today,
    // each page before or after it is seven days earlier or later.
    val pager = rememberPagerState(initialPage = WEEK_MID) { WEEK_PAGES }
    fun weekStart(page: Int): Long = today + (page - WEEK_MID) * 7L
    fun pageOf(day: Long): Int = (WEEK_MID + Math.floorDiv(day - today, 7L).toInt()).coerceIn(0, WEEK_PAGES - 1)
    // After a swipe, keep the picked day if it's in the new week; otherwise pick the week's first day (today on the start page).
    // With no day picked, it stays unselected.
    LaunchedEffect(pager.settledPage) {
        val p = picked ?: return@LaunchedEffect
        val start = weekStart(pager.settledPage)
        if (p !in start until start + 7) picked = start
    }
    fun jumpTo(day: Long) {
        picked = day
        scope.launch { pager.animateScrollToPage(pageOf(day)) }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = Ty.screenTitle, color = C.fg, modifier = Modifier.weight(1f))
                if (granted && (picked != today || pager.currentPage != WEEK_MID)) {
                    Text(
                        "TODAY", style = Ty.mono, color = C.accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Go back to today") { jumpTo(today) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // Tap the month to jump to any date.
                Text(
                    SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(Routines.startOf(picked ?: weekStart(pager.currentPage)))).uppercase(),
                    style = Ty.mono, color = C.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = granted, onClickLabel = "Pick a date") {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = Routines.startOf(picked ?: weekStart(pager.currentPage)) }
                        android.app.DatePickerDialog(
                            ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert,
                            { _, y, m, d ->
                                val ms = java.util.Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0) }.timeInMillis
                                jumpTo(Routines.dayOf(ms))
                            },
                            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH),
                        ).show()
                    }.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            if (!granted) {
                Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "See your Google Calendar next to your notes, and add, change or delete events from here.\nUnutma reads the calendars already on your phone; nothing is sent anywhere.",
                        style = Ty.rowSub, color = C.muted, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    TextButtonU("Connect calendar", { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
                }
                return@Box
            }
            // Seven days per page; swipe for earlier or later weeks.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                val start = weekStart(page)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val dots = rememberEvents(start, 7, version).groupBy { Routines.dayOf(it.begin) }
                    for (i in 0..6) {
                        val d = start + i
                        val on = d == picked
                        Column(
                            Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.accent else Color.Transparent)
                                .then(if (d == today && !on) Modifier.border(1.dp, C.accent, RoundedCornerShape(14.dp)) else Modifier)
                                // Tap the picked day again to unselect it and see the next 30 days.
                                .clickable(role = Role.Tab, onClickLabel = if (on) "Show the next 30 days" else null) { picked = if (on) null else d }
                                .semantics { contentDescription = dayName(d, today) },
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
            }
            val day = picked
            if (day != null) {
                // Just the picked day's events.
                val events = rememberEvents(day, 1, version)
                Text(dayName(day, today).uppercase(), style = Ty.section, color = C.fg, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp))
                HDivider(C.faint, Modifier.padding(horizontal = 24.dp))
                if (events.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
                        Text("Nothing on this day.", style = Ty.rowSub, color = C.muted)
                    }
                } else LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 120.dp)) {
                    items(events, key = { "e${it.eventId}_${it.begin}" }) { e ->
                        SwipeToDelete(onDelete = { deleting = e }) { EventRow(e, onOpenNote = onOpenNote, onEdit = { editing = e }) }
                    }
                }
            } else {
                // No day picked: everything that hasn't ended yet in the next 30 days, under a heading per day.
                val now = System.currentTimeMillis()
                val byDay = rememberEvents(today, UPCOMING_DAYS, version).filter { it.end > now }
                    .groupBy { Routines.dayOf(it.begin).coerceAtLeast(today) }.toSortedMap()
                Text("NEXT $UPCOMING_DAYS DAYS", style = Ty.section, color = C.fg, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp))
                HDivider(C.faint, Modifier.padding(horizontal = 24.dp))
                if (byDay.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
                        Text("Nothing in the next $UPCOMING_DAYS days.", style = Ty.rowSub, color = C.muted)
                    }
                } else LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 120.dp)) {
                    byDay.forEach { (d, events) ->
                        item(key = "d$d") {
                            Text(
                                dayName(d, today).uppercase(), style = Ty.mono, color = if (d == today) C.accent else C.muted,
                                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Show this day") { jumpTo(d) }.padding(top = 16.dp, bottom = 2.dp),
                            )
                        }
                        items(events, key = { "u${it.eventId}_${it.begin}" }) { e ->
                            SwipeToDelete(onDelete = { deleting = e }) { EventRow(e, onOpenNote = onOpenNote, onEdit = { editing = e }) }
                        }
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

    val saved = { version++; Widgets.refreshAll(ctx) }
    if (creating) EventSheet(notes, picked ?: today, editing = null, onDismiss = { creating = false }, onSaved = saved)
    editing?.let { e ->
        EventSheet(notes, picked ?: Routines.dayOf(e.begin), editing = e, onDismiss = { editing = null }, onSaved = saved,
            onDelete = { editing = null; deleting = e })
    }
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

// ─── New / edit event ────────────────────────────────────────────────────────

/** Extra reminders on top of the one every event gets when it starts. -1 = choose your own. */
private val EXTRA_REMINDERS = listOf<Pair<Int?, String>>(null to "None", 10 to "10 min before", 60 to "1 hour before", 1440 to "1 day before", -1 to "Custom…")
private val REPEATS = listOf("NONE" to "Never", "DAILY" to "Every day", "WEEKLY" to "Every week", "MONTHLY" to "Every month", "YEARLY" to "Every year")

private fun reminderText(m: Int?): String = when {
    m == null -> "At the start"
    m % 1440 == 0 -> "At the start + ${m / 1440} day${if (m / 1440 == 1) "" else "s"} before"
    m % 60 == 0 -> "At the start + ${m / 60} h before"
    else -> "At the start + $m min before"
}

/** The event sheet: a new event ([editing] null), or changes to one you tapped (with a Delete button at the bottom). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSheet(notes: List<Note>, day: Long, editing: CalEvent?, onDismiss: () -> Unit, onSaved: () -> Unit, onDelete: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val calendars by produceState(emptyList<CalInfo>()) { value = withContext(Dispatchers.IO) { Cal.calendars(ctx).filter { it.writable } } }
    var title by remember { mutableStateOf(editing?.title?.takeIf { it != "(No title)" } ?: "") }
    var allDay by remember { mutableStateOf(editing?.allDay ?: false) }
    val firstStart = remember {
        editing?.begin ?: if (day == Routines.today()) Cal.nextHour() else Routines.startOf(day) + 9 * 60 * 60_000L
    }
    var start by remember { mutableLongStateOf(firstStart) }
    var end by remember { mutableLongStateOf(editing?.end ?: (firstStart + 60 * 60_000L)) }
    var calId by remember { mutableStateOf(editing?.calendarId) }
    var reminder by remember { mutableStateOf<Int?>(null) }
    var repeat by remember { mutableStateOf("NONE") }
    var noteId by remember { mutableStateOf(editing?.let { Cal.linkedNote(ctx, it.eventId) }) }
    var picker by remember { mutableStateOf("") } // which sub-dialog is open
    // Editing: fill in the repeat and extra reminder from the calendar.
    LaunchedEffect(editing) {
        val e = editing ?: return@LaunchedEffect
        val d = withContext(Dispatchers.IO) { Cal.details(ctx, e.eventId) } ?: return@LaunchedEffect
        repeat = d.repeat; reminder = d.extraReminder
    }
    val cal = calendars.firstOrNull { it.id == calId } ?: calendars.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.menuBg, contentColor = C.fg,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButtonU("Cancel", onDismiss, color = C.muted)
                Text(if (editing == null) "New event" else "Edit event", style = Ty.rowTitle, color = C.fg, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButtonU("Save", {
                    val c = cal ?: return@TextButtonU
                    val name = title.trim().ifEmpty { "New event" }
                    Repo.scope.launch {
                        val id = if (editing == null) Cal.insert(ctx, c.id, name, start, end, allDay, reminder, repeat)
                        else editing.eventId.takeIf { Cal.update(ctx, it, editing.begin, c.id, name, start, end, allDay, reminder, repeat) }
                        if (id != null) Cal.setLinkedNote(ctx, id, noteId)
                        withContext(Dispatchers.Main) {
                            if (id == null) android.widget.Toast.makeText(ctx, "Couldn't save the event.", android.widget.Toast.LENGTH_LONG).show()
                            onSaved(); onDismiss()
                        }
                    }
                }, enabled = cal != null)
            }
            UField(
                title, { title = it }, "Event title", Modifier.fillMaxWidth().padding(vertical = 8.dp), style = Ty.editorTitle,
                fieldModifier = if (editing == null) Modifier.focusRequester(rememberAutoFocus()) else Modifier,
                // Titles start with a capital letter, like note titles.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            HDivider(C.accent)
            SheetSwitchRow("All day", allDay) { allDay = it }
            SheetValueRow("Starts", if (allDay) Fmt.date(start) else Fmt.dateTime(start)) { picker = "start" }
            SheetValueRow("Ends", if (allDay) Fmt.date(end) else Fmt.dateTime(end)) { picker = "end" }
            SheetValueRow("Google calendar", cal?.name ?: "No calendar you can add to") { if (calendars.size > 1) picker = "cal" }
            SheetValueRow("Reminder", reminderText(reminder)) { picker = "reminder" }
            SheetValueRow("Repeat", REPEATS.first { it.first == repeat }.second) { picker = "repeat" }
            SheetValueRow("Linked note", noteId?.let { id -> notes.firstOrNull { it.id == id }?.let { noteDisplayTitle(it) } } ?: "None") { picker = "note" }
            // Editing: delete the event from the bottom of the sheet (it asks first).
            if (editing != null && onDelete != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.danger, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button, onClickLabel = "Delete event", onClick = onDelete).heightIn(min = 52.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(UI.Trash, null, tint = C.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete event", style = Ty.row, color = C.danger)
                }
            }
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
        "reminder" -> OptionDialog("Also remind me", EXTRA_REMINDERS, if (reminder != null && EXTRA_REMINDERS.none { it.first == reminder }) -1 else reminder,
            { m -> if (m == -1) picker = "custom" else { reminder = m; picker = "" } }, { if (picker == "reminder") picker = "" })
        "custom" -> CustomReminderDialog(reminder, { reminder = it }, { picker = "" })
        "repeat" -> OptionDialog("Repeat", REPEATS, repeat, { repeat = it }, { picker = "" })
        "note" -> OptionDialog("Linked note", listOf<Pair<Int?, String>>(null to "None") + notes.sortedByDescending { it.updatedAt }.take(40).map { it.id to noteDisplayTitle(it) },
            noteId, { noteId = it }, { picker = "" })
    }
}

/** "Custom…" extra reminder: a number of minutes, hours or days before the event. */
@Composable
private fun CustomReminderDialog(current: Int?, onSet: (Int?) -> Unit, onDismiss: () -> Unit) {
    val units = listOf(1 to "minutes", 60 to "hours", 1440 to "days")
    val startUnit = when { current == null -> 60; current % 1440 == 0 -> 1440; current % 60 == 0 -> 60; else -> 1 }
    var unit by remember { mutableIntStateOf(startUnit) }
    var amount by remember { mutableStateOf(((current ?: 120) / startUnit).toString()) }
    UDialog(onDismiss, "Remind me before") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            UField(amount, { amount = it.filter(Char::isDigit).take(4) }, "How many", Modifier.fillMaxWidth(),
                fieldModifier = Modifier.focusRequester(rememberAutoFocus()),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Segmented(units, unit) { unit = it }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            TextButtonU("Cancel", onDismiss, color = C.muted)
            TextButtonU("Set", {
                val n = amount.toIntOrNull() ?: 0
                onSet(if (n <= 0) null else n * unit); onDismiss()
            })
        }
    }
}

@Composable
private fun SheetValueRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Text(value, style = Ty.monoValue, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
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
