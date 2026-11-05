package com.example.unutma.data

import android.content.Context
import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.NOTE_TYPE_TEXT
import com.example.unutma.Note
import com.example.unutma.NoteDatabase
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_NONE
import com.example.unutma.RESET_WEEKLY
import com.example.unutma.notify.Alarms
import com.example.unutma.notify.Notifier
import com.example.unutma.util.DateDetector
import com.example.unutma.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Calendar

/**
 * Every change to a note goes through here, so reminders, the notification-bar pin and
 * home screen widgets always match what is in the database.
 */
object Repo {
    /** Outlives screens: a save started when you press back still finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun db(ctx: Context) = NoteDatabase.getInstance(ctx)
    fun notes(ctx: Context) = db(ctx).noteDao()
    fun labels(ctx: Context) = db(ctx).labelDao()

    /** Inserts or updates. Returns the saved note (with its id). */
    suspend fun save(ctx: Context, note: Note, touch: Boolean = true): Note {
        val now = System.currentTimeMillis()
        val saved = if (note.id == 0) {
            val n = note.copy(createdAt = now, updatedAt = now)
            val id = notes(ctx).insert(n).toInt()
            n.copy(id = id)
        } else {
            val n = if (touch) note.copy(updatedAt = now) else note
            notes(ctx).update(n)
            n
        }
        afterChange(ctx, saved)
        return saved
    }

    suspend fun saveAll(ctx: Context, list: List<Note>) {
        for (n in list) {
            notes(ctx).update(n)
            afterChange(ctx, n, refreshWidgets = false)
        }
        Widgets.refreshAll(ctx)
    }

    /** Today as a whole number of days, in the phone's time zone. */
    fun localDay(now: Long = System.currentTimeMillis()): Long = Routines.dayOf(now)

    private suspend fun afterChange(ctx: Context, note: Note, refreshWidgets: Boolean = true) {
        // How much is ticked today, for routine streaks and week squares. (The reset after midnight
        // happens on the new day, so it never lowers yesterday.)
        if (!note.isArchived && !note.isLocked) Routines.record(ctx, note)
        Alarms.syncNote(ctx, note)
        val labelKey = note.labelId?.let { id -> labels(ctx).all().firstOrNull { it.id == id }?.color }
        Notifier.syncPin(ctx, note, labelKey)
        if (refreshWidgets) Widgets.refreshAll(ctx)
    }

    /** Delete = move to the archive; it is purged after the time set in Settings. */
    suspend fun delete(ctx: Context, ids: Collection<Int>) = archive(ctx, ids, deleted = true)

    suspend fun archive(ctx: Context, ids: Collection<Int>, deleted: Boolean) {
        val now = System.currentTimeMillis()
        val list = ids.mapNotNull { notes(ctx).get(it) }.map {
            it.copy(isArchived = true, deletedAt = if (deleted) now else null, pinnedToBar = false)
        }
        saveAll(ctx, list)
    }

    suspend fun restore(ctx: Context, ids: Collection<Int>) {
        val list = ids.mapNotNull { notes(ctx).get(it) }.map {
            it.copy(isArchived = false, deletedAt = null, expiresAt = it.expiresAt?.takeIf { e -> e > System.currentTimeMillis() })
        }
        saveAll(ctx, list)
    }

    suspend fun deleteForever(ctx: Context, ids: Collection<Int>) {
        val list = ids.mapNotNull { notes(ctx).get(it) }
        for (n in list) {
            Alarms.cancelNote(ctx, n.id)
            Notifier.cancelPin(ctx, n.id)
            Notifier.cancelReminder(ctx, n.id)
            Routines.forget(ctx, n.id)
            deleteMedia(parseBlocks(n))
        }
        notes(ctx).deleteByIds(list.map { it.id })
        Widgets.refreshAll(ctx)
    }

