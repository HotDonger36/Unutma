package com.example.unutma.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
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

private fun nextWeekday(target: Int, hour: Int): Long {
    val c = Calendar.getInstance()
    var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
    if (add == 0) add = 7
    return at(add, hour)
}

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
        val laterToday = at(0, 18)
        if (laterToday > now + 30 * 60_000) DialogRow("Later today", { onSet(laterToday, nag); onDismiss() }, UI.Bell, Fmt.time(laterToday))
        val tomorrow = at(1, 9)
        DialogRow("Tomorrow", { onSet(tomorrow, nag); onDismiss() }, UI.Bell, Fmt.reminderShort(tomorrow))
        val monday = nextWeekday(Calendar.MONDAY, 9)
        DialogRow("Next week", { onSet(monday, nag); onDismiss() }, UI.Bell, Fmt.reminderShort(monday))
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
        val timeState = rememberTimePickerState(
            initialHour = if (initial > 0) initCal.get(Calendar.HOUR_OF_DAY) else defaultHour,
            initialMinute = if (initial > 0) initCal.get(Calendar.MINUTE) else 0,
            is24Hour = true,
        )
        UDialog(onDismiss, "Time") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = C.selected,
                        selectorColor = C.accent,
                        timeSelectorSelectedContainerColor = C.selected,
                        timeSelectorUnselectedContainerColor = C.bg,
                        timeSelectorSelectedContentColor = C.fg,
                        timeSelectorUnselectedContentColor = C.muted,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButtonU("Back", { chosenDay = null }, color = C.muted)
                TextButtonU("Set", { onPicked(Fmt.at(day.first, day.second, day.third, timeState.hour, timeState.minute)) })
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
