package com.example.unutma.data

/**
 * Bold and italic in note text, written with stars: `*italic*`, `**bold**`, `***both***`, and
 * any nesting of them (`**bold *and italic* bold**`, `***both** just italic*`).
 *
 * This pairs up runs of stars roughly the way Markdown does: a run can open a style when a
 * character other than a space follows it, and close one when such a character comes before it;
 * a closing run takes stars from the nearest open run before it (3 = bold and italic, 2 = bold,
 * 1 = italic). A single star between two digits (`3*4*5`) is left alone, so sums stay sums.
 *
 * Plain Kotlin (no Android or Compose) so it can be unit-tested.
 */
object Emphasis {
    /** Invisible placeholder inside an empty styled spot (`**​**`), so it has something to style. */
    const val EMPTY = "​"

    /**
     * One styled stretch: [n] stars on each side (1 italic, 2 bold, 3 both), the opening stars at
     * [openStart] and the closing ones ending at [closeEnd]. The styled text is [inner].
     */
    data class Span(val openStart: Int, val closeEnd: Int, val n: Int) {
        val openEnd get() = openStart + n
        val closeStart get() = closeEnd - n
        val inner: IntRange get() = openEnd until closeStart
        val bold get() = n >= 2
        val italic get() = n % 2 == 1
        fun has(stars: Int) = if (stars >= 2) bold else italic
    }

    private class Run(val start: Int, val len: Int) {
        var openLeft = len // unused stars, taken from the right end (next to the text) when opening
        var closeUsed = 0  // stars used from the left end when closing
    }

    fun parse(s: String): List<Span> {
        val out = ArrayList<Span>()
        val openers = ArrayList<Run>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '*') { i++; continue }
            var j = i
            while (j < s.length && s[j] == '*') j++
            val run = Run(i, j - i)
            val before = s.getOrNull(i - 1)
            val after = s.getOrNull(j)
            val sum = run.len == 1 && before?.isDigit() == true && after?.isDigit() == true
            val canClose = !sum && before != null && !before.isWhitespace()
            val canOpen = !sum && after != null && !after.isWhitespace()
            if (canClose) {
                var left = run.len
                while (left > 0 && openers.isNotEmpty()) {
                    val o = openers.last()
                    val k = when { o.openLeft >= 3 && left >= 3 -> 3; o.openLeft >= 2 && left >= 2 -> 2; else -> 1 }
                    val openStart = o.start + o.openLeft - k
                    val closeEnd = run.start + run.closeUsed + k
                    out += Span(openStart, closeEnd, k)
                    o.openLeft -= k
                    run.closeUsed += k
                    left -= k
                    if (o.openLeft == 0) openers.removeAt(openers.lastIndex)
                }
                if (left > 0 && canOpen) {
                    // What's left of this run starts a new style.
                    openers += Run(run.start + run.closeUsed, left)
                }
            } else if (canOpen) openers += run
            i = j
        }
        return out.sortedBy { it.openStart }
    }

    /** Every star that belongs to a style (and so is hidden or removed), as a set of positions. */
    fun markerPositions(s: String): Set<Int> {
        val set = HashSet<Int>()
        for (sp in parse(s)) {
            for (p in sp.openStart until sp.openEnd) set += p
            for (p in sp.closeStart until sp.closeEnd) set += p
        }
        return set
    }

    /** The text without its style stars or placeholders, for previews, widgets and exports. */
    fun strip(s: String): String {
        if (!s.contains('*')) return s.replace(EMPTY, "")
        val hidden = markerPositions(s)
        val b = StringBuilder(s.length)
        for (i in s.indices) if (i !in hidden) b.append(s[i])
        return b.toString().replace(EMPTY, "")
    }

    // ─── B / I buttons ───────────────────────────────────────────────────────

    /** Text plus selection, independent of Compose. */
    data class Edit(val text: String, val selStart: Int, val selEnd: Int = selStart)

    private fun stars(n: Int) = "*".repeat(n)

    /** Changes a span to [newN] stars a side (0 removes them). */
    private fun restyle(t: String, sp: Span, newN: Int): String =
        t.substring(0, sp.openStart) + stars(newN) + t.substring(sp.openEnd, sp.closeStart) + stars(newN) + t.substring(sp.closeEnd)

    /**
     * Pressing B ([stars] = 2) or I ([stars] = 1).
     *  • With text selected: styles it, or takes the style off if it already has it.
     *  • With nothing selected: starts an empty styled spot to type into (B then I gives bold and
     *    italic); inside styled text, steps out of that style so typing goes on without it.
     */
    fun toggle(e: Edit, stars: Int): Edit {
        val t = e.text
        val spans = parse(t)
        if (e.selStart == e.selEnd) {
            val cur = e.selStart
            // The innermost styled stretch the cursor is in.
            val inside = spans.filter { cur >= it.openEnd && cur <= it.closeStart }.maxByOrNull { it.openStart }
            if (inside != null && t.substring(inside.openEnd, inside.closeStart) == EMPTY) {
                // An empty spot: add or remove this style from it.
                val newN = if (inside.has(stars)) inside.n - stars else (inside.n + stars).coerceAtMost(3)
                if (newN <= 0) return Edit(t.removeRange(inside.openStart, inside.closeEnd), inside.openStart)
                val nt = restyle(t, inside, newN)
                return Edit(nt, inside.openStart + newN + EMPTY.length)
            }
            val styled = spans.filter { cur >= it.openEnd && cur <= it.closeStart && it.has(stars) }.maxByOrNull { it.openStart }
            if (styled != null) {
                if (styled.n == stars) return Edit(t, styled.closeEnd) // step out past the closing stars
                // Bold and italic: close just this style here and carry on with the other one.
                val keep = styled.n - stars
                val nt = t.substring(0, styled.closeStart) + stars(stars) + EMPTY + stars(keep) + t.substring(styled.closeEnd)
                return Edit(nt, styled.closeStart + stars + EMPTY.length)
            }
            val nt = t.substring(0, cur) + stars(stars) + EMPTY + stars(stars) + t.substring(cur)
            return Edit(nt, cur + stars + EMPTY.length)
        }
        var a = minOf(e.selStart, e.selEnd)
        var b = maxOf(e.selStart, e.selEnd)
        // The selection is exactly a styled stretch (its text, or with its stars): change its style.
        val same = spans.firstOrNull { (a == it.openEnd && b == it.closeStart) || (a == it.openStart && b == it.closeEnd) }
        if (same != null) {
            val newN = if (same.has(stars)) same.n - stars else (same.n + stars).coerceAtMost(3)
            val nt = restyle(t, same, newN)
            val innerLen = same.closeStart - same.openEnd
            return Edit(nt, same.openStart + newN, same.openStart + newN + innerLen)
        }
        // Stars can't sit next to a space, so leave spaces at the edges outside.
        while (a < b && t[a].isWhitespace()) a++
        while (b > a && t[b - 1].isWhitespace()) b--
        if (a == b) return e
        val nt = t.substring(0, a) + stars(stars) + t.substring(a, b) + stars(stars) + t.substring(b)
        return Edit(nt, a + stars, b + stars)
    }
}
