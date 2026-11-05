package com.example.unutma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty

/** Which row of tools is open above the editor's bottom bar. */
enum class ToolTab { TEXT, INSERT }

/** One tab of the editor's bottom bar: an icon with a small label; [dot] marks "something is set". */
@Composable
fun ToolTabButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (active) tint else C.muted
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = active; contentDescription = label + if (dot) ", set" else "" }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            Icon(icon, null, tint = if (dot) tint else color, modifier = Modifier.size(18.dp))
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(6.dp).clip(CircleShape).background(tint))
        }
        Text(label, style = Ty.mono.copy(fontSize = 10.sp), color = color)
    }
}

/** The editor's Details sheet, sliding up from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.menuBg,
        contentColor = C.fg,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding()) {
            Text("Details", style = Ty.settingsHeader, color = C.muted, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** A row in a sheet: icon, title and the current value; tapping opens its picker. */
@Composable
fun SheetRow(title: String, icon: ImageVector, value: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = C.muted, modifier = Modifier.size(18.dp))
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = Ty.monoValue, color = C.muted)
            Icon(UI.Chevron, null, tint = C.faint, modifier = Modifier.size(14.dp))
        }
        HDivider()
    }
}

/** A row in a sheet with an on/off switch. */
@Composable
fun SheetSwitch(title: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = if (checked) C.accent else C.muted, modifier = Modifier.size(18.dp))
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            MiniSwitch(checked, onChange, title)
        }
        HDivider()
    }
}
