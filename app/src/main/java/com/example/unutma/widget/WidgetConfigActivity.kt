package com.example.unutma.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unutma.Label
import com.example.unutma.Note
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.TextSize
import com.example.unutma.data.ThemeMode
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.previewLine
import com.example.unutma.ui.Dot
import com.example.unutma.ui.HDivider
import com.example.unutma.ui.ScreenHeader
import com.example.unutma.ui.Segmented
import com.example.unutma.ui.TextButtonU
import com.example.unutma.ui.UI
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.UnutmaTheme
import com.example.unutma.ui.theme.labelColor
import kotlinx.coroutines.launch

/**
 * Widget settings: opens when a widget is added, or from "Widget settings" when you hold it.
 * Every widget gets its own text size; note widgets pick which note they show, the Notes widget
 * picks all notes or one label.
 */
class WidgetConfigActivity : ComponentActivity() {
    companion object { const val EXTRA_PROVIDER = "provider" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val mgr = AppWidgetManager.getInstance(this)
        val className = intent.getStringExtra(EXTRA_PROVIDER) ?: mgr.getAppWidgetInfo(widgetId)?.provider?.className
        val isNotesWidget = className == NotesListWidget::class.java.name
        val provider: NoteWidgetBase? = if (isNotesWidget) null else try {
            Class.forName(className ?: "").getDeclaredConstructor().newInstance() as NoteWidgetBase
        } catch (_: Exception) { finish(); return }
        val prefs = Prefs.get(this)

        fun ok() = setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        setContent {
            val settings by prefs.state.collectAsState()
            val notes by remember { Repo.notes(this).observeActive() }.collectAsState(initial = emptyList())
            val labels by remember { Repo.labels(this).observeAll() }.collectAsState(initial = emptyList())
            val scope = rememberCoroutineScope()
            var size by remember { mutableStateOf(prefs.widgetTextSize(widgetId)) }
            var chosenNote by remember { mutableIntStateOf(prefs.widgetNote(widgetId)) }
            var chosenLabel by remember { mutableIntStateOf(prefs.widgetLabel(widgetId)) }

            /** Redraws this widget with the current choices. */
            fun redraw() = scope.launch {
                if (provider != null) provider.renderAll(applicationContext, mgr, intArrayOf(widgetId))
                else NotesListWidget.render(applicationContext, mgr, intArrayOf(widgetId))
            }

            UnutmaTheme(darkTheme = settings.themeMode != ThemeMode.LIGHT, accentKey = settings.accentKey, textScale = settings.textSize.scale) {
                Column(Modifier.fillMaxSize().background(C.bg).navigationBarsPadding()) {
                    ScreenHeader(if (provider != null) provider.pickerTitle else "Notes widget", { finish() }) {
                        // A widget that already shows something can just be closed after changing the size.
                        if (provider == null || chosenNote > 0) TextButtonU("Done", { ok(); finish() })
                    }

                    // Text size, for this widget only.
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Text size", style = Ty.row, color = C.fg)
                        Segmented(TextSize.entries.map { it to it.label }, size) { s ->
                            size = s
                            prefs.setWidgetTextSize(widgetId, s)
                            if (provider == null || chosenNote > 0) { ok(); redraw() }
                        }
                    }
                    HDivider(modifier = Modifier.padding(horizontal = 24.dp))

                    if (provider == null) {
                        // Notes widget: all notes, or one label's.
                        Text("Show", style = Ty.settingsHeader, color = C.muted, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 4.dp))
                        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp)) {
                            item {
                                ChoiceRow("All notes", C.muted, null, chosenLabel < 0) {
                                    chosenLabel = -1; prefs.setWidgetLabel(widgetId, -1); ok(); redraw()
                                }
                            }
                            items(labels, key = { it.id }) { l ->
                                ChoiceRow(l.name, labelColor(l.color), null, chosenLabel == l.id) {
                                    chosenLabel = l.id; prefs.setWidgetLabel(widgetId, l.id); ok(); redraw()
                                }
                            }
                        }
                    } else {
                        val eligible = notes.filter { provider.accepts(it) }.sortedByDescending { it.updatedAt }
                        if (eligible.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (provider is CountdownWidget) "No notes with a countdown or reminder yet." else "No checklists yet.",
                                    style = Ty.monoValue, color = C.muted,
                                )
                            }
                        } else LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                            items(eligible, key = { it.id }) { n ->
                                PickRow(n, labels, n.id == chosenNote) {
                                    chosenNote = n.id
                                    prefs.setWidgetNote(widgetId, n.id)
                                    scope.launch {
                                        provider.renderAll(applicationContext, mgr, intArrayOf(widgetId))
                                        ok()
                                        finish()
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

@Composable
private fun ChoiceRow(title: String, color: Color, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(color)
            Column(Modifier.weight(1f)) {
                Text(title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrEmpty()) Text(sub, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(UI.Tick, "Selected", tint = C.accent, modifier = Modifier.size(16.dp))
        }
        HDivider()
    }
}

@Composable
private fun PickRow(n: Note, labels: List<Label>, selected: Boolean, onClick: () -> Unit) {
    val label = labels.firstOrNull { it.id == n.labelId }
    val blocks = remember(n) { parseBlocks(n) }
    ChoiceRow(
        noteDisplayTitle(n, blocks),
        labelColor(label?.color ?: "GREY"),
        if (n.isLocked) "Locked" else blocks.previewLine(),
        selected, onClick,
    )
}
