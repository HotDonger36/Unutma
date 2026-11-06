package com.example.unutma.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.example.unutma.data.Emphasis
import com.example.unutma.ui.theme.GeistMono
import com.example.unutma.util.MathEval

private val linkRe = Regex("\\[\\[(.+?)]]")
private val headingRe = Regex("^(#{1,3}) ")

/**
 * Pressing B or I with nothing selected puts in `**` + this invisible character + `**`, so the
 * pair is a real (hidden) bold/italic spot to type into. It's removed again as soon as there's text.
 */
const val EMPTY_STYLE = "​"

/**
 * Characters that are hidden on screen: [start] until [end] (exclusive). For an [opening] marker a cursor
 * placed just after it is moved in front of it (typing before bold text stays plain); [heading] markers
 * ("## ") keep the cursor after them, so typing at the start of a heading stays in the heading.
 */
internal data class Hidden(val start: Int, val end: Int, val opening: Boolean, val heading: Boolean = false)

/** Every marker the editor hides: bold/italic stars (however combined or nested) and the "# " in front of headings. */
internal fun hiddenMarkers(s: String): List<Hidden> {
    val out = ArrayList<Hidden>()
    for (e in Emphasis.parse(s)) {
        out += Hidden(e.openStart, e.openEnd, opening = true)
        out += Hidden(e.closeStart, e.closeEnd, opening = false)
    }
    var lineStart = 0
    while (lineStart <= s.length) {
        val end = s.indexOf('\n', lineStart).let { if (it < 0) s.length else it }
        headingRe.find(s.substring(lineStart, end))?.let {
            out += Hidden(lineStart, lineStart + it.value.length, opening = false, heading = true)
        }
        lineStart = end + 1
    }
    return out.sortedBy { it.start }
}

/** Applies bold / italic / heading / link styles without changing the text (the editor hides the markers). */
fun styleMarkdown(s: String, marker: Color, link: Color): AnnotatedString {
    val b = AnnotatedString.Builder(s)
    val markerStyle = SpanStyle(color = marker)
    for (e in Emphasis.parse(s)) {
        if (e.bold) b.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), e.openStart, e.closeEnd)
        if (e.italic) b.addStyle(SpanStyle(fontStyle = FontStyle.Italic), e.openStart, e.closeEnd)
    }
    // Heading sizes: "# " = N1 (largest), "## " = N2, "### " = N3.
    var lineStart = 0
    while (lineStart <= s.length) {
        val end = s.indexOf('\n', lineStart).let { if (it < 0) s.length else it }
        val m = headingRe.find(s.substring(lineStart, end))
        if (m != null) {
            val level = m.groupValues[1].length
            val (size, height, weight) = when (level) {
                1 -> Triple(24.sp, 34.sp, FontWeight.SemiBold)
                2 -> Triple(20.sp, 30.sp, FontWeight.SemiBold)
                else -> Triple(18.sp, 28.sp, FontWeight.Medium)
            }
            b.addStyle(ParagraphStyle(lineHeight = height), lineStart, end)
            b.addStyle(SpanStyle(fontSize = size, fontWeight = weight), lineStart + m.value.length, end)
        }
        lineStart = end + 1
    }
    for (m in linkRe.findAll(s)) {
        val r = m.range
        b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), r.first + 2, r.last - 1)
        b.addStyle(SpanStyle(color = link), r.first + 2, r.last - 1)
        b.addStyle(markerStyle, r.first, r.first + 2)
        b.addStyle(markerStyle, r.last - 1, r.last + 1)
    }
    return b.toAnnotatedString()
}

/** The [[title]] around [cursor], if the cursor is inside a note link. */
fun linkAt(s: String, cursor: Int): String? =
    linkRe.findAll(s).firstOrNull { cursor >= it.range.first && cursor <= it.range.last + 1 }?.groupValues?.get(1)?.trim()

// ─── Editing helpers for the hidden markers ──────────────────────────────────

/**
 * Fixes up an edit that touched a hidden marker, so the markers never show up as stray `*` or `#`:
 *  • Backspace on a hidden `*` deletes the letter before it instead (the style stays).
 *  • Backspace on a heading's hidden "## " turns the line back into normal text.
 *  • The invisible placeholder from [EMPTY_STYLE] goes away once there's real text next to it.
 */
fun fixMarkerEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    var v = new
    val o = old.text
    val n = new.text
    if (n.length == o.length - 1 && new.selection.collapsed) {
        val p = (0 until n.length).firstOrNull { o[it] != n[it] } ?: n.length // the deleted character
        val h = hiddenMarkers(o).firstOrNull { p >= it.start && p < it.end }
        if (h != null) {
            v = if (h.heading) {
                TextFieldValue(o.removeRange(h.start, h.end), TextRange(h.start))
            } else {
                // The nearest shown character before the marker; an emptied bold keeps a placeholder to type into.
                val hiddenAt = hiddenMarkers(o).flatMap { it.start until it.end }.toSet()
                val q = (h.start - 1 downTo 0).firstOrNull { it !in hiddenAt }
                if (q == null) old else {
                    val cut = o.removeRange(q, q + 1)
                    val cursor = (old.selection.start - 1).coerceIn(0, cut.length)
                    val e = Emphasis.parse(o).firstOrNull { q in it.inner }
                    if (e != null && e.inner.first == q && e.inner.last == q) {
                        // That was the only letter: keep the empty style spot.
                        TextFieldValue(cut.substring(0, q) + EMPTY_STYLE + cut.substring(q), TextRange(q + 1))
                    } else TextFieldValue(cut, TextRange(cursor))
                }
            }
        }
    }
    return dropPlaceholders(v)
}

