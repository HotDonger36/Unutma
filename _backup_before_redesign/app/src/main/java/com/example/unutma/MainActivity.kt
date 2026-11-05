package com.example.unutma

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unutma.ui.theme.UnutmaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Checklist helpers ────────────────────────────────────────────────────────

data class CheckItem(val text: String, val checked: Boolean)

fun parseCheckItems(raw: String): List<CheckItem> =
    if (raw.isBlank()) listOf(CheckItem("", false))
    else raw.split("\n").mapNotNull { line ->
        if (line.length < 2) null
        else CheckItem(text = line.drop(2), checked = line.startsWith("1|"))
    }.ifEmpty { listOf(CheckItem("", false)) }

fun serializeCheckItems(items: List<CheckItem>): String =
    items.joinToString("\n") { "${if (it.checked) "1" else "0"}|${it.text}" }

// ─── Note colour palette (accent strips on cards) ────────────────────────────

val PALETTE: List<Pair<String, Color>> = listOf(
    ""       to Color.Transparent,
    "RED"    to Color(0xFFE53935),
    "ORANGE" to Color(0xFFEF6C00),
    "YELLOW" to Color(0xFFF9A825),
    "GREEN"  to Color(0xFF43A047),
    "TEAL"   to Color(0xFF00897B),
    "BLUE"   to Color(0xFF1E88E5),
    "PURPLE" to Color(0xFF8E24AA),
    "PINK"   to Color(0xFFD81B60),
)

fun noteAccentColor(key: String): Color =
    PALETTE.find { it.first == key }?.second ?: Color.Transparent

// ─── App accent presets ───────────────────────────────────────────────────────

data class AccentOption(val key: String, val color: Color, val onColor: Color)

val APP_ACCENTS = listOf(
    AccentOption("PURPLE", Color(0xFFBB86FC), Color.Black),
    AccentOption("BLUE",   Color(0xFF2196F3), Color.White),
    AccentOption("TEAL",   Color(0xFF00BCD4), Color.Black),
    AccentOption("GREEN",  Color(0xFF4CAF50), Color.Black),
    AccentOption("YELLOW", Color(0xFFFFC107), Color.Black),
    AccentOption("ORANGE", Color(0xFFFF5722), Color.White),
    AccentOption("RED",    Color(0xFFF44336), Color.White),
    AccentOption("PINK",   Color(0xFFE91E63), Color.White),
)

// ─── Persistent settings ──────────────────────────────────────────────────────

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class SortOrder  { DEFAULT, TITLE_AZ, TITLE_ZA, COLOR }

object AppSettings {
    private const val PREFS = "unutma_prefs"

    fun getThemeMode(ctx: Context): ThemeMode =
        ThemeMode.valueOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        )
    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode.name).apply()
    }

    fun getSortOrder(ctx: Context): SortOrder =
        SortOrder.valueOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("sort_order", SortOrder.DEFAULT.name) ?: SortOrder.DEFAULT.name
        )
    fun setSortOrder(ctx: Context, order: SortOrder) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("sort_order", order.name).apply()
    }

    fun getRemindersEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("reminders_enabled", true)
    fun setRemindersEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("reminders_enabled", enabled).apply()
    }

    fun getAccentKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("accent_key", "PURPLE") ?: "PURPLE"
    fun setAccentKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("accent_key", key).apply()
    }
}

// ─── Screen enum ──────────────────────────────────────────────────────────────

private enum class Screen { LIST, EDITOR, SETTINGS }

