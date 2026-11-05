package com.example.unutma

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AColor
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.Settings
import com.example.unutma.data.ThemeMode
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.notify.Notifier
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.unutma.ui.CalendarScreen
import com.example.unutma.ui.HomeScaffold
import com.example.unutma.ui.HomeTab
import com.example.unutma.ui.RoutineHomeCard
import com.example.unutma.ui.RoutinesScreen
import com.example.unutma.ui.TodayBlock
import com.example.unutma.ui.rememberRoutines
import com.example.unutma.ui.ArchiveScreen
import com.example.unutma.ui.EditorScreen
import com.example.unutma.ui.EditorTarget
import com.example.unutma.ui.LabelsScreen
import com.example.unutma.ui.NotesScreen
import com.example.unutma.ui.SettingsScreen
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.UnutmaTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** The screens of the app. */
sealed interface Route {
    data object Notes : Route
    data class Edit(val target: EditorTarget) : Route
    data object Settings : Route
    data object Archive : Route
    data object Deleted : Route
    data object Help : Route
    data class Labels(val create: Boolean = false) : Route
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_NOTE = "com.example.unutma.OPEN_NOTE"
        const val ACTION_NEW_NOTE = "com.example.unutma.NEW_NOTE"
        const val ACTION_NEW_LIST = "com.example.unutma.NEW_LIST"
        const val ACTION_VOICE = "com.example.unutma.VOICE"
        const val ACTION_NEW_PHOTO = "com.example.unutma.NEW_PHOTO"
        const val ACTION_OPEN_CALENDAR = "com.example.unutma.OPEN_CALENDAR"
        const val ACTION_NEW_EVENT = "com.example.unutma.NEW_EVENT"
        const val ACTION_OPEN_ROUTINES = "com.example.unutma.OPEN_ROUTINES"
    }

    /** The latest intent from a widget or notification, handled by the UI. */
    private val incoming = mutableStateOf<Intent?>(null)

    // Unlocking on Android 7–9 uses the lock screen's own confirm screen.
    private var onUnlocked: (() -> Unit)? = null
    private lateinit var credentialLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) onUnlocked?.invoke()
            onUnlocked = null
        }
        if (savedInstanceState == null) incoming.value = intent
        Repo.scope.launch {
            com.example.unutma.data.Guide.seedIfNeeded(applicationContext) // guide notes on first launch
            Repo.maintenance(applicationContext)
        }

        setContent {
            val settings by Prefs.get(this).state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
                )
            }
            UnutmaTheme(darkTheme = dark, accentKey = settings.accentKey, textScale = settings.textSize.scale) {
                UnutmaApp(settings, incoming, ::unlock)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    /** Fingerprint/face or the phone's PIN, pattern or password. */
    fun unlock(onSuccess: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) {
            Toast.makeText(this, "Set a screen lock on your phone to protect locked notes.", Toast.LENGTH_LONG).show()
            onSuccess(); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Unlock note")
                // Strong biometrics (or the screen lock) — weak face unlock can't open the encryption key.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onSuccess() }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Unlock note", null)
            if (i == null) { onSuccess(); return }
            onUnlocked = onSuccess
            credentialLauncher.launch(i)
        }
    }
}

