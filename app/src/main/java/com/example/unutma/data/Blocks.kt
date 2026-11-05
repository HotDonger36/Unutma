package com.example.unutma.data

import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.Note
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * A note body is a list of blocks, so text, checkboxes, images and voice clips can sit
 * anywhere in the same note. Images are always their own block, so text goes above or
 * below an image and never on top of it.
 */
sealed interface Block {
    val id: Long
}

data class TextBlock(override val id: Long = newBlockId(), val text: String = "") : Block

data class CheckBlock(
    override val id: Long = newBlockId(),
    val text: String = "",
    val checked: Boolean = false,
    val indent: Int = 0,          // 0 = top level, 1 = sub-item
    val qty: Int? = null,         // quantity stepper; null = no stepper
) : Block

/** A picture; [width] is the share of the note's width it fills (0.3–1), set by dragging its corner. */
data class ImageBlock(override val id: Long = newBlockId(), val path: String, val width: Float = 1f) : Block

data class AudioBlock(override val id: Long = newBlockId(), val path: String, val durationMs: Long) : Block

private val idSeq = AtomicLong(System.nanoTime())
fun newBlockId(): Long = idSeq.incrementAndGet()

// ─── Reading and writing ─────────────────────────────────────────────────────

fun parseBlocks(note: Note?): List<Block> {
    if (note == null) return emptyList()
    return parseBlocks(note.description, note.type)
}

fun parseBlocks(raw: String, type: String): List<Block> {
    val trimmed = raw.trimStart()
    // An encrypted (locked) note: only its picture and voice files can be seen without unlocking.
    if (Vault.isSealed(trimmed)) {
        return Vault.mediaPaths(trimmed).map { p -> if (p.endsWith(".m4a")) AudioBlock(path = p, durationMs = 0) else ImageBlock(path = p) }
    }
    if (trimmed.startsWith("{\"v\"")) {
        try {
            val arr = JSONObject(trimmed).optJSONArray("blocks") ?: JSONArray()
            val out = ArrayList<Block>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                when (o.optString("t")) {
                    "text" -> out += TextBlock(text = o.optString("s"))
                    "check" -> out += CheckBlock(
                        text = o.optString("s"),
                        checked = o.optBoolean("c"),
                        indent = o.optInt("i", 0).coerceIn(0, 1),
                        qty = if (o.has("q")) o.optInt("q") else null,
                    )
                    "img" -> out += ImageBlock(path = o.optString("p"), width = o.optDouble("w", 1.0).toFloat().coerceIn(0.3f, 1f))
                    "audio" -> out += AudioBlock(path = o.optString("p"), durationMs = o.optLong("d"))
                }
            }
            return out
        } catch (_: Exception) {
            // Fall through and treat it as plain text rather than losing anything.
        }
    }
    // Notes saved by the earlier version of the app.
    if (type == NOTE_TYPE_LIST) {
        val items = raw.split("\n").mapNotNull { line ->
            if (line.length < 2 || line[1] != '|') null
            else CheckBlock(text = line.drop(2), checked = line.startsWith("1|"))
        }
        return items.ifEmpty { listOf(CheckBlock()) }
    }
    return listOf(TextBlock(text = raw))
}

fun serializeBlocks(blocks: List<Block>): String {
    val arr = JSONArray()
    for (b in blocks) {
        val o = JSONObject()
        when (b) {
            is TextBlock -> { o.put("t", "text"); o.put("s", b.text) }
            is CheckBlock -> {
                o.put("t", "check"); o.put("s", b.text); o.put("c", b.checked)
                if (b.indent > 0) o.put("i", b.indent)
                if (b.qty != null) o.put("q", b.qty)
            }
            is ImageBlock -> { o.put("t", "img"); o.put("p", b.path); if (b.width < 1f) o.put("w", b.width.toDouble()) }
            is AudioBlock -> { o.put("t", "audio"); o.put("p", b.path); o.put("d", b.durationMs) }
        }
        arr.put(o)
    }
    return JSONObject().put("v", 1).put("blocks", arr).toString()
}

// ─── Helpers used by the list, widgets, search, notifications and export ─────

fun List<Block>.isBlank(): Boolean = all {
    when (it) {
        is TextBlock -> it.text.isBlank()
        is CheckBlock -> it.text.isBlank()
        else -> false
    }
}

/** (done, total) for the checkboxes in a note, or null when it has none. */
fun List<Block>.checkProgress(): Pair<Int, Int>? {
    val checks = filterIsInstance<CheckBlock>().filter { it.text.isNotBlank() }
    if (checks.isEmpty()) return null
    return checks.count { it.checked } to checks.size
}

fun List<Block>.hasImage(): Boolean = any { it is ImageBlock }
fun List<Block>.hasAudio(): Boolean = any { it is AudioBlock }
fun List<Block>.hasChecks(): Boolean = any { it is CheckBlock }
fun List<Block>.hasText(): Boolean = any { it is TextBlock && it.text.isNotBlank() }

fun List<Block>.firstUnchecked(): CheckBlock? =
    filterIsInstance<CheckBlock>().firstOrNull { !it.checked && it.text.isNotBlank() }

/** Everything readable in the note, for search and sharing. */
fun List<Block>.plainText(): String = buildString {
    for (b in this@plainText) {
        when (b) {
            is TextBlock -> if (b.text.isNotBlank()) appendLine(stripMarkdown(b.text))
            is CheckBlock -> if (b.text.isNotBlank()) {
                append("  ".repeat(b.indent))
                append(if (b.checked) "☑ " else "☐ ")
                append(stripMarkdown(b.text))
                if (b.qty != null) append(" ×").append(b.qty)
                appendLine()
            }
            else -> Unit
        }
    }
}.trimEnd()

/** The one-line preview shown under a note's title. */
fun List<Block>.previewLine(): String {
    val text = filterIsInstance<TextBlock>()
        .flatMap { it.text.lines() }
        .map { stripMarkdown(it).trim() }
        .firstOrNull { it.isNotEmpty() }
    if (text != null) return text
    val open = filterIsInstance<CheckBlock>().filter { !it.checked && it.text.isNotBlank() }
    if (open.isNotEmpty()) return open.joinToString(" · ") {
        stripMarkdown(it.text).trim() + (if (it.qty != null) " ×${it.qty}" else "")
    }
    if (any { it is CheckBlock }) return "All done"
    if (hasImage()) return "Image"
    if (hasAudio()) return "Voice note"
    return ""
}

/** Removes **bold**, *italic* and [[link]] markers for previews and exports. */
fun stripMarkdown(s: String): String =
    s.replace(Regex("(?m)^#{1,3} "), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "$1")
        .replace(Regex("\\[\\[(.+?)]]"), "$1")

/** Titles of notes linked from this one with [[Title]]. */
fun List<Block>.linkedTitles(): List<String> {
    val re = Regex("\\[\\[(.+?)]]")
    return flatMap { b ->
        when (b) {
            is TextBlock -> re.findAll(b.text).map { it.groupValues[1].trim() }.toList()
            is CheckBlock -> re.findAll(b.text).map { it.groupValues[1].trim() }.toList()
            else -> emptyList()
        }
    }.distinct()
}

fun noteDisplayTitle(note: Note, blocks: List<Block> = parseBlocks(note)): String =
    stripMarkdown(note.title).trim().ifBlank { blocks.previewLine().take(40).ifBlank { "Untitled" } }