// ─── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db  = NoteDatabase.getInstance(applicationContext)
        val dao = db.noteDao()
        setContent {
            var themeMode by remember { mutableStateOf(AppSettings.getThemeMode(applicationContext)) }
            var accentKey by remember { mutableStateOf(AppSettings.getAccentKey(applicationContext)) }
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM -> systemDark
            }
            val currentAccent = APP_ACCENTS.firstOrNull { it.key == accentKey } ?: APP_ACCENTS[0]
            UnutmaTheme(
                darkTheme     = isDark,
                accentColor   = currentAccent.color,
                onAccentColor = currentAccent.onColor
            ) {
                UnutmaApp(
                    dao               = dao,
                    themeMode         = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        AppSettings.setThemeMode(applicationContext, mode)
                    },
                    accentKey         = accentKey,
                    onAccentKeyChange = { key ->
                        accentKey = key
                        AppSettings.setAccentKey(applicationContext, key)
                    }
                )
            }
        }
    }
}

// ─── App root ─────────────────────────────────────────────────────────────────

@Composable
fun UnutmaApp(
    dao: NoteDao,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentKey: String,
    onAccentKeyChange: (String) -> Unit
) {
    val ctx   = LocalContext.current
    val notes by dao.getAllNotes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var screen        by remember { mutableStateOf(Screen.LIST) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds   by remember { mutableStateOf(setOf<Int>()) }
    var editingNote   by remember { mutableStateOf<Note?>(null) }
    var pendingType   by remember { mutableStateOf(NOTE_TYPE_TEXT) }
    var showDeleteDlg by remember { mutableStateOf(false) }
    var fabExpanded   by remember { mutableStateOf(false) }
    var sortOrder     by remember { mutableStateOf(AppSettings.getSortOrder(ctx)) }

    BackHandler(enabled = fabExpanded || selectionMode || screen != Screen.LIST) {
        when {
            fabExpanded              -> fabExpanded = false
            screen == Screen.EDITOR  -> screen = Screen.LIST
            screen == Screen.SETTINGS -> screen = Screen.LIST
            selectionMode            -> { selectionMode = false; selectedIds = setOf() }
        }
    }

    if (showDeleteDlg) {
        AlertDialog(
            onDismissRequest = { showDeleteDlg = false },
            title   = { Text("Delete notes?") },
            text    = {
                val n = selectedIds.size
                Text("Delete $n note${if (n != 1) "s" else ""}? This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deleteByIds(selectedIds.toList()) }
                    selectedIds = setOf(); selectionMode = false; showDeleteDlg = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDlg = false }) { Text("Cancel") }
            }
        )
    }

    when (screen) {
        Screen.LIST -> ListScreen(
            notes             = notes,
            selectionMode     = selectionMode,
            selectedIds       = selectedIds,
            fabExpanded       = fabExpanded,
            sortOrder         = sortOrder,
            onNoteClick       = { note ->
                if (selectionMode) {
                    selectedIds = if (note.id in selectedIds) selectedIds - note.id
                                  else selectedIds + note.id
                } else {
                    editingNote = note; pendingType = note.type; screen = Screen.EDITOR
                }
            },
            onNoteLongPress   = { note -> selectionMode = true; selectedIds = selectedIds + note.id },
            onCancelSelection = { selectionMode = false; selectedIds = setOf() },
            onFabClick        = { fabExpanded = !fabExpanded },
            onFabDismiss      = { fabExpanded = false },
            onNewNote         = { type ->
                fabExpanded = false; editingNote = null; pendingType = type; screen = Screen.EDITOR
            },
            onDeleteSelected  = { showDeleteDlg = true },
            onColorSelected   = { key ->
                val ids = selectedIds.toList()
                scope.launch { notes.filter { it.id in ids }.forEach { dao.update(it.copy(color = key)) } }
                selectedIds = setOf(); selectionMode = false
            },
            onPinToggle       = {
                val ids = selectedIds.toList()
                scope.launch {
                    val sel       = notes.filter { it.id in ids }
                    val allPinned = sel.all { it.isPinned }
                    sel.forEach { dao.update(it.copy(isPinned = !allPinned)) }
                }
                selectedIds = setOf(); selectionMode = false
            },
            onArchiveSelected = {
                val ids = selectedIds.toList()
                scope.launch { notes.filter { it.id in ids }.forEach { dao.update(it.copy(isArchived = true)) } }
                selectedIds = setOf(); selectionMode = false
            },
            onSettingsClick   = { screen = Screen.SETTINGS }
        )
        Screen.EDITOR -> EditorScreen(
            note     = editingNote,
            noteType = pendingType,
            onSave   = { title, desc ->
                val n = editingNote
                scope.launch {
                    if (n == null) dao.insert(Note(title = title, description = desc, type = pendingType))
                    else           dao.update(n.copy(title = title, description = desc))
                }
                screen = Screen.LIST
            },
            onBack = { screen = Screen.LIST }
        )
        Screen.SETTINGS -> SettingsScreen(
            themeMode         = themeMode,
            onThemeModeChange = onThemeModeChange,
            sortOrder         = sortOrder,
            onSortOrderChange = { order ->
                sortOrder = order
                AppSettings.setSortOrder(ctx, order)
            },
            accentKey         = accentKey,
            onAccentKeyChange = onAccentKeyChange,
            onBack            = { screen = Screen.LIST }
        )
    }
}

