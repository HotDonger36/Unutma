package com.example.unutma.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.LABEL_COLOURS
import com.example.unutma.ui.theme.Ty

@Composable
fun Dot(color: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Checklist progress: a ring, deliberately unlike the date/time chips. When everything is done it fills in solid. */
@Composable
fun ProgressRing(done: Int, total: Int, color: Color, size: Dp = 14.dp, stroke: Dp = 2.dp, modifier: Modifier = Modifier) {
    val track = C.track
    Canvas(modifier.size(size).semantics { contentDescription = "$done of $total done" }) {
        val w = stroke.toPx()
        val inset = w / 2f + (this.size.minDimension * 0.07f)
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        if (total > 0 && done >= total) {
            drawCircle(color, radius = arcSize.width / 2f + w / 2f)
            return@Canvas
        }
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        if (total > 0 && done > 0) {
            drawArc(color, -90f, 360f * done / total, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun HDivider(color: Color = C.divider, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A label section title: coloured, uppercase, with a count and a coloured underline.
 * With [onToggle], tapping it folds the section away (a chevron shows which way).
 */
@Composable
fun SectionHeader(
    title: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val tap = if (onToggle != null) Modifier.clickable(
        onClickLabel = if (collapsed) "Show notes" else "Hide notes", onClick = onToggle,
    ) else Modifier
    Column(modifier.fillMaxWidth().then(tap)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = Ty.section, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), style = Ty.mono, color = color)
            if (onToggle != null) Icon(
                UI.Chevron, null, tint = color,
                modifier = Modifier.padding(start = 8.dp).size(12.dp).rotate(if (collapsed) 0f else 90f),
            )
        }
        HDivider(color)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = C.icon,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else C.faint, modifier = Modifier.size(iconSize))
    }
}

/** Outlined pill used for the label chip in the editor and filter chips in search. */
@Composable
fun Pill(
    text: String,
    color: Color,
    onClick: () -> Unit,
    filled: Boolean = false,
    dot: Boolean = true,
    dashed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (filled) Modifier.background(color.copy(alpha = 0.16f)) else Modifier)
            .border(1.dp, if (dashed) C.menuBorder else color, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = if (dot) 10.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot) Dot(color)
        Text(text.uppercase(), style = Ty.section, color = color, maxLines = 1)
    }
}

/** Small toggle chip for search filters. */
@Composable
fun FilterChipMini(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) C.selected else Color.Transparent)
            .border(1.dp, if (selected) C.fg.copy(alpha = 0.5f) else C.menuBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Ty.tab, color = if (selected) C.fg else C.muted)
    }
}

/** The design's switch: accent track, dark knob. */
@Composable
fun MiniSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String) {
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) C.accent else C.track)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) C.onAccent else C.bg)
        )
    }
}

/** Three-way segmented control (Theme: Dark / Light / System). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, C.menuBorder, RoundedCornerShape(12.dp)),
    ) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(C.menuBorder))
            val sel = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (sel) C.selected else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Ty.tab.copy(fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Medium else null), color = if (sel) C.fg else C.muted)
            }
        }
    }
}

/** The design's dropdown: near-black panel, thin border, 14 dp corners. */
@Composable
fun UMenu(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset = DpOffset(0.dp, 0.dp), content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(14.dp),
        containerColor = C.menuBg,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, C.menuBorder),
        modifier = Modifier.widthIn(min = 210.dp),
        content = content,
    )
}

@Composable
fun UMenuItem(text: String, onClick: () -> Unit, icon: ImageVector? = null, iconTint: Color = C.muted, color: Color = C.body, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(text, style = Ty.menu, color = color, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Ty.mono, color = C.muted)
    }
}

@Composable
fun UMenuDivider() {
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(C.divider))
}

/** A minimal dialog shell matching the menus. */
@Composable
fun UDialog(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(C.menuBg)
                .border(1.dp, C.menuBorder, RoundedCornerShape(18.dp))
                .padding(vertical = 14.dp),
        ) {
            if (title != null) {
                Text(title, style = Ty.rowTitle, color = C.fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Spacer(Modifier.height(6.dp))
            }
            content()
        }
    }
}

/** Pick one option from a list (sort order, archive time, nag interval…). */
@Composable
fun <T> OptionDialog(title: String, options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    UDialog(onDismiss, title) {
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onPick(value); onDismiss() }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Ty.row, color = if (value == selected) C.fg else C.body, modifier = Modifier.weight(1f))
                if (value == selected) Icon(UI.Tick, null, tint = C.accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun TextButtonU(text: String, onClick: () -> Unit, color: Color = C.accent, enabled: Boolean = true) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Ty.button, color = if (enabled) color else C.faint) }
}

/** Single-line text input styled like the rest of the app. */
@Composable
fun UField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Ty.row,
    singleLine: Boolean = true,
    fieldModifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    Box(modifier) {
        if (value.isEmpty()) Text(placeholder, style = style, color = C.faint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = style.copy(color = C.fg),
            cursorBrush = SolidColor(C.accent),
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().then(fieldModifier),
        )
    }
}

/**
 * Focuses a text field and opens the keyboard as soon as it appears
 * (a short wait lets a dialog's window get ready first).
 */
@Composable
fun rememberAutoFocus(enabled: Boolean = true): androidx.compose.ui.focus.FocusRequester {
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(6) {
            kotlinx.coroutines.delay(60)
            try { fr.requestFocus(); keyboard?.show(); return@LaunchedEffect } catch (_: Exception) {}
        }
    }
    return fr
}

/** Name + colour form for a new label; the keyboard opens straight away. */
@Composable
fun NewLabelForm(defaultColor: String, onCreate: (name: String, color: String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(defaultColor) }
    val fr = rememberAutoFocus()
    fun create() { if (name.isNotBlank()) onCreate(name.trim(), color) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(com.example.unutma.ui.theme.labelColor(color), 8.dp)
            Spacer(Modifier.width(12.dp))
            UField(
                name, { name = it.take(24) }, "Label name, e.g. Personal", Modifier.weight(1f),
                fieldModifier = Modifier.focusRequester(fr),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { create() }),
            )
        }
        HDivider()
        SwatchRow(LABEL_COLOURS.map { it.key }, color, { color = it })
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButtonU("Cancel", onCancel, color = C.muted)
        TextButtonU("Create", { create() }, enabled = name.isNotBlank())
    }
}

/** Colour swatch row for label colours and accents. */
@Composable
fun SwatchRow(keys: List<String>, selected: String, onSelect: (String) -> Unit, isDark: Boolean = C.isDark) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.forEach { key ->
            val sw = LABEL_COLOURS.first { it.key == key }
            val sel = key == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .then(if (sel) Modifier.border(1.dp, C.fg, CircleShape) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(key) }
                    .semantics { contentDescription = sw.name },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(sw.on(isDark)))
            }
        }
    }
}
