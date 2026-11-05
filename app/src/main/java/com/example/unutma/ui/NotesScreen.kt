package com.example.unutma.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalContext
import com.example.unutma.data.Prefs
import com.example.unutma.data.Routines
import androidx.compose.ui.unit.dp
import com.example.unutma.Label
import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.NOTE_TYPE_TEXT
import com.example.unutma.Note
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_WEEKLY
import com.example.unutma.data.Block
import com.example.unutma.data.SortOrder
import com.example.unutma.data.checkProgress
import com.example.unutma.data.hasAudio
import com.example.unutma.data.hasChecks
import com.example.unutma.data.hasImage
import com.example.unutma.data.hasText
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.plainText
import com.example.unutma.data.previewLine
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.labelColor
import com.example.unutma.util.Fmt

/** A note with its parsed body, so the list does not re-parse on every frame. */
class NoteRow(val note: Note, val blocks: List<Block>) {
    val title = noteDisplayTitle(note, blocks)
    val preview = blocks.previewLine()
    val progress = blocks.checkProgress()
    val search: String by lazy { (note.title + "\n" + blocks.plainText()).lowercase() }
    /** The next date this note cares about, for "Coming up first". */
    val nextDate: Long? = listOfNotNull(note.reminderAt, note.countdownAt, note.expiresAt).minOrNull()
}

