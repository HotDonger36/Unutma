package com.example.unutma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.example.unutma.Label
import com.example.unutma.Note
import com.example.unutma.data.ARCHIVE_FOREVER
import com.example.unutma.data.archiveLabel
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.previewLine
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.LABEL_COLOURS
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.labelColor

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconBtn(UI.Back, "Back", onBack, tint = C.fg)
        Text(title, style = Ty.screenTitle, color = C.fg, modifier = Modifier.weight(1f))
        actions()
    }
}

// ─── Archive and Recently deleted ────────────────────────────────────────────

/**
 * [deletedView] false: the Archive — notes you put away, kept until you restore or delete them.
 * [deletedView] true: Recently deleted — empties itself after the time set in Settings.
 */
@Composable
fun ArchiveScreen(
    notes: List<Note>,
    labels: List<Label>,
    archiveDays: Int,
    deletedView: Boolean,
    onBack: () -> Unit,
    onRestore: (Note) -> Unit,
    onDelete: (Collection<Int>) -> Unit,
    onDeleteForever: (Collection<Int>) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<Note?>(null) }
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000

    Column(Modifier.fillMaxSize().background(C.bg)) {
        ScreenHeader(if (deletedView) "Recently deleted" else "Archive", onBack) {
            if (deletedView && notes.isNotEmpty()) Box {
                IconBtn(UI.More, "More options", { menu = true })
                UMenu(menu, { menu = false }, DpOffset((-4).dp, 4.dp)) {
                    UMenuItem("Delete all for good", { menu = false; confirmEmpty = true }, color = C.danger)
                }
            }
        }
        Text(
            when {
                !deletedView -> "Archived notes stay here until you restore or delete them."
                archiveDays == ARCHIVE_FOREVER -> "Deleted notes stay here until you delete them for good. Change this in Settings."
                else -> "Deleted notes are kept for ${archiveLabel(archiveDays)}, then removed for good. Change this in Settings."
            },
            style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (deletedView) "Nothing deleted recently." else "The archive is empty.", style = Ty.monoValue, color = C.muted)
            }
        } else LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp)) {
            items(notes, key = { it.id }) { n ->
                val blocks = remember(n) { parseBlocks(n) }
                val label = labels.firstOrNull { it.id == n.labelId }
                val color = labelColor(label?.color ?: "GREY")
                val deleted = n.deletedAt
                val left = if (deleted == null || archiveDays == ARCHIVE_FOREVER) null
                else ((deleted + archiveDays * dayMs - now) / dayMs).coerceAtLeast(0)
                Column(Modifier.fillMaxWidth().clickable { actionsFor = n }) {
                    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Dot(color, modifier = Modifier.padding(top = 8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(noteDisplayTitle(n, blocks), style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val p = if (n.isLocked) "Locked" else blocks.previewLine()
                            if (p.isNotEmpty()) Text(p, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // The label is kept while archived and comes back on Restore.
                            if (label != null) Text(label.name.uppercase(), style = Ty.section, color = color, maxLines = 1)
                        }
                        Text(
                            when {
                                left == null -> if (deleted == null) "archived" else "deleted"
                                left == 0L -> "today"
                                left == 1L -> "1d left"
                                else -> "${left}d left"
                            },
                            style = Ty.mono, color = C.muted, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    HDivider()
                }
            }
        }
    }

    actionsFor?.let { n ->
        UDialog({ actionsFor = null }, noteDisplayTitle(n)) {
            DialogRow("Restore", { actionsFor = null; onRestore(n) }, UI.Restore)
            if (deletedView) DialogRow("Delete for good", { actionsFor = null; confirmOne = n }, UI.Trash, color = C.danger, iconTint = C.danger)
            else DialogRow("Delete", { actionsFor = null; onDelete(listOf(n.id)) }, UI.Trash, color = C.danger, iconTint = C.danger)
        }
    }
    confirmOne?.let { n ->
        ConfirmDialog("Delete for good?", "This note and its pictures and voice clips can't be brought back.", "Delete", { onDeleteForever(listOf(n.id)) }, { confirmOne = null })
    }
    if (confirmEmpty) {
        ConfirmDialog("Delete all for good?", "All ${notes.size} recently deleted notes will be removed and can't be brought back.", "Delete", { onDeleteForever(notes.map { it.id }) }, { confirmEmpty = false })
    }
}

// ─── Manage labels ───────────────────────────────────────────────────────────

@Composable
fun LabelsScreen(
    labels: List<Label>,
    counts: Map<Int, Int>,
    onBack: () -> Unit,
    onAdd: (name: String, color: String) -> Unit,
    onUpdate: (Label) -> Unit,
    onDelete: (Label) -> Unit,
    onReorder: (List<Label>) -> Unit,
    startCreating: Boolean = false,
) {
    val nextColor = LABEL_COLOURS.map { it.key }.firstOrNull { k -> labels.none { it.color == k } } ?: "PURPLE"
    var creating by remember { mutableStateOf(startCreating) }
    var editing by remember { mutableStateOf<Label?>(null) }
    var deleting by remember { mutableStateOf<Label?>(null) }

    // Drag to reorder: the list is reordered here while dragging and saved when you let go.
    val order = remember { mutableStateListOf<Label>().apply { addAll(labels) } }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<Int, Int>() }
    LaunchedEffect(labels) { if (dragId == null) { order.clear(); order.addAll(labels) } }

    fun dragModifier(l: Label, afterLongPress: Boolean) = Modifier.pointerInput(l.id, afterLongPress) {
        val start = { _: Offset -> dragId = l.id; dragOffset = 0f }
        val end = { dragId = null; dragOffset = 0f; onReorder(order.toList()) }
        val drag = { change: PointerInputChange, amount: Offset ->
            change.consume()
            dragOffset += amount.y
            val i = order.indexOfFirst { it.id == l.id }
            if (dragOffset > 0 && i in 0 until order.lastIndex) {
                val h = heights[order[i + 1].id] ?: 0
                if (h > 0 && dragOffset > h / 2f) { order.add(i + 1, order.removeAt(i)); dragOffset -= h }
            } else if (dragOffset < 0 && i > 0) {
                val h = heights[order[i - 1].id] ?: 0
                if (h > 0 && -dragOffset > h / 2f) { order.add(i - 1, order.removeAt(i)); dragOffset += h }
            }
        }
        if (afterLongPress) detectDragGesturesAfterLongPress(start, end, end, drag)
        else detectDragGestures(start, end, end, drag)
    }

    Column(Modifier.fillMaxSize().background(C.bg).imePadding()) {
        ScreenHeader("Labels", onBack) {
            IconBtn(UI.Plus, "Create label", { creating = true }, tint = C.fg)
        }
        Text(
            "Each label becomes a coloured section on your notes screen.",
            style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Column(Modifier.fillMaxWidth().clickable { creating = true }) {
                Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(UI.PlusBold, null, tint = C.accent, modifier = Modifier.size(16.dp))
                    Text("Create label", style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
                }
                HDivider()
            }
            if (order.size > 1) Text(
                "Drag the dots (or hold a label) to change the order.",
                style = Ty.rowSub, color = C.faint, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            order.forEach { l ->
                key(l.id) {
                    val color = labelColor(l.color)
                    val dragging = dragId == l.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[l.id] = it.height }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f; alpha = if (dragging) 0.85f else 1f }
                            .background(if (dragging) C.selected else Color.Transparent)
                            .then(dragModifier(l, afterLongPress = true))
                            .clickable { editing = l }
                    ) {
                        Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.size(width = 24.dp, height = 44.dp).then(dragModifier(l, afterLongPress = false))
                                    .semantics { contentDescription = "Drag to reorder" },
                                contentAlignment = Alignment.Center,
                            ) { Icon(UI.Drag, null, tint = C.faint, modifier = Modifier.size(width = 10.dp, height = 16.dp)) }
                            Dot(color, 8.dp)
                            Text(l.name.uppercase(), style = Ty.section, color = color, modifier = Modifier.weight(1f))
                            Text((counts[l.id] ?: 0).toString(), style = Ty.mono, color = C.muted)
                            IconBtn(UI.Trash, "Delete label", { deleting = l }, tint = C.danger, iconSize = 16.dp)
                        }
                        HDivider()
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (creating) {
        UDialog({ creating = false }, "New label") {
            NewLabelForm(nextColor, { name, color -> onAdd(name, color); creating = false }, { creating = false })
        }
    }

    editing?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        var color by remember(l.id) { mutableStateOf(l.color) }
        UDialog({ editing = null }, "Edit label") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UField(name, { name = it.take(24) }, "Name", Modifier.fillMaxWidth(), fieldModifier = Modifier.focusRequester(rememberAutoFocus()))
                HDivider()
                SwatchRow(LABEL_COLOURS.map { it.key }, color, { color = it })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButtonU("Delete", { editing = null; deleting = l }, color = C.danger)
                Spacer(Modifier.weight(1f))
                TextButtonU("Cancel", { editing = null }, color = C.muted)
                TextButtonU("Save", { onUpdate(l.copy(name = name.trim().ifBlank { l.name }, color = color)); editing = null })
            }
        }
    }
    deleting?.let { l ->
        ConfirmDialog(
            "Delete “${l.name}”?",
            "Its notes are kept; they just won't have a label any more.",
            "Delete", { onDelete(l) }, { deleting = null },
        )
    }
}