// ─── List Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(
    notes: List<Note>,
    selectionMode: Boolean,
    selectedIds: Set<Int>,
    fabExpanded: Boolean,
    sortOrder: SortOrder,
    onNoteClick: (Note) -> Unit,
    onNoteLongPress: (Note) -> Unit,
    onCancelSelection: () -> Unit,
    onFabClick: () -> Unit,
    onFabDismiss: () -> Unit,
    onNewNote: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onColorSelected: (String) -> Unit,
    onPinToggle: () -> Unit,
    onArchiveSelected: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var isSearching  by remember { mutableStateOf(false) }
    var searchQuery  by remember { mutableStateOf("") }
    var showColorDlg by remember { mutableStateOf(false) }
    val searchFocus  = remember { FocusRequester() }

    LaunchedEffect(isSearching) { if (isSearching) searchFocus.requestFocus() }
    LaunchedEffect(selectionMode) { if (selectionMode) { isSearching = false; searchQuery = "" } }

    val sortedNotes = remember(notes, sortOrder) {
        fun sortGroup(list: List<Note>) = when (sortOrder) {
            SortOrder.DEFAULT  -> list
            SortOrder.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_ZA -> list.sortedByDescending { it.title.lowercase() }
            SortOrder.COLOR    -> list.sortedBy { n ->
                PALETTE.indexOfFirst { p -> p.first == n.color }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
        }
        sortGroup(notes.filter { it.isPinned }) + sortGroup(notes.filter { !it.isPinned })
    }

    val displayNotes = remember(sortedNotes, searchQuery, isSearching) {
        if (isSearching && searchQuery.isNotBlank())
            sortedNotes.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
            }
        else sortedNotes
    }

    if (showColorDlg) {
        AlertDialog(
            onDismissRequest = { showColorDlg = false },
            title = { Text("Set colour", style = MaterialTheme.typography.titleMedium) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(PALETTE.take(5), PALETTE.drop(5)).forEach { row ->
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            row.forEach { (key, accent) ->
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (key.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                            else accent
                                        )
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                                        .clickable { onColorSelected(key); showColorDlg = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (key.isEmpty()) Icon(
                                        Icons.Default.Block, null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showColorDlg = false }) { Text("Cancel") } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        when {
                            isSearching -> TextField(
                                value       = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text("Search notes…",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                                },
                                singleLine  = true,
                                colors      = TextFieldDefaults.colors(
                                    focusedContainerColor   = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor   = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor  = Color.Transparent
                                ),
                                textStyle  = TextStyle(fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface),
                                modifier   = Modifier.fillMaxWidth().focusRequester(searchFocus)
                            )
                            selectionMode -> Text("${selectedIds.size} selected", fontSize = 20.sp)
                            else -> Text("Unutma", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                        }
                    },
                    navigationIcon = {
                        if (isSearching) {
                            IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                Icon(Icons.Default.ArrowBack, "Close search")
                            }
                        }
                    },
                    actions = {
                        when {
                            isSearching -> {
                                if (searchQuery.isNotEmpty())
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, "Clear")
                                    }
                            }
                            selectionMode -> {
                                IconButton(onClick = onCancelSelection) {
                                    Icon(Icons.Default.Close, "Cancel")
                                }
                            }
                            else -> {
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(Icons.Default.Search, "Search")
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(Icons.Default.Settings, "Settings")
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background)
                )
            },
            floatingActionButton = {
                if (!selectionMode) {
                    FloatingActionButton(
                        onClick = {
                            if (isSearching) { isSearching = false; searchQuery = "" }
                            else onFabClick()
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor   = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(if (fabExpanded) Icons.Default.Close else Icons.Default.Add, null)
                    }
                }
            },
            bottomBar = {
                if (selectionMode) {
                    BottomAppBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        IconButton(onClick = { showColorDlg = true }) {
                            Icon(Icons.Default.Palette, "Set colour")
                        }
                        IconButton(onClick = onPinToggle) {
                            Icon(Icons.Default.PushPin, "Pin / Unpin")
                        }
                        IconButton(onClick = onArchiveSelected) {
                            Icon(Icons.Default.Archive, "Archive")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDeleteSelected) {
                            Icon(Icons.Default.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            if (displayNotes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isSearching) Icons.Default.SearchOff else Icons.Default.NoteAlt,
                            null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                        )
                        Text(
                            if (isSearching) "No results for \"$searchQuery\"" else "No notes yet",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (!isSearching) Text(
                            "Tap + to add your first note",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start  = 12.dp, end = 12.dp,
                        top    = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayNotes, key = { it.id }) { note ->
                        NoteCard(
                            note          = note,
                            selectionMode = selectionMode,
                            isSelected    = note.id in selectedIds,
                            onClick       = { onNoteClick(note) },
                            onLongPress   = { onNoteLongPress(note) }
                        )
                    }
                }
            }
        }

        // Scrim behind FAB speed-dial
        AnimatedVisibility(
            visible = fabExpanded && !selectionMode,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { onFabDismiss() }
            )
        }

        // Speed dial
        if (fabExpanded && !selectionMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 84.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SpeedDialItem("List", Icons.Default.FormatListBulleted) { onNewNote(NOTE_TYPE_LIST) }
                SpeedDialItem("Text", Icons.Default.Edit)               { onNewNote(NOTE_TYPE_TEXT) }
            }
        }
    }
}

