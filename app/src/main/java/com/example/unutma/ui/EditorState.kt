package com.example.unutma.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.unutma.Note
import com.example.unutma.data.AudioBlock
import com.example.unutma.data.Block
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.ImageBlock
import com.example.unutma.data.TextBlock
import com.example.unutma.data.isBlank
import com.example.unutma.data.serializeBlocks

/** One row in the editor: a block plus, for text and checkbox rows, its live text field value. */
data class EItem(val block: Block, val tfv: TextFieldValue = TextFieldValue(textOf(block))) {
    val id get() = block.id
}

private fun textOf(b: Block) = when (b) {
    is TextBlock -> b.text
    is CheckBlock -> b.text
    else -> ""
}

private data class Snap(val title: String, val blocks: List<Block>)

/**
 * Everything the note editor is editing, plus the undo/redo history.
 * [version] goes up on every change; the screen saves a moment after it stops changing.
 */
@Stable
class EditorState(note: Note, blocks: List<Block>) {
    var note by mutableStateOf(note)            // the saved note's other fields (label, reminder…)
    var title by mutableStateOf(TextFieldValue(note.title))
    val items = mutableStateListOf<EItem>()
    var focusedId by mutableStateOf<Long?>(null)
    /** Set when a new row should grab focus (with the cursor at [focusCursor]). */
    var pendingFocus by mutableStateOf<Long?>(null)
    var focusCursor = 0
    var version by mutableIntStateOf(0)
        private set
    var saveStatus by mutableStateOf("saved")
    /** The [version] that was last written to the database (so just opening a note doesn't re-save it). */
    @Volatile var savedVersion = 0
    /** A locked note's encryption key while it's open, so saving can encrypt it again (see data/Vault.kt). */
    var vaultKey: ByteArray? = null

    private val history = ArrayList<Snap>()
    private var index by mutableIntStateOf(0)
    private var historySize by mutableIntStateOf(0)
    private var lastKind = ""
    private var lastBlock: Long? = null
    private var lastTime = 0L

    val canUndo get() = index > 0
    val canRedo get() = index < historySize - 1

    init {
        items.addAll(blocks.ifEmpty { listOf(TextBlock()) }.map { EItem(it) })
        normalize()
        history += snap(); historySize = 1
    }

    // ─── Reading out ─────────────────────────────────────────────────────────

    fun blocks(): List<Block> = items.map { it.materialize() }

    private fun EItem.materialize(): Block = when (val b = block) {
        is TextBlock -> b.copy(text = tfv.text)
        is CheckBlock -> b.copy(text = tfv.text)
        else -> b
    }

    fun isEmpty() = title.text.isBlank() && blocks().isBlank()

    /** The note as it should be saved right now (without the invisible "empty bold" placeholders). */
    fun toNote(): Note = note.copy(
        title = cleanForSave(title.text).trim(),
        description = serializeBlocks(blocks().map {
            when (it) { is TextBlock -> it.copy(text = cleanForSave(it.text)); is CheckBlock -> it.copy(text = cleanForSave(it.text)); else -> it }
        }),
    )

    fun indexOf(id: Long) = items.indexOfFirst { it.id == id }

    // ─── History ─────────────────────────────────────────────────────────────

    private fun snap() = Snap(title.text, blocks())

    /** Records a change. Typing in the same field within a second is merged into one undo step. */
    private fun changed(kind: String, blockId: Long?) {
        val now = System.currentTimeMillis()
        val merge = kind == "type" && lastKind == "type" && lastBlock == blockId && now - lastTime < 1000 && index == historySize - 1 && index > 0
        while (history.size > index + 1) history.removeAt(history.size - 1)
        if (merge) history[index] = snap() else {
            history += snap()
            if (history.size > 100) history.removeAt(0)
            index = history.size - 1
        }
        historySize = history.size
        lastKind = kind; lastBlock = blockId; lastTime = now
        version++
    }

    /** For changes to the note's other fields (label, reminder…) — saved, but not undoable. */
    fun metaChanged() { version++ }

    fun undo() { if (canUndo) restore(--index) }
    fun redo() { if (canRedo) restore(++index) }

    private fun restore(i: Int) {
        val s = history[i]
        title = TextFieldValue(s.title, TextRange(s.title.length))
        items.clear()
        items.addAll(s.blocks.map { b -> EItem(b, TextFieldValue(textOf(b), TextRange(textOf(b).length))) })
        lastKind = ""
        version++
    }

    // ─── Edits ───────────────────────────────────────────────────────────────

