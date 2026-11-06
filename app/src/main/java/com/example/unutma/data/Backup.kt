package com.example.unutma.data

import android.content.Context
import android.net.Uri
import com.example.unutma.Label
import com.example.unutma.Note
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Back up and restore: everything in one .zip, so notes can move between Unutma (debug), Unutma and
 * a new phone.
 *
 *   backup.json   notes (including Archive and Recently deleted), labels, and the app's settings:
 *                 routine progress and streaks, your own note order, folded sections, widget sizes…
 *   media/…       the pictures and voice notes the notes use (paths in the notes say "media/<file>")
 *
 * Locked notes are written unlocked (the backup asks for your fingerprint first), still marked as
 * locked, so the app restoring them locks them again with its own key.
 * Restoring adds what isn't there yet: a note counts as already there when one with the same title
 * and the same creation time exists, so restoring the same backup twice changes nothing.
 */
object Backup {
    private const val FORMAT = "unutma-backup"
    private const val MEDIA = "media/"

    data class Result(val notes: Int, val skipped: Int, val labels: Int, val lockedLeftOut: Int)

    /** Settings that belong to one phone (which note each widget shows) aren't moved. */
    private fun movable(key: String) = !key.startsWith("widget_") || key == "widget_text_size"

    // ─── Backing up ──────────────────────────────────────────────────────────

    /** "Unutma-backup-2026-11-06.zip" */
    fun fileName(): String = "Unutma-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date()) + ".zip"