@Composable
fun SpeedDialItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape         = RoundedCornerShape(8.dp),
            color         = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp, shadowElevation = 4.dp
        ) {
            Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
        }
        SmallFloatingActionButton(
            onClick        = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor   = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    }
}

// ─── Note Card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val accent    = noteAccentColor(note.color)
    val hasAccent = note.color.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .then(
                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            ),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (hasAccent) {
                Box(
                    Modifier.width(5.dp).fillMaxHeight()
                        .background(accent, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start  = if (hasAccent) 10.dp else 14.dp,
                        end    = 0.dp,
                        top    = 12.dp,
                        bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (note.title.isNotBlank() || note.isPinned) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (note.title.isNotBlank()) {
                                Text(
                                    note.title,
                                    fontWeight = FontWeight.Medium, fontSize = 15.sp,
                                    maxLines   = 1, overflow = TextOverflow.Ellipsis,
                                    color      = MaterialTheme.colorScheme.onSurface,
                                    modifier   = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (note.isPinned) Icon(
                                Icons.Default.PushPin, "Pinned", Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (note.description.isNotBlank()) {
                        if (note.type == NOTE_TYPE_LIST) {
                            val items = parseCheckItems(note.description)
                            items.take(3).forEach { item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        if (item.checked) Icons.Default.CheckBox
                                        else Icons.Default.CheckBoxOutlineBlank,
                                        null, Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        item.text, fontSize = 13.sp,
                                        color  = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (item.checked) 0.4f else 0.7f),
                                        style  = LocalTextStyle.current.copy(
                                            textDecoration = if (item.checked) TextDecoration.LineThrough else null),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (items.size > 3) Text(
                                "+${items.size - 3} more", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        } else {
                            Text(
                                note.description, fontSize = 13.sp,
                                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (selectionMode) Checkbox(checked = isSelected, onCheckedChange = null)
                }
            }
        }
    }
}

// ─── Editor Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note?,
    noteType: String,
    onSave: (title: String, desc: String) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf(note?.title ?: "") }

    // Use TextFieldValue for body so we can track selection for bold/italic
    var bodyTfv by remember {
        mutableStateOf(
            TextFieldValue(if (noteType == NOTE_TYPE_TEXT) (note?.description ?: "") else "")
        )
    }

    // ── Undo / Redo stacks ────────────────────────────────────────────────────
    val undoStack = remember { ArrayDeque<String>() }
    val redoStack = remember { ArrayDeque<String>() }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    val updateBody: (TextFieldValue) -> Unit = { newVal ->
        if (newVal.text != bodyTfv.text) {
            undoStack.addLast(bodyTfv.text)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
            canUndo = true
            canRedo = false
        }
        bodyTfv = newVal
    }

    val undo: () -> Unit = {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(bodyTfv.text)
            val prev = undoStack.removeLast()
            bodyTfv = TextFieldValue(prev, selection = TextRange(prev.length))
            canUndo = undoStack.isNotEmpty()
            canRedo = true
        }
    }

    val redo: () -> Unit = {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(bodyTfv.text)
            val next = redoStack.removeLast()
            bodyTfv = TextFieldValue(next, selection = TextRange(next.length))
            canUndo = true
            canRedo = redoStack.isNotEmpty()
        }
    }

    // Bold: wraps selection with **…**, or inserts ****  with cursor in the middle
    val applyBold: () -> Unit = {
        val sel = bodyTfv.selection
        val t   = bodyTfv.text
        if (sel.collapsed) {
            val newText = t.substring(0, sel.start) + "****" + t.substring(sel.start)
            updateBody(bodyTfv.copy(text = newText, selection = TextRange(sel.start + 2)))
        } else {
            val newText = t.substring(0, sel.start) + "**" +
                          t.substring(sel.start, sel.end) + "**" +
                          t.substring(sel.end)
            updateBody(bodyTfv.copy(text = newText, selection = TextRange(sel.end + 4)))
        }
    }

    // Italic: wraps selection with *…*, or inserts ** with cursor in the middle
    val applyItalic: () -> Unit = {
        val sel = bodyTfv.selection
        val t   = bodyTfv.text
        if (sel.collapsed) {
            val newText = t.substring(0, sel.start) + "**" + t.substring(sel.start)
            updateBody(bodyTfv.copy(text = newText, selection = TextRange(sel.start + 1)))
        } else {
            val newText = t.substring(0, sel.start) + "*" +
                          t.substring(sel.start, sel.end) + "*" +
                          t.substring(sel.end)
            updateBody(bodyTfv.copy(text = newText, selection = TextRange(sel.end + 2)))
        }
    }

    // ── Checklist state ───────────────────────────────────────────────────────
    val checkItems = remember {
        mutableStateListOf<CheckItem>().also { list ->
            if (noteType == NOTE_TYPE_LIST) {
                if (note != null && note.description.isNotBlank())
                    list.addAll(parseCheckItems(note.description))
                else
                    list.add(CheckItem("", false))
            }
        }
    }

    // Parallel FocusRequesters, one per checklist item
    val focusRequesters = remember { mutableListOf<FocusRequester>() }
    while (focusRequesters.size < checkItems.size) focusRequesters.add(FocusRequester())

    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pendingFocusIndex) {
        val idx = pendingFocusIndex ?: return@LaunchedEffect
        delay(30)
        if (idx < focusRequesters.size) focusRequesters[idx].requestFocus()
        pendingFocusIndex = null
    }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (note == null) titleFocus.requestFocus() }

    fun doSave() {
        val desc = if (noteType == NOTE_TYPE_LIST)
            serializeCheckItems(checkItems.filter { it.text.isNotBlank() })
        else bodyTfv.text
        if (title.isBlank() && desc.isBlank()) { onBack(); return }
        onSave(title, desc)
    }

    val transparentField = TextFieldDefaults.colors(
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor   = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor  = Color.Transparent
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { doSave() }) {
                        Icon(Icons.Default.ArrowBack, "Save and go back")
                    }
                },
                title  = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background)
            )
        },
        // ── Formatting toolbar (text notes only) ──────────────────────────────
        bottomBar = {
            if (noteType == NOTE_TYPE_TEXT) {
                Surface(
                    color           = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Undo
                        IconButton(onClick = undo, enabled = canUndo) {
                            Icon(
                                Icons.Default.Undo, "Undo",
                                tint = if (canUndo) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                        // Redo
                        IconButton(onClick = redo, enabled = canRedo) {
                            Icon(
                                Icons.Default.Redo, "Redo",
                                tint = if (canRedo) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                        // Separator
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                        )
                        Spacer(Modifier.width(4.dp))
                        // Bold
                        IconButton(onClick = applyBold) {
                            Text(
                                "B",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize   = 16.sp,
                                color      = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // Italic
                        IconButton(onClick = applyItalic) {
                            Text(
                                "I",
                                fontStyle = FontStyle.Italic,
                                fontSize  = 16.sp,
                                color     = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            // Title field
            TextField(
                value         = title,
                onValueChange = { title = it },
                placeholder   = {
                    Text("Title", fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                },
                textStyle = TextStyle(
                    fontSize   = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurface
                ),
                colors     = transparentField,
                singleLine = true,
                modifier   = Modifier.fillMaxWidth().focusRequester(titleFocus)
            )

            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            if (noteType == NOTE_TYPE_TEXT) {
                // Body with TextFieldValue for selection-aware formatting
                TextField(
                    value         = bodyTfv,
                    onValueChange = { updateBody(it) },
                    placeholder   = {
                        Text("Note…", fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    },
                    textStyle = TextStyle(
                        fontSize   = 16.sp,
                        lineHeight = 24.sp,
                        color      = MaterialTheme.colorScheme.onSurface
                    ),
                    colors   = transparentField,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp)
                )
            } else {
                // Checklist editor
                Spacer(Modifier.height(4.dp))
                checkItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked         = item.checked,
                            onCheckedChange = { checked -> checkItems[index] = item.copy(checked = checked) }
                        )
                        TextField(
                            value         = item.text,
                            onValueChange = { newText ->
                                if ('\n' in newText) {
                                    val parts   = newText.split("\n", limit = 2)
                                    checkItems[index] = item.copy(text = parts[0])
                                    val nextIdx = index + 1
                                    val carry   = parts.getOrElse(1) { "" }
                                    checkItems.add(nextIdx, CheckItem(carry, false))
                                    focusRequesters.add(nextIdx, FocusRequester())
                                    pendingFocusIndex = nextIdx
                                } else {
                                    checkItems[index] = item.copy(text = newText)
                                }
                            },
                            placeholder = {
                                Text("List item",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            },
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                color    = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (item.checked) 0.45f else 1f),
                                textDecoration = if (item.checked) TextDecoration.LineThrough
                                                 else TextDecoration.None
                            ),
                            colors          = transparentField,
                            singleLine      = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = {
                                val nextIdx = index + 1
                                checkItems.add(nextIdx, CheckItem("", false))
                                focusRequesters.add(nextIdx, FocusRequester())
                                pendingFocusIndex = nextIdx
                            }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(
                                    if (index < focusRequesters.size) focusRequesters[index]
                                    else FocusRequester()
                                )
                        )
                        if (checkItems.size > 1) {
                            IconButton(
                                onClick  = {
                                    checkItems.removeAt(index)
                                    if (index < focusRequesters.size) focusRequesters.removeAt(index)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, "Remove item",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        } else {
                            Spacer(Modifier.size(32.dp))
                        }
                    }
                }
                TextButton(
                    onClick  = {
                        val idx = checkItems.size
                        checkItems.add(CheckItem("", false))
                        focusRequesters.add(FocusRequester())
                        pendingFocusIndex = idx
                    },
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add item")
                }
            }
        }
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    accentKey: String,
    onAccentKeyChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var remindersEnabled by remember { mutableStateOf(AppSettings.getRemindersEnabled(ctx)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {

            /* ── Appearance ── */
            item { SettingsSectionHeader("Appearance") }

            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

                    // Theme mode
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Brightness6, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(
                            ThemeMode.DARK   to "Dark",
                            ThemeMode.LIGHT  to "Light",
                            ThemeMode.SYSTEM to "System"
                        ).forEachIndexed { idx, (mode, label) ->
                            SegmentedButton(
                                shape    = SegmentedButtonDefaults.itemShape(index = idx, count = 3),
                                onClick  = { onThemeModeChange(mode) },
                                selected = themeMode == mode,
                                label    = { Text(label, fontSize = 13.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Accent colour picker
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, null, Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        APP_ACCENTS.forEach { option ->
                            val selected = accentKey == option.key
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(option.color)
                                    .clickable { onAccentKeyChange(option.key) }
                                    .then(
                                        if (selected)
                                            Modifier.border(
                                                3.dp,
                                                MaterialTheme.colorScheme.onBackground,
                                                CircleShape
                                            )
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check, "Selected",
                                        tint     = option.onColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SettingsDivider() }

            /* ── Notes ── */
            item { SettingsSectionHeader("Notes") }

            item {
                var expanded by remember { mutableStateOf(false) }
                val labels = mapOf(
                    SortOrder.DEFAULT  to "Default (newest first)",
                    SortOrder.TITLE_AZ to "Title A → Z",
                    SortOrder.TITLE_ZA to "Title Z → A",
                    SortOrder.COLOR    to "By colour"
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Sort, null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text("Default sort order", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(labels[sortOrder] ?: "Default", fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(labels[order] ?: "") },
                                    onClick = { onSortOrderChange(order); expanded = false },
                                    leadingIcon = {
                                        if (sortOrder == order)
                                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                        else
                                            Spacer(Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsDivider() }

            /* ── Notifications ── */
            item { SettingsSectionHeader("Notifications") }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Enable reminders", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Show a notification when a reminder fires",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked         = remindersEnabled,
                        onCheckedChange = { enabled ->
                            remindersEnabled = enabled
                            AppSettings.setRemindersEnabled(ctx, enabled)
                        }
                    )
                }
            }

            item { SettingsDivider() }

            /* ── About ── */
            item { SettingsSectionHeader("About") }

            item {
                // Fix: override containerColor to match the screen background
                ListItem(
                    headlineContent   = { Text("App version") },
                    supportingContent = { Text("1.0.0") },
                    leadingContent    = {
                        Icon(Icons.Default.Info, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background)
                )
            }

            item {
                ListItem(
                    headlineContent   = { Text("Send feedback") },
                    supportingContent = { Text("Report a bug or suggest a feature") },
                    leadingContent    = {
                        Icon(Icons.Default.Email, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                            putExtra(Intent.EXTRA_SUBJECT, "Unutma – feedback")
                        }
                        ctx.startActivity(intent)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background)
                )
            }
        }
    }
}

// ─── Settings helpers ─────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp),
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