private enum class Filter(val label: String, val test: (NoteRow) -> Boolean) {
    LISTS("Checklists", { it.blocks.hasChecks() }),
    TEXT("Text", { it.blocks.hasText() }),
    REMINDERS("Reminders", { it.note.reminderAt != null }),
    DATES("Dates", { it.note.countdownAt != null || it.note.expiresAt != null }),
    IMAGES("Images", { it.blocks.hasImage() }),
    VOICE("Voice", { it.blocks.hasAudio() }),
    BAR("In notification bar", { it.note.pinnedToBar }),
    LOCKED("Locked", { it.note.isLocked }),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    notes: List<Note>,
    labels: List<Label>,
    sortOrder: SortOrder,
    onOpen: (Note) -> Unit,
    onNew: (type: String, labelId: Int?) -> Unit,
    onVoice: () -> Unit,
    onArchive: () -> Unit,
    onDeleted: () -> Unit,
    onLabels: () -> Unit,
    onSettings: () -> Unit,
    onFeedback: () -> Unit,
    onSetLabel: (ids: Set<Int>, labelId: Int?) -> Unit,
    onTogglePinTop: (ids: Set<Int>) -> Unit,
    onToggleLock: (ids: Set<Int>) -> Unit,
    onArchiveNotes: (ids: Set<Int>) -> Unit,
    onDeleteNotes: (ids: Set<Int>) -> Unit,
    onCreateLabelFor: (ids: Set<Int>, name: String, color: String) -> Unit,
    /** Shown above the label sections: today's calendar and the routines card. */
    topContent: @Composable () -> Unit = {},
    /** Called when you drag a note into place while another sort is on: switch to "My order". */
    onSortCustom: () -> Unit = {},
) {
    val rows = remember(notes) { notes.map { NoteRow(it, parseBlocks(it)) } }
    var tab by rememberSaveable { mutableStateOf<Int?>(null) }     // null = All
    var menuOpen by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf<Filter>()) }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var selecting by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    var labelDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (tab != null && labels.none { it.id == tab }) tab = null
    val inSelection = selecting || selection.isNotEmpty()

    BackHandler(enabled = inSelection || searching || fabOpen) {
        when {
            fabOpen -> fabOpen = false
            inSelection -> { selection = emptySet(); selecting = false }
            searching -> { searching = false; query = ""; filters = emptySet() }
        }
    }

    // Filter + search
    val q = query.trim().lowercase()
    val visible = rows.filter { r ->
        (tab == null || r.note.labelId == tab) &&
            (q.isEmpty() || (if (r.note.isLocked) r.note.title.lowercase().contains(q) else r.search.contains(q)) ||
                labels.firstOrNull { it.id == r.note.labelId }?.name?.lowercase()?.contains(q) == true) &&
            filters.all { f -> f.test(r) }
    }

    // Your own order (hold a note and drag it): kept per section in Prefs, used with the "My order" sort.
    val prefs = Prefs.get(LocalContext.current)
    val customOrders = remember { mutableStateMapOf<String, List<Int>>() }
    var dragSection by remember { mutableStateOf<String?>(null) }
    var dragOrder by remember { mutableStateOf<List<Int>>(emptyList()) }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { HashMap<Int, Int>() }
    fun orderFor(key: String): List<Int>? =
        if (dragSection == key) dragOrder
        else if (sortOrder == SortOrder.CUSTOM) customOrders.getOrPut(key) { prefs.noteOrder(key) }.takeIf { it.isNotEmpty() }
        else null

    fun sorted(list: List<NoteRow>, key: String): List<NoteRow> {
        orderFor(key)?.let { order ->
            // Notes not in your order yet (new ones) go on top, newest first.
            val idx = order.withIndex().associate { it.value to it.index }
            return list.sortedWith(compareBy<NoteRow> { idx[it.note.id] ?: -1 }.thenByDescending { it.note.updatedAt })
        }
        val bySort = when (sortOrder) {
            SortOrder.CUSTOM -> list.sortedWith(compareByDescending<NoteRow> { it.note.updatedAt }.thenByDescending { it.note.id })
            SortOrder.NEWEST -> list.sortedWith(compareByDescending<NoteRow> { it.note.updatedAt }.thenByDescending { it.note.id })
            SortOrder.OLDEST -> list.sortedWith(compareBy<NoteRow> { it.note.updatedAt }.thenBy { it.note.id })
            SortOrder.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_ZA -> list.sortedByDescending { it.title.lowercase() }
            SortOrder.UPCOMING -> list.sortedWith(compareBy<NoteRow> { it.nextDate ?: Long.MAX_VALUE }.thenByDescending { it.note.updatedAt })
        }
        return bySort.sortedByDescending { it.note.isPinned } // stable: pinned first, order kept
    }

    // Notes without a label first, then one coloured section per label.
    data class Section(val key: String, val title: String, val color: Color, val labelId: Int?, val rows: List<NoteRow>)
    val sections = buildList {
        if (tab == null) {
            val r = visible.filter { n -> n.note.labelId == null || labels.none { it.id == n.note.labelId } }
            if (r.isNotEmpty()) add(Section("none", if (labels.isEmpty()) "Notes" else "No label", C.muted, null, sorted(r, "none")))
        }
        for (l in labels) {
            if (tab != null && tab != l.id) continue
            val r = visible.filter { it.note.labelId == l.id }
            if (r.isNotEmpty()) add(Section("l${l.id}", l.name, labelColor(l.color), l.id, sorted(r, "l${l.id}")))
        }
    }

    /**
     * Holding a note: let go without moving and it's selected (as before); drag it and it moves
     * within its section. Dropping it switches the sort to "My order".
     */
    fun noteDrag(sectionKey: String, sectionIds: List<Int>, id: Int): Modifier = Modifier.pointerInput(sectionKey, id, inSelection, q) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                dragMoved = 0f; dragOffset = 0f; dragId = id
                if (!inSelection && q.isEmpty()) { dragSection = sectionKey; dragOrder = sectionIds }
            },
            onDragEnd = {
                val wasDrag = dragSection != null && kotlin.math.abs(dragMoved) > 12f
                if (wasDrag) {
                    customOrders[sectionKey] = dragOrder
                    prefs.setNoteOrder(sectionKey, dragOrder)
                    if (sortOrder != SortOrder.CUSTOM) onSortCustom()
                } else selection = if (id in selection) selection - id else selection + id
                dragSection = null; dragId = null; dragOffset = 0f
            },
            onDragCancel = { dragSection = null; dragId = null; dragOffset = 0f },
        ) { change, amount ->
            if (dragSection == null) return@detectDragGesturesAfterLongPress
            change.consume()
            dragOffset += amount.y; dragMoved += amount.y
            val list = dragOrder.toMutableList()
            val i = list.indexOf(id)
            if (dragOffset > 0 && i in 0 until list.lastIndex) {
                val h = rowHeights[list[i + 1]] ?: 0
                if (h > 0 && dragOffset > h / 2f) { list.add(i + 1, list.removeAt(i)); dragOffset -= h; dragOrder = list }
            } else if (dragOffset < 0 && i > 0) {
                val h = rowHeights[list[i - 1]] ?: 0
                if (h > 0 && -dragOffset > h / 2f) { list.add(i - 1, list.removeAt(i)); dragOffset += h; dragOrder = list }
            }
        }
    }

    // Sections folded away by tapping their header (remembered between visits). Searching shows everything.
    var collapsed by remember { mutableStateOf(prefs.collapsedSections()) }
    fun toggleSection(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        prefs.setCollapsedSections(collapsed)
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ─── Header ───
            Box(Modifier.padding(start = 24.dp, end = 12.dp, top = 22.dp).heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    inSelection -> SelectionBar(
                        count = selection.size,
                        // "All" means every note shown right now (respects the label tab and search).
                        allSelected = visible.isNotEmpty() && visible.all { it.note.id in selection },
                        onClose = { selection = emptySet(); selecting = false },
                        onSelectAll = {
                            val ids = visible.map { it.note.id }.toSet()
                            selection = if (ids.isNotEmpty() && selection.containsAll(ids)) selection - ids else selection + ids
                            selecting = true
                        },
                        onLabel = { if (selection.isNotEmpty()) labelDialog = true },
                        allPinned = selection.isNotEmpty() && notes.filter { it.id in selection }.all { it.isPinned },
                        allLocked = selection.isNotEmpty() && notes.filter { it.id in selection }.all { it.isLocked },
                        onPinTop = { onTogglePinTop(selection); selection = emptySet(); selecting = false },
                        onLock = { onToggleLock(selection); selection = emptySet(); selecting = false },
                        onArchive = { onArchiveNotes(selection); selection = emptySet(); selecting = false },
                        onDelete = { if (selection.isNotEmpty()) confirmDelete = true },
                    )
                    searching -> SearchBar(query, { query = it }, onClose = { searching = false; query = ""; filters = emptySet() })
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("UNUTMA", style = Ty.logo, color = C.logo, modifier = Modifier.weight(1f).semantics { contentDescription = "Unutma" })
                        IconBtn(UI.Search, "Search", { searching = true })
                        Box {
                            IconBtn(UI.More, "More options", { menuOpen = true }, tint = if (menuOpen) C.logo else C.icon)
                            UMenu(menuOpen, { menuOpen = false }, DpOffset((-4).dp, 4.dp)) {
                                UMenuItem("Archive", { menuOpen = false; onArchive() })
                                UMenuItem("Recently deleted", { menuOpen = false; onDeleted() })
                                UMenuItem("Manage labels", { menuOpen = false; onLabels() })
                                UMenuItem("Select notes", { menuOpen = false; selecting = true })
                                UMenuDivider()
                                UMenuItem("Settings", { menuOpen = false; onSettings() }, icon = UI.Sliders, iconTint = C.accent, color = C.fg)
                            }
                        }
                    }
                }
            }

            if (searching) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.entries.forEach { f ->
                        FilterChipMini(f.label, f in filters) { filters = if (f in filters) filters - f else filters + f }
                    }
                }
            }

            // ─── Label tabs ───
            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(if (searching) 6.dp else 22.dp))
                LabelTabs(labels, tab) { tab = it }
            }

            // ─── Sections ───
            val showTop = tab == null && !searching && !inSelection
            if (sections.isEmpty()) {
                if (showTop) Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp)) { topContent() }
                EmptyState(
                    hasNotes = rows.isNotEmpty(),
                    filtered = q.isNotEmpty() || filters.isNotEmpty() || tab != null,
                    hasLabels = labels.isNotEmpty(),
                    onLabels = onLabels,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 120.dp),
                ) {
                    if (showTop) item(key = "top") { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { topContent(); Spacer(Modifier.height(0.dp)) } }
                    sections.forEachIndexed { si, s ->
                        // Only folds in the All view; a label's own tab always shows its notes.
                        val folded = tab == null && q.isEmpty() && s.key in collapsed
                        item(key = "h_" + s.key) {
                            if (si > 0) Spacer(Modifier.height(22.dp))
                            SectionHeader(
                                s.title, s.rows.size, s.color,
                                collapsed = folded,
                                onToggle = if (tab == null && q.isEmpty()) ({ toggleSection(s.key) }) else null,
                            )
                        }
                        if (!folded) items(s.rows, key = { "n_" + it.note.id }) { r ->
                            val dragging = dragId == r.note.id && dragSection == s.key
                            Box(
                                Modifier
                                    .onSizeChanged { rowHeights[r.note.id] = it.height }
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; alpha = if (dragging) 0.9f else 1f }
                                    .background(if (dragging) C.bg else Color.Transparent)
                            ) {
                                NoteRowView(
                                    r, s.color,
                                    // Progress ring: the label's colour, or the accent colour for notes without a label.
                                    ringColor = if (s.labelId != null) s.color else C.accent,
                                    selected = r.note.id in selection,
                                    onClick = {
                                        if (inSelection) selection = if (r.note.id in selection) selection - r.note.id else selection + r.note.id
                                        else onOpen(r.note)
                                    },
                                    onSelect = { selection = selection + r.note.id },
                                    holdModifier = noteDrag(s.key, s.rows.map { it.note.id }, r.note.id),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── New note button ───
        if (!inSelection) {
            AnimatedVisibility(fabOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(C.bg.copy(alpha = 0.6f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { fabOpen = false }
                )
            }
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (fabOpen) {
                    FabOption("Talk to create", UI.Mic, C.accent) { fabOpen = false; onVoice() }
                    FabOption("Checklist", UI.Checkbox, C.logo) { fabOpen = false; onNew(NOTE_TYPE_LIST, tab) }
                    FabOption("Note", UI.Text, C.logo) { fabOpen = false; onNew(NOTE_TYPE_TEXT, tab) }
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(C.bg)
                        .border(1.dp, C.fabBorder, CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = { if (fabOpen) { fabOpen = false; onNew(NOTE_TYPE_TEXT, tab) } else onNew(NOTE_TYPE_TEXT, tab) },
                            onLongClick = { fabOpen = !fabOpen },
                        )
                        .semantics { contentDescription = "New note. Long-press for checklist or voice" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (fabOpen) UI.Close else UI.Plus, null, tint = C.logo, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    if (labelDialog) {
        val chosen = selection
        LabelPickerDialog(
            labels, null, { onSetLabel(chosen, it); selection = emptySet(); selecting = false }, onLabels, { labelDialog = false },
            onCreate = { name, color -> onCreateLabelFor(chosen, name, color); selection = emptySet(); selecting = false },
        )
    }
    if (confirmDelete) {
        val n = selection.size
        ConfirmDialog(
            "Delete ${if (n == 1) "note" else "$n notes"}?",
            "Deleted notes stay in the archive for a while, so you can still restore them.",
            "Delete",
            { onDeleteNotes(selection); selection = emptySet(); selecting = false },
            { confirmDelete = false },
        )
    }
}

@Composable
private fun FabOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Ty.row, color = C.fg)
        Box(
            Modifier.padding(end = 4.dp).size(48.dp).clip(CircleShape).background(C.bg).border(1.dp, if (tint == C.accent) C.accent else C.menuBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun LabelTabs(labels: List<Label>, selected: Int?, onSelect: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Tab("All", null, selected == null) { onSelect(null) }
            labels.forEach { l -> Tab(l.name, labelColor(l.color), selected == l.id) { onSelect(l.id) } }
        }
        HDivider(modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun Tab(text: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(IntrinsicSize.Max).clickable(role = Role.Tab, onClick = onClick).padding(top = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 11.dp)) {
            if (dot != null) Dot(dot)
            Text(text, style = Ty.tab, color = if (selected) C.fg else C.muted, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (selected) C.fg else Color.Transparent))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRowView(
    r: NoteRow, color: Color, ringColor: Color, selected: Boolean,
    onClick: () -> Unit, onSelect: () -> Unit, holdModifier: Modifier = Modifier,
) {
    val n = r.note
    val now = System.currentTimeMillis()
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) C.selected else Color.Transparent)
            // Holding is handled by holdModifier (select, or drag to reorder); screen readers get "Select" as an action.
            .then(holdModifier)
            .semantics { onLongClick(label = "Select") { onSelect(); true } }
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 8.dp)) {
                if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(C.fg)) else Dot(color)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.title, style = Ty.rowTitle, color = C.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (n.isPinned) Icon(UI.PinTop, "Pinned to the top", tint = C.muted, modifier = Modifier.size(13.dp))
                    if (n.pinnedToBar) Icon(UI.PinBarBold, "Pinned to the notification bar", tint = C.muted, modifier = Modifier.size(13.dp))
                    if (n.isLocked) Icon(UI.Lock, "Locked", tint = C.muted, modifier = Modifier.size(13.dp))
                }
                val sub = when {
                    n.isLocked -> "Locked"
                    Routines.isRoutine(n) -> Routines.describe(n.resetMode) + (if (r.preview.isNotEmpty() && r.preview != "All done") " · " + r.preview else "")
                    else -> r.preview
                }
                if (sub.isNotEmpty()) Text(sub, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Trailing: one date chip (highlighted in the label colour when it is coming up) and/or a progress ring.
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                val remind = n.reminderAt
                val countdown = n.countdownAt
                val expires = n.expiresAt
                when {
                    remind != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val c = if (Fmt.isSoon(remind)) color else C.muted
                        Icon(UI.BellBold, "Reminder", tint = c, modifier = Modifier.size(11.dp))
                        Text(Fmt.reminderShort(remind, now), style = Ty.mono, color = c)
                    }
                    countdown != null -> Text(Fmt.countdown(countdown, now), style = Ty.mono, color = if (Fmt.isSoon(countdown)) color else C.muted)
                    expires != null -> Text(Fmt.expiryShort(expires), style = Ty.mono, color = if (Fmt.isSoon(expires, 3)) color else C.muted)
                }
                val p = r.progress
                if (p != null && !n.isLocked) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgressRing(p.first, p.second, ringColor)
                    Text("${p.first}/${p.second}", style = Ty.mono, color = C.muted)
                }
            }
        }
        HDivider()
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(UI.Search, null, tint = C.icon, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        UField(query, onQuery, "Search notes", Modifier.weight(1f).focusRequester(fr), style = Ty.rowTitle.copy(fontWeight = null))
        IconBtn(UI.Close, "Close search", onClose)
    }
    LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) {} }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    allPinned: Boolean,
    allLocked: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onLabel: () -> Unit,
    onPinTop: () -> Unit,
    onLock: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-12).dp)) {
        IconBtn(UI.Close, "Stop selecting", onClose, tint = C.fg)
        Text(
            if (count == 0) "Select" else "$count",
            style = Ty.screenTitle.copy(fontSize = Ty.rowTitle.fontSize), color = C.fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconBtn(UI.SelectAll, if (allSelected) "Deselect all" else "Select all", onSelectAll, tint = if (allSelected) C.accent else C.icon)
        val on = count > 0
        IconBtn(UI.Tag, "Set label", onLabel, enabled = on)
        IconBtn(UI.Archive, "Archive", onArchive, enabled = on)
        IconBtn(UI.Trash, "Delete", onDelete, enabled = on, tint = C.danger)
        Box {
            IconBtn(UI.More, "More actions", { more = true }, enabled = on)
            UMenu(more, { more = false }, DpOffset((-4).dp, 4.dp)) {
                UMenuItem(if (allPinned) "Unpin from top" else "Pin to top", { more = false; onPinTop() }, UI.PinTop)
                UMenuItem(if (allLocked) "Unlock" else "Lock", { more = false; onLock() }, if (allLocked) UI.Unlock else UI.Lock)
            }
        }
    }
}

@Composable
private fun EmptyState(hasNotes: Boolean, filtered: Boolean, hasLabels: Boolean, onLabels: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (filtered && hasNotes) "Nothing matches." else "Nothing to remember yet.",
            style = Ty.monoValue, color = C.muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (filtered && hasNotes) "Try another word or remove a filter." else "Tap + to write a note. Long-press it for a checklist or to talk.",
            style = Ty.rowSub, color = C.faint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!hasLabels && !filtered) {
            Spacer(Modifier.height(18.dp))
            TextButtonU("Create labels", onLabels)
        }
    }
}