    fun updateTitle(v: TextFieldValue) {
        val fixed = fixMarkerEdit(title, v)
        val textChanged = fixed.text != title.text
        title = fixed
        if (textChanged) changed("type", -1L)
    }

    fun edit(id: Long, v: TextFieldValue) {
        val i = indexOf(id); if (i < 0) return
        val old = items[i]
        // Hidden bold/italic/heading markers are never left half-deleted (see fixMarkerEdit).
        val fixed = fixMarkerEdit(old.tfv, v)
        val next = if (old.block is TextBlock) continueList(old.tfv, fixed) else fixed
        items[i] = old.copy(tfv = next)
        if (next.text != old.tfv.text) changed("type", id)
    }

    // ─── Numbered lists, bullets and heading sizes (text rows) ───────────────

    private val numberedRe = Regex("^(\\s*)(\\d{1,3})([.)]) (.*)$")
    private val bulletRe = Regex("^(\\s*)([•\\-]) (.*)$")
    private val headingRe = Regex("^(#{1,3}) ")

    /**
     * Pressing Enter on "2. Milk" starts the next line with "3. ", and on "• item" with "• ".
     * Pressing Enter on an empty "3. " ends the list.
     */
    private fun continueList(old: TextFieldValue, v: TextFieldValue): TextFieldValue {
        val t = v.text
        val cur = v.selection.start
        if (!v.selection.collapsed || t.length != old.text.length + 1 || cur < 1 || t[cur - 1] != '\n') return v
        val lineStart = t.lastIndexOf('\n', cur - 2) + 1
        val line = t.substring(lineStart, cur - 1)
        val num = numberedRe.find(line)
        val bul = if (num == null) bulletRe.find(line) else null
        val (indent, prefix, content) = when {
            num != null -> Triple(num.groupValues[1], "${num.groupValues[2].toInt() + 1}${num.groupValues[3]} ", num.groupValues[4])
            bul != null -> Triple(bul.groupValues[1], "${bul.groupValues[2]} ", bul.groupValues[3])
            else -> return v
        }
        if (content.isBlank()) {
            // Empty item: remove its marker and the new line, which ends the list.
            val nt = t.substring(0, lineStart) + t.substring(cur)
            return TextFieldValue(nt, TextRange(lineStart))
        }
        val insert = indent + prefix
        val nt = t.substring(0, cur) + insert + t.substring(cur)
        return TextFieldValue(nt, TextRange(cur + insert.length))
    }

    private fun focusedTextRow(): Int? {
        val i = focusedId?.let { indexOf(it) } ?: return null
        return if (i >= 0 && items[i].block is TextBlock) i else null
    }

    /** 0 = normal text, 1–3 = the heading size of the line the cursor is on. */
    fun headingLevelAtFocus(): Int {
        val i = focusedTextRow() ?: return 0
        val t = items[i].tfv.text
        val cur = items[i].tfv.selection.start.coerceIn(0, t.length)
        val ls = t.lastIndexOf('\n', cur - 1) + 1
        return headingRe.find(t.substring(ls))?.groupValues?.get(1)?.length ?: 0
    }

    /** The N button: normal → N1 (largest) → N2 → N3 → normal, for the line the cursor is on. */
    fun cycleHeading() {
        val level = headingLevelAtFocus()
        setHeading(if (level >= 3) 0 else level + 1)
    }

    /** Holding the N button: sets the line the cursor is on to [level] (0 = normal, 1–3 = N1–N3). */
    fun setHeading(level: Int) {
        val i = focusedTextRow() ?: return
        val item = items[i]
        val t = item.tfv.text
        val cur = item.tfv.selection.start.coerceIn(0, t.length)
        val ls = t.lastIndexOf('\n', cur - 1) + 1
        val m = headingRe.find(t.substring(ls))
        val oldPrefix = m?.value?.length ?: 0
        val newPrefix = if (level <= 0) "" else "#".repeat(level.coerceAtMost(3)) + " "
        if (oldPrefix == 0 && newPrefix.isEmpty()) return
        val nt = t.substring(0, ls) + newPrefix + t.substring(ls + oldPrefix)
        val newCur = (cur - oldPrefix + newPrefix.length).coerceAtLeast(ls + newPrefix.length)
        items[i] = item.copy(tfv = TextFieldValue(nt, TextRange(newCur.coerceAtMost(nt.length))))
        changed("struct", item.id)
    }

