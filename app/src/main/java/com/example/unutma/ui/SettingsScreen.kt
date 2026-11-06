package com.example.unutma.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.unutma.data.ARCHIVE_FOREVER
import com.example.unutma.data.ARCHIVE_OPTIONS
import com.example.unutma.data.Guide
import com.example.unutma.data.Repo
import com.example.unutma.data.Settings
import com.example.unutma.data.SortOrder
import com.example.unutma.data.TextSize
import com.example.unutma.data.ThemeMode
import com.example.unutma.data.archiveLabel
import com.example.unutma.notify.Notifier
import com.example.unutma.ui.theme.ACCENTS
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onBack: () -> Unit, onFeedback: () -> Unit, onHelp: () -> Unit,
    unlock: (onSuccess: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    var sortDialog by remember { mutableStateOf(false) }
    var archiveDialog by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var widgetSizeDialog by remember { mutableStateOf(false) }
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        ScreenHeader("Settings", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            SettingsSection("Appearance") {
                Column(Modifier.padding(top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Theme", style = Ty.row, color = C.fg)
                    Segmented(ThemeMode.entries.map { it to it.label }, settings.themeMode) { m -> onChange { it.copy(themeMode = m) } }
                }
                HDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Accent colour", style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
                    Box(Modifier.offset(x = 6.dp)) {
                        SwatchRow(ACCENTS.map { it.key }, settings.accentKey, { k -> onChange { it.copy(accentKey = k) } })
                    }
                }
                HDivider()
                ValueRow("Text size", settings.textSize.label) { sizeDialog = true }
            }

            SettingsSection("Notes") {
                ValueRow("Default sort order", settings.sortOrder.label) { sortDialog = true }
                ValueRow("Keep recently deleted notes", archiveLabel(settings.archiveDays)) { archiveDialog = true }
            }

            SettingsSection("Widgets") {
                ValueRow("Default widget text size", settings.widgetTextSize.label) { widgetSizeDialog = true }
            }

            SettingsSection("Notifications") {
                SwitchRow("Sound", settings.sound) { v -> onChange { it.copy(sound = v) }; Notifier.reminderChannel(ctx) }
                SwitchRow("Vibration", settings.vibration) { v -> onChange { it.copy(vibration = v) }; Notifier.reminderChannel(ctx) }
                SwitchRow("Show pinned notes opened", settings.pinExpanded) { v ->
                    onChange { it.copy(pinExpanded = v) }
                    Repo.scope.launch { Notifier.resyncPins(ctx.applicationContext) }
                }
                LinkRow("Android notification settings", UI.External) {
                    val i = if (Build.VERSION.SDK_INT >= 26) Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                    else Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    ctx.startActivity(i)
                }
            }

            BackupSection(unlock)

            SettingsSection("Help and about") {
                LinkRow("Help", UI.Chevron, onClick = onHelp)
                LinkRow("Add the guide notes", UI.PlusBold) {
                    Repo.scope.launch {
                        Guide.add(ctx.applicationContext)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "Guide notes added to the Guide label", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                LinkRow("Send feedback", UI.Chevron, onClick = onFeedback)
                // About: the app's icon, name and version.
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF0E0E12)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(com.example.unutma.R.drawable.ic_launcher_foreground), null,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    Text("Unutma", style = Ty.rowTitle, color = C.fg, modifier = Modifier.weight(1f))
                    Text(version, style = Ty.monoValue, color = C.muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sortDialog) OptionDialog("Default sort order", SortOrder.entries.map { it to it.label }, settings.sortOrder,
        { v -> onChange { it.copy(sortOrder = v) } }, { sortDialog = false })
    if (archiveDialog) OptionDialog("Keep recently deleted notes for", ARCHIVE_OPTIONS, settings.archiveDays,
        { v -> onChange { it.copy(archiveDays = v) } }, { archiveDialog = false })
    if (sizeDialog) OptionDialog("Text size", TextSize.entries.map { it to it.label }, settings.textSize,
        { v -> onChange { it.copy(textSize = v) } }, { sizeDialog = false })
    if (widgetSizeDialog) OptionDialog("Widget text size", TextSize.entries.map { it to it.label }, settings.widgetTextSize,
        { v -> onChange { it.copy(widgetTextSize = v) }; com.example.unutma.widget.Widgets.refreshAll(ctx) }, { widgetSizeDialog = false })
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title.uppercase(), style = Ty.settingsHeader, color = C.muted, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = Ty.monoValue, color = C.muted)
            Spacer(Modifier.width(6.dp))
            Icon(UI.Chevron, null, tint = C.faint, modifier = Modifier.size(14.dp))
        }
        HDivider()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            MiniSwitch(checked, onChange, title)
        }
        HDivider()
    }
}

@Composable
private fun LinkRow(title: String, icon: ImageVector, divider: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Icon(icon, null, tint = C.faint, modifier = Modifier.size(14.dp))
        }
        if (divider) HDivider()
    }
}

// ─── Help ────────────────────────────────────────────────────────────────────

/** The guide, as a page: tap a topic to open it. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val topics = remember { Guide.topics() }
    var open by remember { mutableStateOf<Int?>(0) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        ScreenHeader("Help", onBack)
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp)) {
            itemsIndexed(topics) { i, (title, text) ->
                val expanded = open == i
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = if (expanded) null else i }
                            .semantics { stateDescription = if (expanded) "Open" else "Closed" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = Ty.rowTitle, color = C.fg, modifier = Modifier.weight(1f))
                        Icon(UI.Chevron, null, tint = C.faint, modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f))
                    }
                    if (expanded) Text(text, style = Ty.body, color = C.body, modifier = Modifier.padding(bottom = 16.dp))
                    HDivider()
                }
            }
        }
    }
}