    /**
     * Writes the backup to [out]. Call it right after the fingerprint check when there are locked
     * notes, so they can be opened; any that still can't be are counted in [Result.lockedLeftOut].
     */
    suspend fun write(ctx: Context, out: OutputStream): Result {
        val notes = Repo.notes(ctx).all()
        val labels = Repo.labels(ctx).all()
        val media = LinkedHashMap<String, File>() // name in the zip → file
        var lockedLeftOut = 0
        val notesJson = JSONArray()
        for (n in notes) {
            var desc = n.description
            if (Vault.isSealed(desc)) {
                desc = Vault.open(desc)?.first ?: run { lockedLeftOut++; null } ?: continue
            }
            // Point picture and voice paths at the copies in the zip.
            val blocks = parseBlocks(desc, n.type).map { b ->
                when (b) {
                    is ImageBlock -> b.copy(path = addMedia(media, b.path))
                    is AudioBlock -> b.copy(path = addMedia(media, b.path))
                    else -> b
                }
            }
            if (blocks.any { it is ImageBlock || it is AudioBlock }) desc = serializeBlocks(blocks)
            notesJson.put(noteJson(n.copy(description = desc)))
        }
        val labelsJson = JSONArray()
        for (l in labels) labelsJson.put(JSONObject().put("id", l.id).put("name", l.name).put("color", l.color).put("position", l.position))
        val prefsJson = JSONObject()
        for ((k, v) in Prefs.get(ctx).all()) {
            if (!movable(k) || v == null) continue
            prefsJson.put(k, when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> JSONObject().put("t", "s").put("v", v.toString())
            })
        }
        val root = JSONObject()
            .put("format", FORMAT).put("version", 1).put("exportedAt", System.currentTimeMillis())
            .put("notes", notesJson).put("labels", labelsJson).put("prefs", prefsJson)

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, file) in media) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Result(notesJson.length(), 0, labels.size, lockedLeftOut)
    }

    private fun addMedia(media: MutableMap<String, File>, path: String): String {
        val f = File(path)
        val name = MEDIA + f.name
        media.putIfAbsent(name, f)
        return name
    }

    private fun noteJson(n: Note) = JSONObject()
        .put("id", n.id).put("title", n.title).put("description", n.description).put("type", n.type).put("color", n.color)
        .put("isPinned", n.isPinned).put("isArchived", n.isArchived).put("labelId", n.labelId ?: JSONObject.NULL)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
        .put("reminderAt", n.reminderAt ?: JSONObject.NULL).put("nagMinutes", n.nagMinutes)
        .put("countdownAt", n.countdownAt ?: JSONObject.NULL).put("expiresAt", n.expiresAt ?: JSONObject.NULL)
        .put("deletedAt", n.deletedAt ?: JSONObject.NULL).put("resetMode", n.resetMode).put("lastResetAt", n.lastResetAt)
        .put("pinnedToBar", n.pinnedToBar).put("isLocked", n.isLocked)

    private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)

    // ─── Restoring ───────────────────────────────────────────────────────────

    /** Adds the notes, labels and settings from a backup zip. Reminders and pins are set up again. */
    suspend fun restore(ctx: Context, uri: Uri): Result {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var json: String? = null
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == "backup.json" -> json = zip.readBytes().toString(Charsets.UTF_8)
                        e.name.startsWith(MEDIA) -> {
                            // Only the file name is used, so nothing can be written outside the temp folder.
                            val name = File(e.name).name
                            if (name.isNotBlank()) File(tmp, name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        } ?: throw IllegalArgumentException("Couldn't open that file.")
        val root = JSONObject(json ?: throw IllegalArgumentException("That isn't an Unutma backup."))
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("That isn't an Unutma backup.")

        // Labels: reuse one with the same name, otherwise add it.
        val existingLabels = Repo.labels(ctx).all().toMutableList()
        val labelMap = HashMap<Int, Int>()
        var labelsAdded = 0
        val labelsJson = root.optJSONArray("labels") ?: JSONArray()
        for (i in 0 until labelsJson.length()) {
            val o = labelsJson.getJSONObject(i)
            val name = o.optString("name")
            val found = existingLabels.firstOrNull { it.name.equals(name, ignoreCase = true) }
            labelMap[o.optInt("id")] = found?.id ?: run {
                val pos = (existingLabels.maxOfOrNull { it.position } ?: -1) + 1
                val id = Repo.labels(ctx).insert(Label(name = name, color = o.optString("color", "PURPLE"), position = pos)).toInt()
                existingLabels += Label(id, name, o.optString("color", "PURPLE"), pos)
                labelsAdded++
                id
            }
        }

        // Notes: add the ones that aren't already here.
        val have = Repo.notes(ctx).all().associateBy { it.title to it.createdAt }
        val noteMap = HashMap<Int, Int>()
        var added = 0; var skipped = 0
        val notesJson = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notesJson.length()) {
            val o = notesJson.getJSONObject(i)
            val oldId = o.optInt("id")
            val title = o.optString("title")
            val created = o.optLong("createdAt")
            have[title to created]?.let { noteMap[oldId] = it.id; skipped++; continue }
            val type = o.optString("type", "TEXT")
            var desc = o.optString("description")
            val blocks = parseBlocks(desc, type)
            if (blocks.any { it is ImageBlock || it is AudioBlock }) {
                desc = serializeBlocks(blocks.map { b ->
                    when (b) {
                        is ImageBlock -> b.copy(path = copyIn(ctx, tmp, b.path, "images"))
                        is AudioBlock -> b.copy(path = copyIn(ctx, tmp, b.path, "audio"))
                        else -> b
                    }
                })
            }
            val n = Note(
                title = title, description = desc, type = type, color = o.optString("color"),
                isPinned = o.optBoolean("isPinned"), isArchived = o.optBoolean("isArchived"),
                labelId = o.longOrNull("labelId")?.toInt()?.let { labelMap[it] },
                reminderAt = o.longOrNull("reminderAt"), nagMinutes = o.optInt("nagMinutes"),
                countdownAt = o.longOrNull("countdownAt"), expiresAt = o.longOrNull("expiresAt"),
                deletedAt = o.longOrNull("deletedAt"), resetMode = o.optString("resetMode"),
                lastResetAt = o.optLong("lastResetAt"), pinnedToBar = o.optBoolean("pinnedToBar"),
                isLocked = o.optBoolean("isLocked"),
            )
            val saved = Repo.save(ctx, n)
            // Keep the original dates (so "Newest first" and duplicate checks still work).
            Repo.save(ctx, saved.copy(createdAt = created, updatedAt = o.optLong("updatedAt", created)), touch = false)
            noteMap[oldId] = saved.id
            added++
        }

        restorePrefs(ctx, root.optJSONObject("prefs") ?: JSONObject(), noteMap, labelMap)
        Repo.sealLockedNotes(ctx) // locked notes get locked again with this phone's key
        tmp.deleteRecursively()
        com.example.unutma.widget.Widgets.refreshAll(ctx)
        return Result(added, skipped, labelsAdded, 0)
    }

    /** Copies a picture or voice note out of the backup into the app's own folder; returns its new path. */
    private fun copyIn(ctx: Context, tmp: File, zipPath: String, dir: String): String {
        val src = File(tmp, File(zipPath).name)
        if (!src.exists()) return zipPath
        val ext = src.extension.ifBlank { if (dir == "audio") "m4a" else "jpg" }
        val dest = File(File(ctx.filesDir, dir).apply { mkdirs() }, UUID.randomUUID().toString() + "." + ext)
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    /**
     * Settings come along; ones tied to a note or label follow it to its new number here. A note's
     * routine progress only fills in where this phone has none yet.
     */
    private fun restorePrefs(ctx: Context, prefs: JSONObject, noteMap: Map<Int, Int>, labelMap: Map<Int, Int>) {
        val p = Prefs.get(ctx)
        val current = p.all()
        fun section(k: String): String? = when {
            k == "none" -> k
            k.startsWith("l") -> k.drop(1).toIntOrNull()?.let { labelMap[it] }?.let { "l$it" }
            else -> null
        }
        val out = HashMap<String, Any>()
        for (key in prefs.keys()) {
            val o = prefs.getJSONObject(key)
            var value: Any = when (o.optString("t")) {
                "b" -> o.optBoolean("v")
                "i" -> o.optInt("v")
                "l" -> o.optLong("v")
                "f" -> o.optDouble("v").toFloat()
                "set" -> o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet<String>()
                else -> o.optString("v")
            }
            var k = key
            // Keys that name a note.
            val perNote = listOf("progress_", "ticks_", "best_streak_", "done_days_").firstOrNull { key.startsWith(it) }
            if (perNote != null) {
                val newId = key.removePrefix(perNote).toIntOrNull()?.let { noteMap[it] } ?: continue
                k = perNote + newId
                if (current.containsKey(k)) continue
            }
            when {
                key.startsWith("note_order_") -> {
                    k = "note_order_" + (section(key.removePrefix("note_order_")) ?: continue)
                    value = value.toString().split(',').mapNotNull { it.toIntOrNull()?.let { id -> noteMap[id] } }.joinToString(",")
                }
                key == "collapsed_sections" -> value = (value as? Set<*>)?.mapNotNull { section(it.toString()) }?.toSet() ?: continue
                key.startsWith("event_note_") -> value = value.toString().toIntOrNull()?.let { noteMap[it] }?.toString() ?: continue
                key == "widget_label" || key.startsWith("widget_label_") -> continue
            }
            out[k] = value
        }
        p.putAll(out)
    }
}
