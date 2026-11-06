package com.example.unutma.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.unutma.Label
import com.example.unutma.notify.Alarms
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.labelColor
import com.example.unutma.util.Fmt
import java.util.Calendar
import java.util.TimeZone

@Composable
fun DialogRow(text: String, onClick: () -> Unit, icon: ImageVector? = null, trailing: String? = null, color: Color = C.body, iconTint: Color = C.muted) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Text(text, style = Ty.row, color = color, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Ty.monoValue, color = C.muted)
    }
}

private fun at(daysFromToday: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_MONTH, daysFromToday)
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

val NAG_OPTIONS = listOf(0 to "Off", 5 to "Every 5 min", 15 to "Every 15 min", 30 to "Every 30 min", 60 to "Every hour")

/** Set a reminder on the note itself: quick picks, any date and time, and nagging. */
@Composable
fun ReminderDialog(current: Long?, nagMinutes: Int, onSet: (Long?, Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var pickCustom by remember { mutableStateOf(false) }
    var pickNag by remember { mutableStateOf(false) }
    var nag by remember { mutableIntStateOf(nagMinutes) }
    val now = System.currentTimeMillis()

    if (pickCustom) {
        DateTimePicker(initial = current ?: at(1, 9), withTime = true, onPicked = { onSet(it, nag); onDismiss() }, onDismiss = { pickCustom = false })
        return
    }
    if (pickNag) {
        OptionDialog("Repeat until done", NAG_OPTIONS, nag, { nag = it; if (current != null) onSet(current, it) }, { pickNag = false })
        return
    }

    UDialog(onDismiss, "Remind me") {
        // Quick picks counted from now, each showing exactly when it will go off (to the minute).
        val minute = now - now % 60_000
        val inOne = minute + 60 * 60_000L
        DialogRow("In 1 hour", { onSet(inOne, nag); onDismiss() }, UI.Bell, Fmt.reminderShort(inOne))
        val inThree = minute + 3 * 60 * 60_000L
        DialogRow("In 3 hours", { onSet(inThree, nag); onDismiss() }, UI.Bell, Fmt.reminderShort(inThree))
        val tomorrowSame = Calendar.getInstance().apply { timeInMillis = minute; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        DialogRow("Tomorrow at this time", { onSet(tomorrowSame, nag); onDismiss() }, UI.Bell, Fmt.reminderShort(tomorrowSame))
        DialogRow("Pick date & time", { pickCustom = true }, UI.Calendar)
        UMenuDivider()
        DialogRow("Repeat until done", { pickNag = true }, UI.Repeat, NAG_OPTIONS.firstOrNull { it.first == nag }?.second ?: "Off")
        if (current != null) {
            UMenuDivider()
            Text(
                "Set for " + Fmt.dateTime(current),
                style = Ty.mono, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DialogRow("Remove reminder", { onSet(null, 0); onDismiss() }, UI.Close, color = C.danger, iconTint = C.danger)
        }
        if (!Alarms.canExact(ctx) && Build.VERSION.SDK_INT >= 31) {
            UMenuDivider()
            DialogRow("Allow exact alarms so reminders are on time", {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            }, UI.External)
        }
    }
}

/**
 * Material date picker, then (when [withTime]) a 24-hour time picker.
 * Returns local time in epoch milliseconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePicker(initial: Long, withTime: Boolean, onPicked: (Long) -> Unit, onDismiss: () -> Unit, defaultHour: Int = 9) {
    val initCal = remember { Calendar.getInstance().apply { timeInMillis = initial } }
    // The date picker works in UTC midnights.
    val utcInitial = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(initCal.get(Calendar.YEAR), initCal.get(Calendar.MONTH), initCal.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = utcInitial)
    var chosenDay by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    val day = chosenDay
    if (day == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButtonU("Next", {
                    val sel = dateState.selectedDateMillis ?: return@TextButtonU
                    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                    val t = Triple(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                    if (withTime) chosenDay = t else onPicked(Fmt.at(t.first, t.second, t.third, defaultHour, 0))
                }, enabled = dateState.selectedDateMillis != null)
            },
            dismissButton = { TextButtonU("Cancel", onDismiss, color = C.muted) },
            colors = DatePickerDefaults.colors(containerColor = C.menuBg),
        ) {
            DatePicker(
                state = dateState,
                colors = DatePickerDefaults.colors(
                    containerColor = C.menuBg,
                    selectedDayContainerColor = C.accent,
                    selectedDayContentColor = C.onAccent,
                    todayDateBorderColor = C.accent,
                    todayContentColor = C.accent,
                ),
            )
        }
    } else {
        var hour by remember { mutableIntStateOf(if (initial > 0) initCal.get(Calendar.HOUR_OF_DAY) else defaultHour) }
        var minute by remember { mutableIntStateOf(if (initial > 0) initCal.get(Calendar.MINUTE) else 0) }
        UDialog(onDismiss, "Time") {
            TimeWheels(hour, minute, { hour = it }, { minute = it }, Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButtonU("Back", { chosenDay = null }, color = C.muted)
                TextButtonU("Set", { onPicked(Fmt.at(day.first, day.second, day.third, hour, minute)) })
            }
        }
    }
}

/**
 * Two wheels, hours (00–23) and minutes (every minute, 00–59), that snap to the middle row.
 * They go round: flicking past 59 comes back to 00.
 */
@Composable
fun TimeWheels(hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(WHEEL_ROW * 5), contentAlignment = Alignment.Center) {
        // The band behind the chosen time.
        Box(Modifier.fillMaxWidth().height(WHEEL_CENTER).clip(RoundedCornerShape(14.dp)).background(C.selected))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Wheel(24, hour, onHour, C.fg, "Hour")
            Text(":", style = Ty.monoValue.copy(fontSize = 30.sp), color = C.muted)
            Wheel(60, minute, onMinute, C.accent, "Minute")
        }
    }
}

private val WHEEL_ROW = 40.dp
private val WHEEL_CENTER = 50.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(count: Int, value: Int, onValue: (Int) -> Unit, centerColor: Color, label: String) {
    // A long list that repeats 0…count-1, started in the middle so it can turn either way.
    val loops = 400
    val start = (loops / 2) * count + value
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val density = LocalDensity.current
    val rowPx = with(density) { WHEEL_ROW.toPx() }
    // The row in the middle of the wheel.
    val centered by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 }
    }
    LaunchedEffect(centered) { onValue(((centered % count) + count) % count) }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = WHEEL_ROW * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(90.dp).height(WHEEL_ROW * 5)
            .semantics { contentDescription = "$label ${"%02d".format(((centered % count) + count) % count)}" },
    ) {
        items(count * loops) { i ->
            val d = kotlin.math.abs(i - centered)
            Box(Modifier.height(WHEEL_ROW).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "%02d".format(i % count),
                    style = Ty.monoValue.copy(fontSize = when (d) { 0 -> 34.sp; 1 -> 22.sp; else -> 18.sp }),
                    color = when (d) { 0 -> centerColor; 1 -> Color(0xFF555555); else -> Color(0xFF2E2E2E) },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Choose the note's label (or none). */
@Composable
fun LabelPickerDialog(
    labels: List<Label>,
    selected: Int?,
    onPick: (Int?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: ((name: String, color: String) -> Unit)? = null,
) {
    // With no labels yet, go straight to creating one.
    var creating by remember { mutableStateOf(onCreate != null && labels.isEmpty()) }
    if (creating && onCreate != null) {
        val next = com.example.unutma.ui.theme.LABEL_COLOURS.map { it.key }.firstOrNull { k -> labels.none { it.color == k } } ?: "PURPLE"
        UDialog(onDismiss, "New label") {
            NewLabelForm(next, { name, color -> onCreate(name, color); onDismiss() }, { if (labels.isEmpty()) onDismiss() else creating = false })
        }
        return
    }
    UDialog(onDismiss, "Label") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            labels.forEach { l ->
                val color = labelColor(l.color)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onPick(l.id); onDismiss() }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Dot(color, 8.dp)
                    Text(l.name, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
                    if (l.id == selected) Icon(UI.Tick, null, tint = color, modifier = Modifier.size(16.dp))
                }
            }
            DialogRow("No label", { onPick(null); onDismiss() }, trailing = if (selected == null) "✓" else null)
            UMenuDivider()
            if (onCreate != null) DialogRow("Create label", { creating = true }, UI.PlusBold)
            DialogRow("Manage labels", { onDismiss(); onManage() }, UI.Tag)
        }
    }
}

/** Yes/no confirmation in the app's style. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    UDialog(onDismiss, title) {
        Text(message, style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButtonU("Cancel", onDismiss, color = C.muted)
            TextButtonU(confirm, { onConfirm(); onDismiss() }, color = if (danger) C.danger else C.accent)
        }
    }
}
