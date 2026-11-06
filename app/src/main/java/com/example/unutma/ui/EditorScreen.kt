package com.example.unutma.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.unutma.Label
import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.Note
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_NONE
import com.example.unutma.RESET_WEEKLY
import com.example.unutma.data.AudioBlock
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.ImageBlock
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.Routines
import com.example.unutma.data.TextBlock
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.export.Exporter
import com.example.unutma.media.Images
import com.example.unutma.media.Ocr
import com.example.unutma.media.VoicePlayer
import com.example.unutma.media.VoiceRecorder
import com.example.unutma.notify.Notifier
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.GeistMono
import com.example.unutma.ui.theme.Ty
import com.example.unutma.ui.theme.swatch
import com.example.unutma.util.DateDetector
import com.example.unutma.util.DetectedDate
import com.example.unutma.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.snapshotFlow

/** What to open: an existing note (id > 0) or a new one. */
data class EditorTarget(
    val noteId: Int,
    val type: String = "TEXT",
    val labelId: Int? = null,
    val title: String? = null,
    val photo: Boolean = false,   // new note that opens the camera straight away (notes widget)
)

@Composable
fun EditorScreen(
    target: EditorTarget,
    labels: List<Label>,
    allNotes: List<Note>,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onManageLabels: () -> Unit,
    unlock: (onSuccess: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember(target) { mutableStateOf<EditorState?>(null) }

    LaunchedEffect(target) {
        val existing = if (target.noteId > 0) withContext(Dispatchers.IO) { Repo.notes(ctx).get(target.noteId) } else null
        if (target.noteId > 0 && existing == null) { onBack(); return@LaunchedEffect }
        val reset = existing?.let { Repo.resetIfDue(it) }
        if (reset != null) withContext(Dispatchers.IO) { Repo.save(ctx, reset, touch = false) }
        var base = reset ?: existing ?: Note(type = target.type, labelId = target.labelId, title = target.title.orEmpty())
        // A locked note is encrypted: open it with the key the fingerprint check just made available.
        var key: ByteArray? = null
        if (existing != null && com.example.unutma.data.Vault.isSealed(base.description)) {
            val opened = withContext(Dispatchers.IO) { com.example.unutma.data.Vault.open(base.description) }
            if (opened == null) {
                android.widget.Toast.makeText(ctx, "Couldn't open this locked note. Unlock it and try again.", android.widget.Toast.LENGTH_LONG).show()
                onBack(); return@LaunchedEffect
            }
            base = base.copy(description = opened.first); key = opened.second
        }
        val blocks = if (existing == null) {
            if (target.type == NOTE_TYPE_LIST) listOf(CheckBlock()) else listOf(TextBlock())
        } else parseBlocks(base)
        state = EditorState(base, blocks).also { s ->
            s.vaultKey = key
            if (existing == null) {
                // New note: start typing straight away (in the title if it came from a link, else the body).
                s.focus(s.items.first().id, 0)
            }
        }
    }

    val s = state
    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (s != null) Editor(s, labels, allNotes, onBack, onOpenLink, onManageLabels, unlock, startCamera = target.photo)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Editor(
    s: EditorState,
    labels: List<Label>,
    allNotes: List<Note>,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onManageLabels: () -> Unit,
    unlock: (onSuccess: () -> Unit) -> Unit,
    startCamera: Boolean = false,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLock = remember { Mutex() }
    val label = labels.firstOrNull { it.id == s.note.labelId }
    val labelKey = label?.color
    val tint = if (label != null) swatch(labelKey).on(C.isDark) else C.accent

    // ─── Auto-save: a moment after every change, and when leaving ───
    suspend fun persist() = saveLock.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val v = s.version
            if (v == s.savedVersion) return@withContext // nothing changed since the last save
            s.savedVersion = v
            if (s.isEmpty()) {
                if (s.note.id != 0 && s.mediaPaths().isEmpty()) {
                    Repo.deleteForever(ctx, listOf(s.note.id)) // an emptied note is not kept
                    s.note = s.note.copy(id = 0)
                }
                return@withContext
            }
            // Locked notes are saved encrypted (when the phone has a screen lock to protect the key).
            var toSave = s.toNote()
            if (toSave.isLocked && com.example.unutma.data.Vault.available(ctx)) {
                val key = s.vaultKey ?: com.example.unutma.data.Vault.newKey().also { s.vaultKey = it }
                toSave = toSave.copy(description = com.example.unutma.data.Vault.seal(toSave.description, s.mediaPaths(), key))
            }
            val saved = Repo.save(ctx, toSave)
            withContext(Dispatchers.Main) {
                s.note = s.note.copy(id = saved.id, createdAt = saved.createdAt, updatedAt = saved.updatedAt)
                s.saveStatus = "saved"
            }
        }
    }
    LaunchedEffect(s) {
        snapshotFlow { s.version }.drop(1).collectLatest {
            s.saveStatus = "editing"
            delay(450)
            persist()
        }
    }
    DisposableEffect(s) {
        onDispose {
            VoicePlayer.stop()
            Repo.scope.launch { persist() }
        }
    }
    BackHandler { onBack() }

    // ─── Permissions and pickers ───
    var afterNotifPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(ctx, "Notifications are off for Unutma, so reminders can't show.", Toast.LENGTH_LONG).show()
        afterNotifPermission?.invoke(); afterNotifPermission = null
    }
    fun withNotifications(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { afterNotifPermission = block; notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } else block()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = Images.import(ctx, uri)
            if (path != null) s.insertMedia(ImageBlock(path = path))
            else Toast.makeText(ctx, "Couldn't add that picture.", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera (the Notes widget's photo button): the picture is taken into the cache, then copied into the note.
    val shot = remember { java.io.File(java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }, "shot.jpg") }
    val shotUri = remember { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            val path = Images.import(ctx, shotUri)
            if (path != null) s.insertMedia(ImageBlock(path = path))
            else Toast.makeText(ctx, "Couldn't add that photo.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        if (startCamera) try { camera.launch(shotUri) } catch (_: Exception) {
            Toast.makeText(ctx, "No camera app found.", Toast.LENGTH_SHORT).show()
        }
    }

    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableLongStateOf(0L) }
    fun startRecording() {
        if (recorder.start()) { recording = true; recordStart = System.currentTimeMillis() }
        else Toast.makeText(ctx, "Couldn't start recording.", Toast.LENGTH_SHORT).show()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else Toast.makeText(ctx, "Unutma needs the microphone to record voice notes.", Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }

    // ─── Dialog flags ───
    var moreMenu by remember { mutableStateOf(false) }
    var labelDialog by remember { mutableStateOf(false) }
    var reminderDialog by remember { mutableStateOf(false) }
    var countdownDialog by remember { mutableStateOf(false) }
    var datePickDirect by remember { mutableStateOf(false) }   // opened by tapping the date in the note
    var reminderTimeEdit by remember { mutableStateOf(false) }
    var expiryDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var shareDialog by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var toolTab by remember { mutableStateOf<ToolTab?>(null) }
    var detailsSheet by remember { mutableStateOf(false) }

    fun updateNote(change: (Note) -> Note) { s.note = change(s.note); s.metaChanged() }

    // "Unpin" tapped in the notification while this note is open: follow the saved value, so the
    // pin button updates and the next auto-save doesn't pin the note again.
    val savedPinned = allNotes.firstOrNull { it.id == s.note.id && it.id != 0 }?.pinnedToBar
    LaunchedEffect(savedPinned) {
        if (savedPinned != null && savedPinned != s.note.pinnedToBar) s.note = s.note.copy(pinnedToBar = savedPinned)
    }

    // ─── Date detection: "Dentist on Monday 09:30" offers a reminder ───
    var suggestion by remember { mutableStateOf<DetectedDate?>(null) }
    var dismissed by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(s.version) {
        delay(700)
        val text = s.title.text + "\n" + s.items.joinToString("\n") { it.tfv.text }
        suggestion = withContext(Dispatchers.Default) { DateDetector.detect(text) }
    }

    val focusRequesters = remember { HashMap<Long, FocusRequester>() }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(s.pendingFocus) {
        val id = s.pendingFocus ?: return@LaunchedEffect
        s.placeCursor(id, s.focusCursor)
        repeat(5) {
            try { focusRequesters[id]?.requestFocus(); s.pendingFocus = null; return@LaunchedEffect } catch (_: Exception) {}
            delay(40)
        }
        s.pendingFocus = null
    }

    val faint = C.faint
    val transformation = remember(faint, tint) { NoteTransformation(faint, tint, tint, math = true) }
    val checkTransformation = remember(faint, tint) { NoteTransformation(faint, tint, tint, math = false) }

    // Drag to reorder
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<Long, Int>() }
    val tops = remember { HashMap<Long, Float>() }          // each row's top, within the note
    val textLayouts = remember { HashMap<Long, androidx.compose.ui.text.TextLayoutResult>() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var contentWidth by remember { mutableIntStateOf(1) }

    // ─── Moving a picture ("top and bottom" wrapping) ───
    // Hold a picture and drag it: a line shows where it will land — between rows, or splitting a
    // paragraph at a line — and it never sits beside text. Dragging it sideways sets Left / Centre / Right.
    var imgDragId by remember { mutableStateOf<Long?>(null) }
    var imgDx by remember { mutableFloatStateOf(0f) }
    var imgDy by remember { mutableFloatStateOf(0f) }
    var drop by remember { mutableStateOf<ImageDrop?>(null) }

    fun findDrop(id: Long): ImageDrop? {
        val y = (tops[id] ?: return null) + (heights[id] ?: 0) / 2f + imgDy
        val others = s.items.filter { it.id != id }
        if (others.isEmpty()) return null
        for (it in others) {
            val t = tops[it.id] ?: continue
            val h = (heights[it.id] ?: 0).toFloat()
            if (y < t) return ImageDrop(it.id, before = true, splitAt = null, lineY = t)
            if (y >= t + h) continue
            val layout = textLayouts[it.id]
            if (it.block is TextBlock && layout != null && it.tfv.text.isNotEmpty()) {
                // Inside a paragraph: split it at the nearest line boundary.
                val local = y - t
                val line = layout.getLineForVerticalPosition(local)
                val upper = local < (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
                val shown = if (upper) layout.getLineStart(line) else layout.getLineEnd(line)
                val lineY = t + if (upper) layout.getLineTop(line) else layout.getLineBottom(line)
                val text = it.tfv.text
                val orig = transformation.filter(androidx.compose.ui.text.AnnotatedString(text)).offsetMapping
                    .transformedToOriginal(shown.coerceIn(0, layout.layoutInput.text.length)).coerceIn(0, text.length)
                return when {
                    orig <= 0 -> ImageDrop(it.id, before = true, splitAt = null, lineY = t)
                    orig >= text.length -> ImageDrop(it.id, before = false, splitAt = null, lineY = lineY)
                    else -> ImageDrop(it.id, before = false, splitAt = orig, lineY = lineY)
                }
            }
            val before = y < t + h / 2
            return ImageDrop(it.id, before, null, if (before) t else t + h)
        }
        val last = others.last()
        return ImageDrop(last.id, before = false, splitAt = null, lineY = (tops[last.id] ?: 0f) + (heights[last.id] ?: 0))
    }

    fun imageDrag(b: ImageBlock): Modifier = Modifier.pointerInput(b.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                imgDragId = b.id; imgDx = 0f; imgDy = 0f; drop = null
            },
            onDragEnd = {
                val cur = s.items.firstOrNull { it.id == b.id }?.block as? ImageBlock ?: b
                // Sideways: where its middle ends up picks Left / Centre / Right (resized pictures only).
                if (cur.width < 1f && kotlin.math.abs(imgDx) > 24.dp.toPx()) {
                    val w = contentWidth * cur.width
                    val left = when (cur.align) { ImageBlock.CENTER -> (contentWidth - w) / 2; ImageBlock.RIGHT -> contentWidth - w; else -> 0f }
                    val mid = left + imgDx + w / 2
                    s.setImageAlign(b.id, when { mid < contentWidth / 3f -> ImageBlock.LEFT; mid > contentWidth * 2 / 3f -> ImageBlock.RIGHT; else -> ImageBlock.CENTER })
                }
                drop?.let { d -> if (kotlin.math.abs(imgDy) > 12.dp.toPx()) s.placeImage(b.id, d.anchorId, d.before, d.splitAt) }
                imgDragId = null; imgDx = 0f; imgDy = 0f; drop = null
            },
            onDragCancel = { imgDragId = null; imgDx = 0f; imgDy = 0f; drop = null },
        ) { change, amount ->
            change.consume()
            imgDx += amount.x; imgDy += amount.y
            drop = if (kotlin.math.abs(imgDy) > 12.dp.toPx()) findDrop(b.id) else null
        }
    }

    /**
     * Moves a checklist row up or down as it's dragged by its handle (pictures use imageDrag).
     * It swaps with the neighbour once dragged past half of the neighbour's height.
     */
    fun reorderDrag(id: Long, afterLongPress: Boolean): Modifier = Modifier.pointerInput(id, afterLongPress) {
        val start = { _: androidx.compose.ui.geometry.Offset -> dragId = id; dragOffset = 0f }
        val end = { dragId = null; dragOffset = 0f; s.moveDone() }
        val drag = { change: androidx.compose.ui.input.pointer.PointerInputChange, amount: androidx.compose.ui.geometry.Offset ->
            change.consume()
            dragOffset += amount.y
            val i = s.indexOf(id)
            // The first row can't be pulled up over the title, nor the last one past the end.
            if (i == 0) dragOffset = dragOffset.coerceAtLeast(0f)
            if (i == s.items.lastIndex) dragOffset = dragOffset.coerceAtMost(0f)
            if (dragOffset > 0 && i in 0 until s.items.lastIndex) {
                val h = heights[s.items[i + 1].id] ?: 0
                if (h > 0 && dragOffset > h / 2f) { s.move(i, i + 1); dragOffset -= h }
            } else if (dragOffset < 0 && i > 0) {
                val h = heights[s.items[i - 1].id] ?: 0
                if (h > 0 && -dragOffset > h / 2f) { s.move(i, i - 1); dragOffset += h }
            }
        }
        if (afterLongPress) detectDragGesturesAfterLongPress(start, end, end, drag)
        else detectDragGestures(start, end, end, drag)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        // ─── Top bar ───
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(UI.Back, "Back to notes", onBack, tint = C.fg)
            Spacer(Modifier.weight(1f))
            IconBtn(UI.Undo, "Undo", { s.undo() }, tint = C.fg, enabled = s.canUndo, iconSize = 18.dp)
            IconBtn(UI.Redo, "Redo", { s.redo() }, tint = C.fg, enabled = s.canRedo, iconSize = 18.dp)
            Box {
                IconBtn(UI.More, "More options", { moreMenu = true }, tint = C.fg, iconSize = 18.dp)
                // Everything about the note itself lives in the toolbar's Details sheet; ⋯ is just these three.
                UMenu(moreMenu, { moreMenu = false }, DpOffset((-4).dp, 4.dp)) {
                    UMenuItem("Share", { moreMenu = false; shareDialog = true }, UI.Share)
                    UMenuItem("Archive", {
                        moreMenu = false
                        scope.launch { persist(); if (s.note.id != 0) Repo.archive(ctx, listOf(s.note.id), deleted = false); onBack() }
                    }, UI.Archive)
                    UMenuItem("Delete", { moreMenu = false; deleteConfirm = true }, UI.Trash, iconTint = C.danger, color = C.danger)
                }
            }
        }

        // ─── Content ───
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .onSizeChanged { contentWidth = it.width.coerceAtLeast(1) }
                // Where a dragged picture will land.
                .drawWithContent {
                    drawContent()
                    val d = drop
                    if (imgDragId != null && d != null) drawLine(tint, Offset(0f, d.lineY), Offset(size.width, d.lineY), strokeWidth = 2.dp.toPx())
                },
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Label chip
            if (label != null) Pill(label.name, tint, { labelDialog = true })
            else Pill("Add label", C.muted, { labelDialog = true }, dashed = true)
            Spacer(Modifier.height(10.dp))

            // Title
            Box {
                if (s.title.text.isEmpty()) Text("Title", style = Ty.editorTitle, color = C.faint)
                BasicTextField(
                    value = s.title,
                    onValueChange = { s.updateTitle(it.copy(text = it.text.replace("\n", " "))) },
                    textStyle = Ty.editorTitle.copy(color = C.fg),
                    cursorBrush = SolidColor(C.accent),
                    visualTransformation = checkTransformation, // hides **bold** / *italic* markers here too
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { s.items.firstOrNull { it.block is TextBlock || it.block is CheckBlock }?.let { s.focus(it.id, it.tfv.text.length) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).onFocusChanged { if (it.isFocused) s.focusedId = -1L },
                )
            }
            Spacer(Modifier.height(10.dp))
            MetaLine(
                s, tint,
                onReminder = { reminderTimeEdit = true },
                onNag = { reminderDialog = true },
                onCountdown = { datePickDirect = true; countdownDialog = true },
                onExpiry = { datePickDirect = true; expiryDialog = true },
            )
            Spacer(Modifier.height(20.dp))

            // Blocks
            s.items.forEachIndexed { index, item ->
                key(item.id) {
                    val dragging = dragId == item.id
                    val movingImage = imgDragId == item.id
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[item.id] = it.height }
                            .onGloballyPositioned { tops[item.id] = it.positionInParent().y }
                            .zIndex(if (dragging || movingImage) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else if (movingImage) imgDy else 0f
                                translationX = if (movingImage) imgDx else 0f
                                alpha = if (dragging || movingImage) 0.85f else 1f
                            }
                            .padding(bottom = if (index < s.items.lastIndex) 12.dp else 0.dp)
                    ) {
                        when (val b = item.block) {
                            is TextBlock -> TextRow(
                                s, item, focusRequesters, transformation,
                                placeholder = if (s.items.size == 1) "Note" else null,
                                onLayout = { textLayouts[item.id] = it },
                            )
                            is CheckBlock -> CheckRow(
                                s, item, b, tint, focusRequesters, checkTransformation,
                                dragModifier = reorderDrag(item.id, afterLongPress = false),
                            )
                            is ImageBlock -> ImageRow(b, imageDrag(b), onResize = { w -> s.setImageWidth(b.id, w) }, onAlign = { a -> s.setImageAlign(b.id, a) }, onRemove = { s.remove(b.id) }, onExtract = {
                                Toast.makeText(ctx, "Reading the text…", Toast.LENGTH_SHORT).show()
                                Ocr.extract(ctx, b.path) { text ->
                                    if (text.isNullOrBlank()) Toast.makeText(ctx, "No text found in this picture.", Toast.LENGTH_SHORT).show()
                                    else s.insertTextAfter(b.id, text.trim())
                                }
                            })
                            is AudioBlock -> AudioRow(b, tint, onRemove = { s.remove(b.id) })
                        }
                    }
                }
            }
            // Tap the empty space below the note to keep writing.
            Box(
                Modifier.fillMaxWidth().heightIn(min = 160.dp).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                ) {
                    val last = s.items.lastOrNull { it.block is TextBlock || it.block is CheckBlock }
                    if (last != null) s.focus(last.id, last.tfv.text.length)
                }
            )
        }

        // ─── Suggestions above the toolbar ───
        val focusedItem = s.focusedId?.let { id -> s.items.firstOrNull { it.id == id } }
        val linkTitle = focusedItem?.let { if (it.tfv.selection.collapsed) linkAt(it.tfv.text, it.tfv.selection.start) else null }
        val sug = suggestion
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (linkTitle != null) {
                SuggestionChip(UI.External, buildAnnotatedString { append("Open "); withStyle(SpanStyle(color = C.fg)) { append(linkTitle) } }, tint, {
                    scope.launch { persist(); onOpenLink(linkTitle) }
                })
            } else if (sug != null && s.note.reminderAt == null && sug.at !in dismissed) {
                SuggestionChip(UI.Bell, buildAnnotatedString {
                    append("Remind me ")
                    withStyle(SpanStyle(color = C.fg, fontFamily = GeistMono)) { append(Fmt.dateTime(sug.at)) }
                    append("?")
                }, tint, { withNotifications { updateNote { it.copy(reminderAt = sug.at) } } }, onDismiss = { dismissed = dismissed + sug.at })
            }
        }

        // ─── Toolbar (or the recording bar) ───
        HDivider()
        if (recording) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
            val secs = ((now - recordStart) / 1000).toInt()
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(C.danger, 8.dp)
                Spacer(Modifier.width(10.dp))
                Text("Recording  %d:%02d".format(secs / 60, secs % 60), style = Ty.monoValue, color = C.fg, modifier = Modifier.weight(1f))
                TextButtonU("Cancel", { recorder.cancel(); recording = false }, color = C.muted)
                TextButtonU("Done", {
                    val r = recorder.stop(); recording = false
                    if (r != null) s.insertMedia(AudioBlock(path = r.first, durationMs = r.second))
                })
            }
        } else {
            // Second row: the tools of the open tab (Text or Insert), just above the keyboard.
            when (toolTab) {
                ToolTab.TEXT -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolText("B", "Bold", TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)) { s.wrap("**") }
                    ToolText("I", "Italic", TextStyle(fontStyle = FontStyle.Italic, fontSize = 16.sp)) { s.wrap("*") }
                    val level = s.headingLevelAtFocus()
                    var headingMenu by remember { mutableStateOf(false) }
                    Box {
                        ToolText(if (level == 0) "H" else "H$level", "Heading: " + (if (level == 0) "off" else "size $level") + ". Tap to change, hold to choose",
                            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), active = level > 0, activeColor = tint,
                            onLongClick = { headingMenu = true }) { s.cycleHeading() }
                        UMenu(headingMenu, { headingMenu = false }) {
                            listOf(0 to "Normal text", 1 to "H1  Large heading", 2 to "H2  Medium heading", 3 to "H3  Small heading").forEach { (lv, name) ->
                                UMenuItem(name, { headingMenu = false; s.setHeading(lv) },
                                    color = if (lv == level) tint else C.fg, trailing = if (lv == level) "✓" else null)
                            }
                        }
                    }
                    IconBtn(UI.Numbered, "Numbered list", { s.toggleNumbered() }, tint = C.muted, iconSize = 18.dp)
                    IconBtn(UI.Checkbox, "Checklist", { s.toggleChecklistAtFocus() }, tint = C.muted, iconSize = 18.dp)
                }
                ToolTab.INSERT -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBtn(UI.Image, "Add image", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, tint = C.muted, iconSize = 18.dp)
                    IconBtn(UI.Mic, "Voice note", {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, tint = C.muted, iconSize = 18.dp)
                    IconBtn(UI.Link, "Link a note", { linkDialog = true }, tint = C.muted, iconSize = 18.dp)
                    IconBtn(UI.Checkbox, "Checklist", { s.toggleChecklistAtFocus() }, tint = C.muted, iconSize = 18.dp)
                }
                null -> Unit
            }
            // Bottom bar: Text · Insert · Remind · Details.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolTabButton("Text", UI.Text, toolTab == ToolTab.TEXT, tint, Modifier.weight(1f)) {
                    toolTab = if (toolTab == ToolTab.TEXT) null else ToolTab.TEXT
                }
                ToolTabButton("Insert", UI.Plus, toolTab == ToolTab.INSERT, tint, Modifier.weight(1f)) {
                    toolTab = if (toolTab == ToolTab.INSERT) null else ToolTab.INSERT
                }
                ToolTabButton("Remind", UI.Bell, false, tint, Modifier.weight(1f), dot = s.note.reminderAt != null) { reminderDialog = true }
                ToolTabButton("Details", UI.Sliders, detailsSheet, tint, Modifier.weight(1f)) { detailsSheet = true }
            }
        }
    }

    // ─── Details sheet: everything about the note that isn't its text ───
    if (detailsSheet) DetailsSheet(onDismiss = { detailsSheet = false }) {
        val hasChecks = s.items.any { it.block is CheckBlock }
        SheetRow("Label", UI.Tag, label?.name ?: "None") { detailsSheet = false; labelDialog = true }
        SheetRow("Countdown", UI.Calendar, s.note.countdownAt?.let { Fmt.countdown(it) } ?: "Off") {
            detailsSheet = false; datePickDirect = false; countdownDialog = true
        }
        SheetRow("Expires", UI.Hourglass, s.note.expiresAt?.let { Fmt.dayMonth(it) } ?: "Never") {
            detailsSheet = false; datePickDirect = false; expiryDialog = true
        }
        if (hasChecks) SheetRow("Repeat (routine)", UI.Repeat, Routines.describe(s.note.resetMode)) { detailsSheet = false; resetDialog = true }
        SheetSwitch("Pin to top", UI.PinTop, s.note.isPinned) { v -> updateNote { it.copy(isPinned = v) } }
        SheetSwitch("Pin to notification bar", UI.PinBar, s.note.pinnedToBar) { v ->
            if (v) withNotifications { updateNote { it.copy(pinnedToBar = true) } } else updateNote { it.copy(pinnedToBar = false) }
        }
        SheetSwitch("Lock", UI.Lock, s.note.isLocked) { v ->
            if (v) {
                updateNote { it.copy(isLocked = true) }
                val p = Prefs.get(ctx)
                if (!p.flag("lock_warned")) {
                    p.setFlag("lock_warned", true)
                    Toast.makeText(ctx, "Locked notes are encrypted with your phone's screen lock. If you ever remove the screen lock, Android deletes the key and locked notes can't be opened.", Toast.LENGTH_LONG).show()
                }
            } else unlock { updateNote { it.copy(isLocked = false) } }
        }
        if (hasChecks) SheetRow("Untick all", UI.Restore, null) { detailsSheet = false; s.uncheckAll() }
    }

    // ─── Dialogs ───
    if (labelDialog) LabelPickerDialog(
        labels, s.note.labelId, { id -> updateNote { it.copy(labelId = id) } }, onManageLabels, { labelDialog = false },
        onCreate = { name, color ->
            scope.launch {
                val pos = (labels.maxOfOrNull { it.position } ?: -1) + 1
                val id = withContext(Dispatchers.IO) { Repo.labels(ctx).insert(Label(name = name, color = color, position = pos)).toInt() }
                updateNote { it.copy(labelId = id) }
            }
        },
    )
    if (reminderDialog) ReminderDialog(s.note.reminderAt, s.note.nagMinutes, { at, nag ->
        if (at != null) withNotifications { updateNote { it.copy(reminderAt = at, nagMinutes = nag) } }
        else { updateNote { it.copy(reminderAt = null, nagMinutes = 0) }; Notifier.cancelReminder(ctx, s.note.id) }
    }, { reminderDialog = false })
    if (countdownDialog) DateOrRemove(
        "Count down to", s.note.countdownAt,
        onSet = { at -> updateNote { it.copy(countdownAt = at) } },
        onRemove = { updateNote { it.copy(countdownAt = null) } },
        onDismiss = { countdownDialog = false }, hour = 9,
        withTime = true, openPicker = datePickDirect,
    )
    if (expiryDialog) DateOrRemove(
        "Expires on", s.note.expiresAt,
        onSet = { at -> updateNote { it.copy(expiresAt = at) } },
        onRemove = { updateNote { it.copy(expiresAt = null) } },
        onDismiss = { expiryDialog = false }, hour = 23, minute = 59,
        note = "On that day the note moves to Recently deleted.",
        openPicker = datePickDirect,
    )
    // Tapping the reminder's date and time in the note opens the date and time picker straight away.
    if (reminderTimeEdit) s.note.reminderAt?.let { current ->
        DateTimePicker(current, withTime = true, onPicked = { at ->
            withNotifications { updateNote { it.copy(reminderAt = at) } }
            reminderTimeEdit = false
        }, onDismiss = { reminderTimeEdit = false })
    }
    if (resetDialog) RoutineDialog(
        s.note.resetMode,
        { mode -> updateNote { it.copy(resetMode = mode, lastResetAt = System.currentTimeMillis()) } }, { resetDialog = false },
    )
    if (linkDialog) LinkPicker(allNotes.filter { it.id != s.note.id }, { title -> s.insertAtCursor("[[$title]]") }, { linkDialog = false })
    if (shareDialog) UDialog({ shareDialog = false }, "Share") {
        val n = s.toNote(); val blocks = s.blocks()
        val colorInt = swatch(labelKey ?: Prefs.get(ctx).current.accentKey).dark.toArgb()
        val dark = C.isDark
        DialogRow("As text", { shareDialog = false; Exporter.shareText(ctx, n, blocks) }, UI.Text)
        DialogRow("As PDF", { shareDialog = false; scope.launch { Exporter.sharePdf(ctx, n, blocks, label?.name, colorInt) } }, UI.Copy)
        DialogRow("As image", { shareDialog = false; scope.launch { Exporter.shareImage(ctx, n, blocks, label?.name, colorInt, dark) } }, UI.Image)
    }
    if (deleteConfirm) ConfirmDialog(
        "Delete note?", "It stays in Recently deleted for a while, so you can still restore it.", "Delete",
        { scope.launch { persist(); if (s.note.id != 0) Repo.delete(ctx, listOf(s.note.id)); s.note = s.note.copy(id = 0); onBack() } },
        { deleteConfirm = false },
    )
}