    /** The numbered-list button: numbers the selected lines (or the current one), or removes the numbers. */
    fun toggleNumbered() {
        val i = focusedTextRow()
        if (i == null) {
            // On a checkbox or nowhere: start a numbered list in a new paragraph.
            val tb = TextBlock(text = "1. ")
            val at = focusedId?.let { indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: items.size
            items.add(at, EItem(tb, TextFieldValue("1. ", TextRange(3))))
            focus(tb.id, 3); normalize(); changed("struct", tb.id); return
        }
        val item = items[i]
        val t = item.tfv.text
        val sel = item.tfv.selection
        val start = t.lastIndexOf('\n', (sel.min - 1).coerceAtLeast(-1)) + 1
        val end = t.indexOf('\n', sel.max).let { if (it < 0) t.length else it }
        val lines = t.substring(start, end).split('\n')
        val allNumbered = lines.all { numberedRe.containsMatchIn(it) }
        // Continue the numbering from the line above, if it is a numbered line.
        val prevLine = if (start == 0) "" else t.substring(t.lastIndexOf('\n', start - 2) + 1, start - 1)
        var n = numberedRe.find(prevLine)?.groupValues?.get(2)?.toInt()?.plus(1) ?: 1
        val newLines = lines.map { line ->
            if (allNumbered) numberedRe.find(line)!!.let { it.groupValues[1] + it.groupValues[4] }
            else {
                val body = numberedRe.find(line)?.let { it.groupValues[1] + it.groupValues[4] } ?: line
                val m = Regex("^(\\s*)(.*)$").find(body)!!
                m.groupValues[1] + "${n++}. " + m.groupValues[2]
            }
        }
        val block = newLines.joinToString("\n")
        val nt = t.substring(0, start) + block + t.substring(end)
        items[i] = item.copy(tfv = TextFieldValue(nt, TextRange(start + block.length)))
        changed("struct", item.id)
    }

    fun toggle(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val b = items[i].block as? CheckBlock ?: return
        items[i] = items[i].copy(block = b.copy(checked = !b.checked))
        changed("toggle", id)
    }

    /** Enter in a checkbox row: split it at the cursor into a new row; Enter on an empty row ends the list. */
    fun enterInCheck(id: Long, v: TextFieldValue) {
        val i = indexOf(id); if (i < 0) return
        val b = items[i].block as? CheckBlock ?: return
        val text = v.text
        val nl = text.indexOf('\n')
        val before = text.substring(0, nl)
        val after = text.substring(nl + 1)
        if (before.isEmpty() && after.isEmpty()) {
            // Empty item + Enter → back to normal text.
            items[i] = EItem(TextBlock(id = b.id))
            focus(b.id, 0)
            changed("struct", id)
            return
        }
        items[i] = items[i].copy(block = b, tfv = TextFieldValue(before, TextRange(before.length)))
        // Enter gives one new row; pasting several lines gives one row per line.
        val lines = after.split('\n')
        var at = i + 1
        var last: CheckBlock? = null
        for (line in lines) {
            val nb = CheckBlock(text = line, indent = b.indent)
            items.add(at++, EItem(nb, TextFieldValue(line, TextRange(0))))
            last = nb
        }
        last?.let { focus(it.id, if (lines.size == 1) 0 else it.text.length) }
        changed("struct", id)
    }

    /** Backspace at the very start of a row. */
    fun backspaceAtStart(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val item = items[i]
        when (val b = item.block) {
            is CheckBlock -> when {
                b.indent > 0 -> { items[i] = item.copy(block = b.copy(indent = 0)); changed("struct", id) }
                item.tfv.text.isEmpty() -> {
                    items.removeAt(i)
                    focusPrevious(i)
                    normalize(); changed("struct", id)
                }
                else -> { items[i] = EItem(TextBlock(id = b.id), item.tfv); changed("struct", id) }
            }
            is TextBlock -> {
                val prev = items.getOrNull(i - 1) ?: return
                if (prev.block is CheckBlock && item.tfv.text.contains('\n')) {
                    // Only the first line joins the checkbox above; the rest stays a paragraph.
                    val first = item.tfv.text.substringBefore('\n')
                    val rest = item.tfv.text.substringAfter('\n')
                    val joinAt = prev.tfv.text.length
                    items[i - 1] = prev.copy(tfv = TextFieldValue(prev.tfv.text + first, TextRange(joinAt)))
                    items[i] = item.copy(tfv = TextFieldValue(rest))
                    focus(prev.id, joinAt)
                    changed("struct", id)
                } else if (prev.block is TextBlock || prev.block is CheckBlock) {
                    // Join this text onto the end of the row above.
                    val joinAt = prev.tfv.text.length
                    val joined = prev.tfv.text + (if (prev.block is TextBlock && item.tfv.text.isNotEmpty()) "\n" else "") + item.tfv.text
                    items[i - 1] = prev.copy(tfv = TextFieldValue(joined, TextRange(joinAt)))
                    items.removeAt(i)
                    focus(prev.id, joinAt)
                    normalize(); changed("struct", id)
                } else if (item.tfv.text.isEmpty() && items.size > 1) {
                    items.removeAt(i); focusPrevious(i); normalize(); changed("struct", id)
                }
            }
            else -> Unit
        }
    }

    private fun focusPrevious(removedIndex: Int) {
        val prev = items.getOrNull(removedIndex - 1) ?: items.getOrNull(0) ?: return
        focus(prev.id, prev.tfv.text.length)
    }

    fun focus(id: Long, cursor: Int) { pendingFocus = id; focusCursor = cursor }

    /** Moves the cursor in a row without recording an undo step. */
    fun placeCursor(id: Long, cursor: Int) {
        val i = indexOf(id); if (i < 0) return
        val t = items[i].tfv
        items[i] = items[i].copy(tfv = t.copy(selection = TextRange(cursor.coerceIn(0, t.text.length))))
    }

    /** Paths of every picture and voice clip, for tidying up files that are no longer used. */
    fun mediaPaths(): List<String> = items.mapNotNull {
        when (val b = it.block) { is ImageBlock -> b.path; is AudioBlock -> b.path; else -> null }
    }

    /** Toolbar checkbox: turns the current line into a checkbox (or a checkbox back into text). */
    fun toggleChecklistAtFocus() {
        val i = focusedId?.let { indexOf(it) } ?: -1
        if (i < 0) {
            val nb = CheckBlock()
            val lastIdx = items.lastIndex
            if (lastIdx >= 0 && items[lastIdx].block is TextBlock && items[lastIdx].tfv.text.isEmpty()) items[lastIdx] = EItem(nb)
            else items.add(EItem(nb))
            focus(nb.id, 0); normalize(); changed("struct", nb.id); return
        }
        val item = items[i]
        when (val b = item.block) {
            is CheckBlock -> { items[i] = EItem(TextBlock(id = b.id), item.tfv); focus(b.id, item.tfv.selection.start) }
            is TextBlock -> {
                val text = item.tfv.text
                val cur = item.tfv.selection.start.coerceIn(0, text.length)
                val lineStart = text.lastIndexOf('\n', cur - 1) + 1
                val lineEnd = text.indexOf('\n', cur).let { if (it < 0) text.length else it }
                val before = if (lineStart == 0) "" else text.substring(0, lineStart - 1)
                val line = text.substring(lineStart, lineEnd)
                val after = if (lineEnd < text.length) text.substring(lineEnd + 1) else ""
                val check = CheckBlock(text = line)
                val newRows = ArrayList<EItem>()
                if (lineStart > 0) newRows += EItem(TextBlock(id = b.id), TextFieldValue(before))
                newRows += EItem(check, TextFieldValue(line, TextRange(cur - lineStart)))
                if (after.isNotEmpty()) newRows += EItem(TextBlock(), TextFieldValue(after))
                items.removeAt(i)
                items.addAll(i, newRows)
                focus(check.id, cur - lineStart)
            }
            else -> return
        }
        normalize(); changed("struct", focusedId)
    }

    /** Puts an image or voice clip at the cursor: the text is split so it sits above and below. */
    fun insertMedia(block: Block) {
        val i = focusedId?.let { indexOf(it) } ?: -1
        if (i < 0) { items.add(EItem(block)); normalize(); changed("struct", block.id); return }
        val item = items[i]
        if (item.block is TextBlock) {
            val text = item.tfv.text
            val cur = item.tfv.selection.start.coerceIn(0, text.length)
            // The picture takes the place of the line the cursor is on; empty lines above it stay,
            // so it lands where you were typing instead of jumping to the top.
            val before = text.substring(0, cur).let { if (it.endsWith("\n")) it.dropLast(1) else it }
            val after = text.substring(cur).let { if (it.startsWith("\n")) it.drop(1) else it }
            val rows = ArrayList<EItem>()
            if (cur > 0) rows += EItem(TextBlock(id = item.id), TextFieldValue(before))
            rows += EItem(block)
            val tail = TextBlock(text = after)
            rows += EItem(tail, TextFieldValue(after, TextRange(0)))
            items.removeAt(i); items.addAll(i, rows)
            focus(tail.id, 0)
        } else {
            items.add(i + 1, EItem(block))
        }
        normalize(); changed("struct", block.id)
    }

    /** Adds text (for example from "Extract text") as a new paragraph after [afterId]. */
    fun insertTextAfter(afterId: Long, text: String) {
        val i = indexOf(afterId)
        val tb = TextBlock(text = text)
        if (i < 0) items.add(EItem(tb)) else items.add(i + 1, EItem(tb))
        normalize(); changed("struct", tb.id)
    }

    /** Inserts [s] at the cursor of the focused text row (used for [[links]]). */
    fun insertAtCursor(s: String) {
        val i = focusedId?.let { indexOf(it) } ?: -1
        if (i < 0 || (items[i].block !is TextBlock && items[i].block !is CheckBlock)) {
            val last = items.lastOrNull()
            if (last != null && last.block is TextBlock) {
                val t = last.tfv.text + (if (last.tfv.text.isEmpty() || last.tfv.text.endsWith("\n")) "" else " ") + s
                items[items.lastIndex] = last.copy(tfv = TextFieldValue(t, TextRange(t.length)))
            } else items.add(EItem(TextBlock(text = s)))
            changed("struct", null); return
        }
        val item = items[i]
        val t = item.tfv.text
        val sel = item.tfv.selection
        val nt = t.substring(0, sel.min) + s + t.substring(sel.max)
        items[i] = item.copy(tfv = TextFieldValue(nt, TextRange(sel.min + s.length)))
        changed("struct", item.id)
    }

    /** Bold / italic on or off (see [toggleEmphasis]); the stars themselves are never shown. */
    fun wrap(marker: String) {
        if (focusedId == -1L) {
            title = toggleEmphasis(title, marker)
            changed("struct", -1L); return
        }
        val i = focusedId?.let { indexOf(it) } ?: return
        if (i < 0) return
        val item = items[i]
        if (item.block !is TextBlock && item.block !is CheckBlock) return
        items[i] = item.copy(tfv = toggleEmphasis(item.tfv, marker))
        changed("struct", item.id)
    }

    fun setIndent(id: Long, indent: Int) {
        val i = indexOf(id); if (i < 0) return
        val b = items[i].block as? CheckBlock ?: return
        items[i] = items[i].copy(block = b.copy(indent = indent.coerceIn(0, 1)))
        changed("struct", id)
    }

    /** Dragging a picture's corner: how much of the width it fills. */
    fun setImageWidth(id: Long, width: Float) {
        val i = indexOf(id); if (i < 0) return
        val b = items[i].block as? ImageBlock ?: return
        items[i] = items[i].copy(block = b.copy(width = width.coerceIn(0.3f, 1f)))
        changed("struct", id)
    }

    fun setQty(id: Long, qty: Int?) {
        val i = indexOf(id); if (i < 0) return
        val b = items[i].block as? CheckBlock ?: return
        items[i] = items[i].copy(block = b.copy(qty = qty?.coerceIn(0, 9999)))
        changed("qty", id)
    }

    fun toText(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val item = items[i]
        if (item.block is CheckBlock) { items[i] = EItem(TextBlock(id = item.id), item.tfv); changed("struct", id) }
    }

    fun remove(id: Long) {
        val i = indexOf(id); if (i < 0) return
        items.removeAt(i)
        // Two text rows that were separated by the removed image become one again.
        val a = items.getOrNull(i - 1); val b = items.getOrNull(i)
        if (a != null && b != null && a.block is TextBlock && b.block is TextBlock) {
            val joined = listOf(a.tfv.text, b.tfv.text).filter { it.isNotEmpty() }.joinToString("\n")
            items[i - 1] = a.copy(tfv = TextFieldValue(joined))
            items.removeAt(i)
        }
        normalize(); changed("struct", id)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val it = items.removeAt(from)
        items.add(to, it)
    }

    fun moveDone() { normalize(); changed("move", null) }

    /** Untick every box (manual "Reset checklist"). */
    fun uncheckAll() {
        for (i in items.indices) {
            val b = items[i].block
            if (b is CheckBlock && b.checked) items[i] = items[i].copy(block = b.copy(checked = false))
        }
        changed("struct", null)
    }

    /** Always leave somewhere to type after an image or voice clip. */
    private fun normalize() {
        if (items.isEmpty()) items.add(EItem(TextBlock()))
        val last = items.last().block
        if (last is ImageBlock || last is AudioBlock) items.add(EItem(TextBlock()))
    }
}