/** Removes [EMPTY_STYLE] wherever it isn't alone between two markers (text was typed next to it). */
private fun dropPlaceholders(v: TextFieldValue): TextFieldValue {
    val t = v.text
    if (!t.contains(EMPTY_STYLE)) return v
    val sb = StringBuilder()
    var selStart = v.selection.start
    var selEnd = v.selection.end
    for (i in t.indices) {
        val c = t[i]
        val keep = c.toString() != EMPTY_STYLE || (t.getOrNull(i - 1) == '*' && t.getOrNull(i + 1) == '*')
        if (keep) sb.append(c) else {
            if (i < v.selection.start) selStart--
            if (i < v.selection.end) selEnd--
        }
    }
    if (sb.length == t.length) return v
    return TextFieldValue(sb.toString(), TextRange(selStart.coerceIn(0, sb.length), selEnd.coerceIn(0, sb.length)))
}

/** The text as it's saved: empty style spots and leftover placeholders removed. */
fun cleanForSave(s: String): String =
    s.replace(Regex("\\*{1,3}$EMPTY_STYLE\\*{1,3}"), "").replace(EMPTY_STYLE, "")

/** B ("**") / I ("*"): see [Emphasis.toggle] — bold and italic can be on together. */
fun toggleEmphasis(v: TextFieldValue, marker: String): TextFieldValue {
    val r = Emphasis.toggle(Emphasis.Edit(v.text, v.selection.start, v.selection.end), marker.length)
    return TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
}

/**
 * Note body rendering: markdown styles with the bold/italic/heading markers hidden, plus, with
 * [math] on, the answer after any line ending in "=" (shown in the accent colour, not part
 * of the saved text).
 */
class NoteTransformation(
    private val marker: Color,
    private val link: Color,
    private val answer: Color,
    private val math: Boolean,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val styled = styleMarkdown(s, marker, link)
        val inserts = if (math) MathEval.results(s) else emptyList()
        val hidden = hiddenMarkers(s)
        if (inserts.isEmpty() && hidden.isEmpty()) return TransformedText(styled, OffsetMapping.Identity)

        val hiddenAt = BooleanArray(s.length)
        for (h in hidden) for (i in h.start until h.end) hiddenAt[i] = true
        val insertsAt = inserts.groupBy { it.insertAt }

        // Walk the text once, building the shown text and both cursor maps.
        val out = StringBuilder()
        val toShown = IntArray(s.length + 1)      // before any answer inserted at that point
        val toShownAfter = IntArray(s.length + 1) // after it
        val toSaved = ArrayList<Int>(s.length + 1) // index = shown offset
        val answers = ArrayList<Pair<Int, Int>>()
        for (i in 0..s.length) {
            toShown[i] = out.length
            insertsAt[i]?.forEach { ins ->
                // The cursor can't stop inside an answer; it lands where the answer starts.
                repeat(ins.text.length) { toSaved += i }
                answers += out.length to out.length + ins.text.length
                out.append(ins.text)
            }
            toShownAfter[i] = out.length
            if (i < s.length && !hiddenAt[i]) {
                toSaved += i
                out.append(s[i])
            }
        }
        toSaved += s.length

        // Styles are laid over the shown text afterwards (one range each), so a heading stays one paragraph.
        val b = AnnotatedString.Builder(out.toString())
        for (r in styled.spanStyles) {
            val a = toShown[r.start]; val e = toShown[r.end]
            if (e > a) b.addStyle(r.item, a, e)
        }
        for (r in styled.paragraphStyles) {
            val a = toShown[r.start]; val e = toShownAfter[r.end]
            if (e > a) b.addStyle(r.item, a, e)
        }
        for ((a, e) in answers) b.addStyle(SpanStyle(color = answer, fontFamily = GeistMono), a, e)

        // A cursor just after a hidden opening marker belongs before it, so typing at the
        // start of bold text stays outside the bold. (Closing markers already sit before the next shown character.)
        val openingEnds = hidden.filter { it.opening }.associate { it.end to it.start }
        for (t in toSaved.indices) {
            var o = toSaved[t]
            while (true) o = openingEnds[o] ?: break
            toSaved[t] = o
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, s.length)]
            override fun transformedToOriginal(offset: Int): Int = toSaved[offset.coerceIn(0, toSaved.lastIndex)]
        }
        return TransformedText(b.toAnnotatedString(), mapping)
    }

    override fun equals(other: Any?): Boolean =
        other is NoteTransformation && other.marker == marker && other.link == link && other.answer == answer && other.math == math

    override fun hashCode(): Int = marker.hashCode() * 31 + link.hashCode() * 7 + answer.hashCode() + if (math) 1 else 0
}