@Composable
fun UnutmaApp(settings: Settings, incoming: MutableState<Intent?>, unlock: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by remember { Repo.notes(ctx).observeActive() }.collectAsState(initial = emptyList())
    val archived by remember { Repo.notes(ctx).observeArchived() }.collectAsState(initial = emptyList())
    val labels by remember { Repo.labels(ctx).observeAll() }.collectAsState(initial = emptyList())
    val stack = remember { mutableStateListOf<Route>(Route.Notes) }
    var homeTab by rememberSaveable { mutableStateOf(HomeTab.NOTES) }
    var newEventRequested by remember { mutableStateOf(false) }

    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun openEditor(t: EditorTarget) {
        // Opening a note from another note replaces the editor instead of stacking many.
        if (stack.last() is Route.Edit) stack[stack.lastIndex] = Route.Edit(t) else push(Route.Edit(t))
    }

    fun open(note: Note) {
        if (note.isLocked) unlock { openEditor(EditorTarget(note.id)) } else openEditor(EditorTarget(note.id))
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) scope.launch {
            val n = Repo.createFromSpeech(ctx, text, preferList = false)
            openEditor(EditorTarget(n.id))
        }
    }
    fun talk() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a note — or “shopping list eggs, milk and bread”")
        try { speech.launch(i) } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Speech recognition isn't available on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    fun feedback() {
        val i = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "Unutma – feedback")
        }
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No email app found.", Toast.LENGTH_SHORT).show()
        }
    }

    // Widgets and notifications. Collected (not keyed on the intent) so clearing `incoming` can't cancel
    // the note lookup halfway — that used to leave you on the notes list instead of the note.
    LaunchedEffect(Unit) {
        snapshotFlow { incoming.value }.filterNotNull().collect { i ->
        incoming.value = null
        when (i.action) {
            MainActivity.ACTION_OPEN_NOTE -> {
                val id = i.getIntExtra(Notifier.EXTRA_NOTE_ID, -1)
                val n = if (id > 0) Repo.notes(ctx).get(id) else null
                if (n != null && !n.isArchived) { stack.retainAll { it == Route.Notes }; open(n) }
            }
            MainActivity.ACTION_NEW_NOTE -> { stack.retainAll { it == Route.Notes }; openEditor(EditorTarget(0, NOTE_TYPE_TEXT)) }
            MainActivity.ACTION_NEW_LIST -> { stack.retainAll { it == Route.Notes }; openEditor(EditorTarget(0, NOTE_TYPE_LIST)) }
            MainActivity.ACTION_NEW_PHOTO -> { stack.retainAll { it == Route.Notes }; openEditor(EditorTarget(0, NOTE_TYPE_TEXT, photo = true)) }
            MainActivity.ACTION_VOICE -> talk()
            MainActivity.ACTION_OPEN_CALENDAR -> { stack.retainAll { it == Route.Notes }; homeTab = HomeTab.CALENDAR }
            MainActivity.ACTION_NEW_EVENT -> { stack.retainAll { it == Route.Notes }; homeTab = HomeTab.CALENDAR; newEventRequested = true }
            MainActivity.ACTION_OPEN_ROUTINES -> { stack.retainAll { it == Route.Notes }; homeTab = HomeTab.ROUTINES }
        }
        }
    }
    // Back from Routines or Calendar goes to Notes first.
    BackHandler(enabled = stack.size == 1 && homeTab != HomeTab.NOTES) { homeTab = HomeTab.NOTES }

    BackHandler(enabled = stack.size > 1) { pop() }

    val routines = rememberRoutines(notes, labels)
    fun tick(n: Note, index: Int) { scope.launch { Repo.toggleCheck(ctx, n.id, index) } }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        when (val r = stack.last()) {
            Route.Notes -> HomeScaffold(homeTab, { homeTab = it }) {
            when (homeTab) {
            HomeTab.ROUTINES -> RoutinesScreen(routines, onOpen = ::open, onTick = ::tick)
            HomeTab.CALENDAR -> CalendarScreen(
                notes,
                onOpenNote = { id -> notes.firstOrNull { it.id == id }?.let(::open) },
                openNewEvent = newEventRequested,
                onNewEventShown = { newEventRequested = false },
            )
            HomeTab.NOTES -> NotesScreen(
                topContent = {
                    TodayBlock(0, onOpenCalendar = { homeTab = HomeTab.CALENDAR }, onOpenNote = { id -> notes.firstOrNull { it.id == id }?.let(::open) })
                    RoutineHomeCard(routines, onTick = ::tick, onOpenTab = { homeTab = HomeTab.ROUTINES })
                },
                notes = notes,
                labels = labels,
                sortOrder = settings.sortOrder,
                onOpen = ::open,
                onNew = { type, labelId -> openEditor(EditorTarget(0, type, labelId)) },
                onVoice = ::talk,
                onArchive = { push(Route.Archive) },
                onDeleted = { push(Route.Deleted) },
                onSortCustom = {
                    Prefs.get(ctx).update { it.copy(sortOrder = com.example.unutma.data.SortOrder.CUSTOM) }
                    Toast.makeText(ctx, "Sorted by your order now. Change it in Settings › Sort order.", Toast.LENGTH_SHORT).show()
                },
                onLabels = { push(Route.Labels(create = labels.isEmpty())) },
                onSettings = { push(Route.Settings) },
                onFeedback = ::feedback,
                onSetLabel = { ids, labelId -> scope.launch { Repo.saveAll(ctx, notes.filter { it.id in ids }.map { it.copy(labelId = labelId) }) } },
                onTogglePinTop = { ids ->
                    val chosen = notes.filter { it.id in ids }
                    val pin = !chosen.all { it.isPinned }
                    scope.launch { Repo.saveAll(ctx, chosen.map { it.copy(isPinned = pin) }) }
                },
                onToggleLock = { ids ->
                    val chosen = notes.filter { it.id in ids }
                    if (chosen.isNotEmpty() && chosen.all { it.isLocked }) {
                        // Unlocking needs the fingerprint / screen lock first (it also lets the notes be decrypted).
                        unlock {
                            Repo.scope.launch {
                                val ok = Repo.unlockNotes(ctx, chosen.map { it.id })
                                if (!ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(ctx, "Some notes couldn't be unlocked. Try again.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else Repo.scope.launch {
                        Repo.saveAll(ctx, chosen.map { it.copy(isLocked = true) })
                        Repo.sealLockedNotes(ctx) // encrypt them straight away
                    }
                },
                onArchiveNotes = { ids -> scope.launch { Repo.archive(ctx, ids, deleted = false) } },
                onDeleteNotes = { ids -> scope.launch { Repo.delete(ctx, ids) } },
                onCreateLabelFor = { ids, name, color ->
                    scope.launch {
                        val id = Repo.labels(ctx).insert(Label(name = name, color = color, position = (labels.maxOfOrNull { it.position } ?: 0) + 1)).toInt()
                        Repo.saveAll(ctx, notes.filter { it.id in ids }.map { it.copy(labelId = id) })
                    }
                },
            )
            }
            }
            is Route.Edit -> EditorScreen(
                target = r.target,
                labels = labels,
                allNotes = notes,
                onBack = ::pop,
                onOpenLink = { title ->
                    val found = notes.firstOrNull { noteDisplayTitle(it).equals(title, ignoreCase = true) }
                    if (found != null) open(found) else openEditor(EditorTarget(0, NOTE_TYPE_TEXT, title = title))
                },
                onManageLabels = { push(Route.Labels()) },
                unlock = unlock,
            )
            Route.Settings -> SettingsScreen(settings, { change -> Prefs.get(ctx).update(change) }, ::pop, ::feedback, onHelp = { push(Route.Help) })
            Route.Help -> com.example.unutma.ui.HelpScreen(::pop)
            Route.Archive, Route.Deleted -> ArchiveScreen(
                // Archive = put away on purpose (kept); Recently deleted = deleted (removed after a while).
                notes = archived.filter { (it.deletedAt != null) == (r == Route.Deleted) },
                labels = labels,
                archiveDays = settings.archiveDays,
                deletedView = r == Route.Deleted,
                onBack = ::pop,
                onRestore = { n -> scope.launch { Repo.restore(ctx, listOf(n.id)) } },
                onDelete = { ids -> scope.launch { Repo.delete(ctx, ids) } },
                onDeleteForever = { ids -> scope.launch { Repo.deleteForever(ctx, ids) } },
            )
            is Route.Labels -> LabelsScreen(
                startCreating = r.create,
                labels = labels,
                counts = notes.groupingBy { it.labelId ?: -1 }.eachCount(),
                onBack = ::pop,
                onAdd = { name, color ->
                    scope.launch { Repo.labels(ctx).insert(Label(name = name, color = color, position = (labels.maxOfOrNull { it.position } ?: 0) + 1)) }
                },
                onUpdate = { l -> scope.launch { Repo.labels(ctx).update(l); com.example.unutma.widget.Widgets.refreshAll(ctx) } },
                onDelete = { l ->
                    scope.launch {
                        Repo.notes(ctx).clearLabel(l.id)
                        Repo.labels(ctx).delete(l)
                        com.example.unutma.widget.Widgets.refreshAll(ctx)
                    }
                },
                onReorder = { list ->
                    scope.launch {
                        list.forEachIndexed { i, x -> if (x.position != i) Repo.labels(ctx).update(x.copy(position = i)) }
                        com.example.unutma.widget.Widgets.refreshAll(ctx)
                    }
                },
            )
        }
    }
}