    fun deleteMedia(blocks: List<Block>) {
        for (b in blocks) {
            val path = when (b) { is ImageBlock -> b.path; is AudioBlock -> b.path; else -> null } ?: continue
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    /**
     * Runs on app start, on boot and once a day: purges old archived notes, archives expired
     * notes and resets daily/weekly checklists.
     */
    suspend fun maintenance(ctx: Context) {
        val now = System.currentTimeMillis()
        val days = Prefs.get(ctx).current.archiveDays
        val keepMs = days * 24L * 60 * 60 * 1000
        val all = notes(ctx).all()
        val purge = if (days == ARCHIVE_FOREVER) emptyList()
        else all.filter { it.isArchived && it.deletedAt != null && now - it.deletedAt >= keepMs }
        if (purge.isNotEmpty()) deleteForever(ctx, purge.map { it.id })

        val changed = ArrayList<Note>()
        for (n in all) {
            if (n.isArchived) continue
            if (n.expiresAt != null && n.expiresAt <= now) {
                changed += n.copy(isArchived = true, deletedAt = now, pinnedToBar = false)
                continue
            }
            resetIfDue(n, now)?.let { changed += it }
        }
        if (changed.isNotEmpty()) saveAll(ctx, changed) else Widgets.refreshAll(ctx) // countdowns change every day
        sealLockedNotes(ctx)
        cleanOrphanMedia(ctx)
        Alarms.scheduleMaintenance(ctx)
    }

    /**
     * Encrypts locked notes that aren't encrypted yet (notes locked before encryption existed, or
     * locked from the notes list). Needs no fingerprint; does nothing without a phone screen lock.
     */
    suspend fun sealLockedNotes(ctx: Context) {
        val todo = notes(ctx).all().filter { it.isLocked && !Vault.isSealed(it.description) }
        if (todo.isEmpty() || !Vault.available(ctx)) return
        saveAll(ctx, todo.map { n ->
            val media = parseBlocks(n).mapNotNull { b -> when (b) { is ImageBlock -> b.path; is AudioBlock -> b.path; else -> null } }
            n.copy(description = Vault.seal(n.description, media, Vault.newKey()))
        })
    }

    /** Unlocking from the notes list (right after the fingerprint check): decrypts and saves as normal notes. */
    suspend fun unlockNotes(ctx: Context, ids: Collection<Int>): Boolean {
        var ok = true
        val list = ids.mapNotNull { notes(ctx).get(it) }.map { n ->
            if (!Vault.isSealed(n.description)) n.copy(isLocked = false)
            else Vault.open(n.description)?.let { (plain, _) -> n.copy(description = plain, isLocked = false) } ?: run { ok = false; n }
        }
        saveAll(ctx, list)
        return ok
    }

    /** Pictures/voice clips removed from a note in the editor stay until here, so Undo can bring them back. */
    private suspend fun cleanOrphanMedia(ctx: Context) {
        val used = HashSet<String>()
        for (n in notes(ctx).all()) for (b in parseBlocks(n)) {
            when (b) { is ImageBlock -> used += b.path; is AudioBlock -> used += b.path; else -> Unit }
        }
        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        for (dir in listOf("images", "audio")) {
            File(ctx.filesDir, dir).listFiles()?.forEach { f ->
                if (f.absolutePath !in used && f.lastModified() < dayAgo) f.delete()
            }
        }
    }

    /** When the routine's boxes were last due to be unticked (see [Routines.periodStart]). */
    fun periodStart(mode: String, now: Long): Long = Routines.periodStart(mode, now)

    /** Returns the note with every box unticked if its reset period has rolled over. */
    fun resetIfDue(n: Note, now: Long = System.currentTimeMillis()): Note? {
        if (n.resetMode == RESET_NONE) return null
        if (n.lastResetAt >= periodStart(n.resetMode, now)) return null
        val blocks = parseBlocks(n)
        val hadTicks = blocks.any { it is CheckBlock && it.checked }
        val newBlocks = blocks.map { if (it is CheckBlock) it.copy(checked = false) else it }
        return n.copy(
            description = if (hadTicks) serializeBlocks(newBlocks) else n.description,
            lastResetAt = now,
        )
    }

    fun uncheckAll(n: Note): Note {
        val blocks = parseBlocks(n).map { if (it is CheckBlock) it.copy(checked = false) else it }
        return n.copy(description = serializeBlocks(blocks), lastResetAt = System.currentTimeMillis())
    }

    /** Used by the list widget: ticks/unticks the [checkIndex]-th checkbox of a note. */
    suspend fun toggleCheck(ctx: Context, noteId: Int, checkIndex: Int) {
        val n = notes(ctx).get(noteId) ?: return
        var i = -1
        val blocks = parseBlocks(n).map {
            if (it is CheckBlock && it.text.isNotBlank()) {
                i++
                if (i == checkIndex) it.copy(checked = !it.checked) else it
            } else it
        }
        save(ctx, n.copy(description = serializeBlocks(blocks)))
    }

    suspend fun reminderDone(ctx: Context, id: Int) {
        val n = notes(ctx).get(id) ?: return
        Notifier.cancelReminder(ctx, id)
        save(ctx, n.copy(reminderAt = null), touch = false)
    }

    suspend fun snooze(ctx: Context, id: Int, minutes: Int) {
        val n = notes(ctx).get(id) ?: return
        Notifier.cancelReminder(ctx, id)
        save(ctx, n.copy(reminderAt = System.currentTimeMillis() + minutes * 60_000L), touch = false)
    }

    /**
     * Talk-to-create. "Shopping list eggs, milk and bread" becomes a checklist;
     * "Remind me to call the bank tomorrow at 10" becomes a note with a reminder;
     * anything else becomes a text note.
     */
    suspend fun createFromSpeech(ctx: Context, spoken: String, preferList: Boolean): Note {
        val text = spoken.trim()
        val listMatch = Regex("^(?:(.{1,30}?)\\s+)?(?:list|checklist)\\b[:,]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(text)
        val now = System.currentTimeMillis()
        val note: Note = if (preferList || listMatch != null) {
            val prefix = listMatch?.groupValues?.get(1)?.trim().orEmpty()
            val rest = listMatch?.groupValues?.get(2) ?: text
            val items = rest.split(Regex(",|\\s+and\\s+|\\s+e\\s+", RegexOption.IGNORE_CASE))
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() }
                .map { CheckBlock(text = it.replaceFirstChar { c -> c.uppercase() }) }
            val title = when {
                listMatch == null -> ""
                prefix.isNotEmpty() -> "${prefix.replaceFirstChar { it.uppercase() }} list"
                else -> "List"
            }
            Note(title = title, type = NOTE_TYPE_LIST, description = serializeBlocks(items.ifEmpty { listOf(CheckBlock()) }))
        } else {
            val remind = Regex("^remind me (?:to )?", RegexOption.IGNORE_CASE)
            val found = DateDetector.detect(text, now)
            val body = text.replace(remind, "").replaceFirstChar { it.uppercase() }
            Note(
                title = "",
                type = NOTE_TYPE_TEXT,
                description = serializeBlocks(listOf(TextBlock(text = body))),
                reminderAt = if (remind.containsMatchIn(text) && found != null) found.at else null,
            )
        }
        return save(ctx, note)
    }
}
