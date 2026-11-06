package com.example.unutma.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.unutma.Label
import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.NOTE_TYPE_TEXT
import com.example.unutma.Note
import com.example.unutma.ui.theme.LABEL_COLOURS
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Import from Google Keep, using the zip from takeout.google.com (choose just Keep).
 *
 *  • Text notes become notes; Keep lists become checklists, ticked items stay ticked.
 *  • Pinned stays pinned, archived goes to Archive, notes in Keep's trash are skipped.
 *  • Keep labels become Unutma labels (an existing one with the same name is reused). Unutma has one
 *    label per note, so a note with several gets the first.
 *  • Keep colours map to the nearest Unutma colour (used for labels the import creates).
 *  • Pictures and voice recordings are copied into the note; created and edited dates are kept.
 *  • Importing again skips notes already brought over (same title and creation time).
 */
object KeepImport {
    data class Result(val notes: Int, val checklists: Int, val alreadyHere: Int, val trashed: Int, val labels: Int, val unreadable: Int)

    /** Keep's colour names → Unutma colour keys. */
    private val COLOURS = mapOf(
        "RED" to "CORAL", "ORANGE" to "AMBER", "YELLOW" to "AMBER", "GREEN" to "GREEN", "TEAL" to "TEAL",
        "BLUE" to "BLUE", "CERULEAN" to "BLUE", "DARK_BLUE" to "BLUE", "PURPLE" to "PURPLE", "PINK" to "PINK",
        "BROWN" to "AMBER", "GRAY" to "GREY",
    )

    suspend fun run(ctx: Context, uri: Uri): Result {
        val tmp = File(ctx.cacheDir, "keep").apply { deleteRecursively(); mkdirs() }
        val notesJson = ArrayList<String>()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    // Takeout keeps Keep under Takeout/Keep/ (the folder name can be translated); take any .json there.
                    if (name.endsWith(".json", ignoreCase = true)) notesJson += zip.readBytes().toString(Charsets.UTF_8)
                    else if (!name.endsWith(".html", ignoreCase = true)) {
                        // Attachments, kept by file name only (so nothing lands outside the temp folder).
                        val file = File(name).name
                        if (file.isNotBlank()) File(tmp, file).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } ?: throw IllegalArgumentException("Couldn't open that file.")
        if (notesJson.isEmpty()) throw IllegalArgumentException("No Keep notes in that zip. Export Keep from takeout.google.com and pick the .zip it gives you.")

        val labels = Repo.labels(ctx).all().toMutableList()
        val have = Repo.notes(ctx).all().map { it.title to it.createdAt }.toHashSet()
        var added = 0; var lists = 0; var already = 0; var trashed = 0; var labelsAdded = 0; var bad = 0

        for (raw in notesJson) {
            val o = try { JSONObject(raw) } catch (_: Exception) { bad++; continue }
            // Other JSON files in the zip (not notes) have none of these.
            if (!o.has("textContent") && !o.has("listContent") && !o.has("title")) continue
            if (o.optBoolean("isTrashed")) { trashed++; continue }
            val title = o.optString("title").trim()
            val created = o.optLong("createdTimestampUsec", 0) / 1000
            val edited = o.optLong("userEditedTimestampUsec", 0) / 1000
            if ((title to created) in have) { already++; continue }

            val blocks = ArrayList<Block>()
            o.optString("textContent").takeIf { it.isNotBlank() }?.let { blocks += TextBlock(text = it.trimEnd()) }
            val list = o.optJSONArray("listContent")
            if (list != null) for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) blocks += CheckBlock(text = text, checked = item.optBoolean("isChecked"))
            }
            val attachments = o.optJSONArray("attachments")
            if (attachments != null) for (i in 0 until attachments.length()) {
                val a = attachments.getJSONObject(i)
                val src = File(tmp, File(a.optString("filePath")).name)
                if (!src.exists()) continue
                val mime = a.optString("mimetype")
                if (mime.startsWith("audio")) {
                    val dest = copy(ctx, src, "audio")
                    blocks += AudioBlock(path = dest.absolutePath, durationMs = duration(dest))
                } else if (mime.startsWith("image")) {
                    blocks += ImageBlock(path = copy(ctx, src, "images").absolutePath)
                }
            }
            if (blocks.isEmpty() && title.isEmpty()) continue
            val isList = list != null && list.length() > 0

            // The note's first Keep label (Unutma notes have one).
            val colour = COLOURS[o.optString("color")] ?: ""
            val labelName = o.optJSONArray("labels")?.optJSONObject(0)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            val labelId = labelName?.let { name ->
                labels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: run {
                    val used = labels.map { it.color }.toSet()
                    val c = colour.ifEmpty { LABEL_COLOURS.map { it.key }.firstOrNull { it !in used } ?: "PURPLE" }
                    val pos = (labels.maxOfOrNull { it.position } ?: -1) + 1
                    val id = Repo.labels(ctx).insert(Label(name = name, color = c, position = pos)).toInt()
                    labels += Label(id, name, c, pos); labelsAdded++
                    id
                }
            }
            val n = Note(
                title = title,
                type = if (isList) NOTE_TYPE_LIST else NOTE_TYPE_TEXT,
                description = serializeBlocks(blocks.ifEmpty { listOf(TextBlock()) }),
                color = colour,
                isPinned = o.optBoolean("isPinned"),
                isArchived = o.optBoolean("isArchived"),
                labelId = labelId,
            )
            val saved = Repo.save(ctx, n)
            if (created > 0) Repo.save(ctx, saved.copy(createdAt = created, updatedAt = if (edited > 0) edited else created), touch = false)
            have += title to created
            added++
            if (isList) lists++
        }
        tmp.deleteRecursively()
        com.example.unutma.widget.Widgets.refreshAll(ctx)
        return Result(added, lists, already, trashed, labelsAdded, bad)
    }

    private fun copy(ctx: Context, src: File, dir: String): File {
        val ext = src.extension.ifBlank { if (dir == "audio") "m4a" else "jpg" }
        val dest = File(File(ctx.filesDir, dir).apply { mkdirs() }, UUID.randomUUID().toString() + "." + ext)
        src.copyTo(dest, overwrite = true)
        return dest
    }

    private fun duration(f: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(f.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release(); d
        }
    } catch (_: Exception) { 0L }

    /** "Imported 84 notes (12 checklists). 3 already here, 5 in Keep's trash skipped." */
    fun summary(r: Result): String = buildString {
        append("Imported ${r.notes} note${if (r.notes == 1) "" else "s"}")
        if (r.checklists > 0) append(" (${r.checklists} checklist${if (r.checklists == 1) "" else "s"})")
        append(".")
        if (r.labels > 0) append(" ${r.labels} new label${if (r.labels == 1) "" else "s"}.")
        if (r.alreadyHere > 0) append(" ${r.alreadyHere} already here, skipped.")
        if (r.trashed > 0) append(" ${r.trashed} in Keep's trash, skipped.")
        if (r.unreadable > 0) append(" ${r.unreadable} couldn't be read.")
    }
}