/** Dates, times and status under the title. Tap a date or time to change it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(
    s: EditorState,
    tint: Color,
    onReminder: () -> Unit,
    onNag: () -> Unit,
    onCountdown: () -> Unit,
    onExpiry: () -> Unit,
) {
    val n = s.note
    val parts = ArrayList<@Composable () -> Unit>()
    @Composable
    fun Seg(text: androidx.compose.ui.text.AnnotatedString, description: String?, onClick: (() -> Unit)?) {
        Text(
            text, style = Ty.mono, color = C.muted,
            modifier = if (onClick == null) Modifier else Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { if (description != null) contentDescription = description }
                .padding(vertical = 4.dp),
        )
    }
    n.reminderAt?.let { at ->
        parts += { Seg(buildAnnotatedString { withStyle(SpanStyle(color = tint)) { append(Fmt.dateTime(at)) } }, "Reminder ${Fmt.dateTime(at)}, tap to change", onReminder) }
        if (n.nagMinutes > 0) parts += { Seg(buildAnnotatedString { append("every ${n.nagMinutes} min") }, "Repeats every ${n.nagMinutes} minutes, tap to change", onNag) }
    }
    n.countdownAt?.let { at ->
        parts += {
            Seg(buildAnnotatedString {
                append(Fmt.dateTime(at)); append(" · ")
                withStyle(SpanStyle(color = if (Fmt.isSoon(at)) tint else C.muted)) { append(Fmt.countdown(at, long = true)) }
            }, "Countdown to ${Fmt.dateTime(at)}, tap to change", onCountdown)
        }
    }
    n.expiresAt?.let { at ->
        parts += { Seg(buildAnnotatedString { withStyle(SpanStyle(color = if (Fmt.isSoon(at, 3)) tint else C.muted)) { append("expires " + Fmt.dayMonth(at)) } }, "Expires ${Fmt.dayMonth(at)}, tap to change", onExpiry) }
    }
    val plain = buildList {
        if (Routines.isRoutine(n)) add("repeats: " + Routines.describe(n.resetMode).lowercase())
        if (n.isLocked) add("locked")
        add(if (s.saveStatus == "saved") (if (n.id == 0) "new" else "saved") else "…")
    }
    plain.forEach { t -> parts += { Seg(buildAnnotatedString { append(t) }, null, null) } }

    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Text(" · ", style = Ty.mono, color = C.muted)
            part()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class CursorKeeper {
    val requester = androidx.compose.foundation.relocation.BringIntoViewRequester()
    var layout: androidx.compose.ui.text.TextLayoutResult? = null
    var focused by mutableStateOf(false)
}

/**
 * Keeps the cursor of the row being typed in on screen with some room below it, also once the
 * keyboard has finished opening (tapping low on the page used to leave the cursor behind the keyboard).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberCursorKeeper(item: EItem, transformation: NoteTransformation): CursorKeeper {
    val keeper = remember { CursorKeeper() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    LaunchedEffect(keeper.focused, item.tfv.selection, item.tfv.text, ime) {
        if (!keeper.focused) return@LaunchedEffect
        delay(80) // let the keyboard animation settle (each frame restarts this)
        val l = keeper.layout ?: return@LaunchedEffect
        val text = item.tfv.text
        val shown = transformation.filter(androidx.compose.ui.text.AnnotatedString(text)).offsetMapping
            .originalToTransformed(item.tfv.selection.end.coerceIn(0, text.length))
            .coerceIn(0, l.layoutInput.text.length)
        val r = l.getCursorRect(shown)
        val above = with(density) { 96.dp.toPx() }
        val below = with(density) { 160.dp.toPx() }
        keeper.requester.bringIntoView(androidx.compose.ui.geometry.Rect(r.left, r.top - above, r.right, r.bottom + below))
    }
    return keeper
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextRow(
    s: EditorState,
    item: EItem,
    frs: HashMap<Long, FocusRequester>,
    transformation: NoteTransformation,
    placeholder: String?,
    onLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
) {
    val fr = frs.getOrPut(item.id) { FocusRequester() }
    val keeper = rememberCursorKeeper(item, transformation)
    Box(Modifier.fillMaxWidth()) {
        if (placeholder != null && item.tfv.text.isEmpty()) Text(placeholder, style = Ty.body, color = C.faint)
        BasicTextField(
            value = item.tfv,
            onValueChange = { s.edit(item.id, it) },
            textStyle = Ty.body.copy(color = C.body),
            cursorBrush = SolidColor(C.accent),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { keeper.layout = it; onLayout(it) },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(keeper.requester)
                .focusRequester(fr)
                .onFocusChanged { keeper.focused = it.isFocused; if (it.isFocused) s.focusedId = item.id }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && item.tfv.selection.collapsed && item.tfv.selection.start == 0) {
                        s.backspaceAtStart(item.id); true
                    } else false
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckRow(
    s: EditorState,
    item: EItem,
    b: CheckBlock,
    tint: Color,
    frs: HashMap<Long, FocusRequester>,
    transformation: NoteTransformation,
    dragModifier: Modifier,
) {
    val fr = frs.getOrPut(item.id) { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    // Middle of the first line's letters, measured from the text, so the checkbox lines up
    // with the words at any text size (the letters sit above the middle of their line box).
    val density = LocalDensity.current
    var lineMid by remember { mutableStateOf(13.dp) }
    val keeper = rememberCursorKeeper(item, transformation)
    Row(
        Modifier.fillMaxWidth().offset(x = (-18).dp).padding(start = (b.indent * 24).dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Drag handle
        Box(
            Modifier.offset(y = lineMid - 13.dp).size(width = 18.dp, height = 26.dp).then(dragModifier).semantics { contentDescription = "Drag to reorder" },
            contentAlignment = Alignment.CenterStart,
        ) { Icon(UI.Drag, null, tint = C.faint, modifier = Modifier.size(width = 10.dp, height = 16.dp)) }
        // Checkbox (long-press for sub-item, quantity…)
        Box {
            Box(
                Modifier
                    .size(26.dp)
                    .combinedClickable(role = Role.Checkbox, onClick = { s.toggle(b.id) }, onLongClick = { menu = true })
                    .semantics { contentDescription = if (b.checked) "Done" else "Not done" },
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    Modifier
                        .offset(y = lineMid - 7.dp)
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .then(if (b.checked) Modifier.background(tint) else Modifier.border(1.dp, C.muted, RoundedCornerShape(3.dp)))
                )
            }
            UMenu(menu, { menu = false }) {
                if (b.indent == 0) UMenuItem("Make sub-item", { menu = false; s.setIndent(b.id, 1) }, UI.Indent)
                else UMenuItem("Make main item", { menu = false; s.setIndent(b.id, 0) }, UI.Outdent)
                if (b.qty == null) UMenuItem("Add quantity", { menu = false; s.setQty(b.id, 1) }, UI.PlusBold)
                else UMenuItem("Remove quantity", { menu = false; s.setQty(b.id, null) }, UI.Minus)
                UMenuItem("Turn into text", { menu = false; s.toText(b.id) }, UI.Text)
                UMenuItem("Delete item", { menu = false; s.remove(b.id) }, UI.Trash, iconTint = C.danger, color = C.danger)
            }
        }
        BasicTextField(
            value = item.tfv,
            onValueChange = { if (it.text.contains('\n')) s.enterInCheck(item.id, it) else s.edit(item.id, it) },
            textStyle = Ty.body.copy(
                color = if (b.checked) C.done else C.body,
                textDecoration = if (b.checked) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(C.accent),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { layout ->
                keeper.layout = layout
                if (layout.lineCount > 0) {
                    val fontPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
                    // Baseline minus about half a capital letter's height.
                    val midPx = layout.getLineBaseline(0) - fontPx * 0.36f
                    lineMid = with(density) { midPx.toDp() }
                }
            },
            modifier = Modifier
                .weight(1f)
                .bringIntoViewRequester(keeper.requester)
                .focusRequester(fr)
                .onFocusChanged { keeper.focused = it.isFocused; if (it.isFocused) s.focusedId = item.id }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && item.tfv.selection.collapsed && item.tfv.selection.start == 0) {
                        s.backspaceAtStart(item.id); true
                    } else false
                },
        )
        val q = b.qty
        if (q != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = 18.dp)) {
                StepButton(UI.Minus, "Less") { s.setQty(b.id, (q - 1).coerceAtLeast(0)) }
                Text(q.toString(), style = Ty.monoValue, color = if (b.checked) C.done else C.fg, modifier = Modifier.widthIn(min = 22.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                StepButton(UI.PlusBold, "More") { s.setQty(b.id, q + 1) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).border(1.dp, C.menuBorder, CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = C.muted, modifier = Modifier.size(14.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageRow(b: ImageBlock, dragModifier: Modifier, onResize: (Float) -> Unit, onAlign: (String) -> Unit, onRemove: () -> Unit, onExtract: () -> Unit) {
    var bmp by remember(b.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(b.path) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(b.path) {
        val loaded = Images.load(b.path, 1440)
        if (loaded == null) failed = true else bmp = loaded.asImageBitmap()
    }
    // Resizing: drag the round handle at the bottom-right corner; the size is saved when you let go.
    var width by remember(b.id, b.width) { mutableFloatStateOf(b.width) }
    var full by remember { mutableIntStateOf(1) }
    // A narrower picture sits on the left, in the middle or on the right of its own line.
    val leftPx = when (b.align) { ImageBlock.CENTER -> full * (1 - width) / 2; ImageBlock.RIGHT -> full * (1 - width); else -> 0f }
    Box(Modifier.fillMaxWidth().onSizeChanged { full = it.width.coerceAtLeast(1) }) {
        Box(
            Modifier
                .align(when (b.align) { ImageBlock.CENTER -> Alignment.TopCenter; ImageBlock.RIGHT -> Alignment.TopEnd; else -> Alignment.TopStart })
                .fillMaxWidth(width)
                .border(1.dp, C.menuBorder)
                .then(dragModifier) // hold and drag to move the picture
                .clickable { menu = true }
                .semantics { contentDescription = "Picture. Tap for options; hold and drag to move it up, down, or left and right" },
        ) {
            val img = bmp
            if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text(if (failed) "picture missing" else "loading picture…", style = Ty.mono, color = C.muted)
            }
        }
        // The resize handle sits on the picture's bottom-right corner.
        Box(
            Modifier
                .offset(x = with(LocalDensity.current) { (leftPx + full * width).toDp() } - 22.dp, y = 0.dp)
                .align(Alignment.BottomStart)
                .size(32.dp)
                .pointerInput(b.id) {
                    detectDragGestures(
                        onDragEnd = { onResize(width) },
                        onDragCancel = { onResize(width) },
                    ) { change, amount ->
                        change.consume()
                        width = (width + amount.x / full).coerceIn(0.3f, 1f)
                    }
                }
                .semantics { contentDescription = "Resize picture, ${(width * 100).toInt()} percent wide" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(C.bg).border(1.5.dp, C.fg, CircleShape))
        }
        UMenu(menu, { menu = false }) {
            UMenuItem("Extract text", { menu = false; onExtract() }, UI.TextScan)
            // The same sizes from a menu, for anyone who'd rather not drag.
            UMenuItem("Full width", { menu = false; width = 1f; onResize(1f) }, UI.Image)
            UMenuItem("Half width", { menu = false; width = 0.5f; onResize(0.5f) }, UI.Image)
            if (width < 1f) {
                UMenuDivider()
                listOf(ImageBlock.LEFT to "Left", ImageBlock.CENTER to "Centre", ImageBlock.RIGHT to "Right").forEach { (a, name) ->
                    UMenuItem(name, { menu = false; onAlign(a) }, null, trailing = if (b.align == a) "✓" else null)
                }
            }
            UMenuItem("Remove picture", { menu = false; onRemove() }, UI.Trash, iconTint = C.danger, color = C.danger)
        }
    }
}

@Composable
private fun AudioRow(b: AudioBlock, tint: Color, onRemove: () -> Unit) {
    var playing by remember(b.path) { mutableStateOf(VoicePlayer.playingPath == b.path) }
    var pos by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        while (playing) { pos = VoicePlayer.position(); delay(200); if (VoicePlayer.playingPath != b.path) playing = false }
        pos = 0
    }
    val total = b.durationMs.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.menuBorder, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(if (playing) UI.Pause else UI.Play, if (playing) "Pause" else "Play voice note", {
            if (playing) { VoicePlayer.stop(); playing = false }
            else { VoicePlayer.play(b.path) { playing = false }; playing = true }
        }, tint = tint, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("Voice note", style = Ty.rowSub, color = C.fg, modifier = Modifier.weight(1f))
                val secs = ((if (playing) pos.toLong() else b.durationMs) / 1000).toInt()
                Text("%d:%02d".format(secs / 60, secs % 60), style = Ty.mono, color = C.muted)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(C.track)) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / total).coerceIn(0f, 1f)).height(2.dp).background(tint))
            }
        }
        Box {
            IconBtn(UI.More, "Voice note options", { menu = true }, iconSize = 16.dp)
            UMenu(menu, { menu = false }) {
                UMenuItem("Remove voice note", { menu = false; VoicePlayer.stop(); onRemove() }, UI.Trash, iconTint = C.danger, color = C.danger)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolText(
    text: String,
    description: String,
    style: TextStyle,
    active: Boolean = false,
    activeColor: Color = C.accent,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(width = 42.dp, height = 44.dp).clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = style.copy(fontFamily = com.example.unutma.ui.theme.Geist), color = if (active) activeColor else C.muted) }
}

@Composable
private fun SuggestionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: androidx.compose.ui.text.AnnotatedString,
    tint: Color,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (onDismiss != null) 2.dp else 14.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = Ty.rowSub, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onDismiss != null) IconBtn(UI.Close, "Dismiss", onDismiss, iconSize = 14.dp, modifier = Modifier.size(36.dp))
    }
}

/** Date-only picker with a "Remove" option when a date is already set. */
@Composable
private fun DateOrRemove(
    title: String, current: Long?, onSet: (Long) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
    hour: Int, minute: Int = 0, note: String? = null,
    withTime: Boolean = false,      // pick a time too (countdowns)
    openPicker: Boolean = false,    // go straight to the date picker (tapped the date in the note)
) {
    var picking by remember { mutableStateOf(current == null || openPicker) }
    if (picking) {
        DateTimePicker(current ?: System.currentTimeMillis() + 24L * 60 * 60 * 1000, withTime = withTime, onPicked = { at ->
            if (withTime) onSet(at) else {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = at; set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute) }
                onSet(c.timeInMillis)
            }
            onDismiss()
        }, onDismiss = { if (current == null || openPicker) onDismiss() else picking = false }, defaultHour = hour)
        return
    }
    UDialog(onDismiss, title) {
        if (current != null) Text(
            (if (withTime) Fmt.dateTime(current) + " · " + Fmt.countdown(current, long = true) else Fmt.date(current) + " · " + Fmt.relative(current, long = true)),
            style = Ty.mono, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (note != null) Text(note, style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        DialogRow(if (withTime) "Change date and time" else "Change date", { picking = true }, UI.Calendar)
        DialogRow("Remove", { onRemove(); onDismiss() }, UI.Close, color = C.danger, iconTint = C.danger)
    }
}

/** Pick a note to link to; inserts [[Title]]. */
@Composable
private fun LinkPicker(notes: List<Note>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val titled = remember(notes) { notes.map { it to noteDisplayTitle(it) }.sortedBy { it.second.lowercase() } }
    val shown = titled.filter { q.isBlank() || it.second.contains(q.trim(), ignoreCase = true) }.take(40)
    UDialog(onDismiss, "Link a note") {
        UField(q, { q = it }, "Search or type a new title", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
        HDivider(modifier = Modifier.padding(horizontal = 20.dp))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (q.isNotBlank() && titled.none { it.second.equals(q.trim(), ignoreCase = true) }) {
                DialogRow("Link to new note “${q.trim()}”", { onPick(q.trim()); onDismiss() }, UI.PlusBold)
            }
            shown.forEach { (_, title) -> DialogRow(title, { onPick(title); onDismiss() }, UI.Link) }
        }
    }
}

/** Where a dragged picture will be dropped: next to [anchorId], or splitting that paragraph at [splitAt]. */
private data class ImageDrop(val anchorId: Long, val before: Boolean, val splitAt: Int?, val lineY: Float)
