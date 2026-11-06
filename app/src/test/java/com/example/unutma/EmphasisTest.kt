package com.example.unutma

import com.example.unutma.data.Emphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bold/italic stars: every combination hides its stars, and B/I toggle as expected. */
class EmphasisTest {
    private val z = Emphasis.EMPTY

    @Test fun plainStyles() {
        assertEquals("bold", Emphasis.strip("**bold**"))
        assertEquals("it", Emphasis.strip("*it*"))
        assertEquals("both", Emphasis.strip("***both***"))
    }

    @Test fun boldAndItalicTogether() {
        val s = Emphasis.parse("***both***")
        assertEquals(1, s.size)
        assertTrue(s[0].bold && s[0].italic)
    }

    @Test fun nestedStyles() {
        assertEquals("bold and italic bold", Emphasis.strip("**bold *and italic* bold**"))
        assertEquals("both just italic", Emphasis.strip("***both** just italic*"))
        assertEquals("it bold it", Emphasis.strip("*it **bold** it*"))
        assertEquals("abcd", Emphasis.strip("***abc*d**"))
    }

    @Test fun leftAlone() {
        assertEquals("3*4*5", Emphasis.strip("3*4*5"))
        assertEquals("a * b", Emphasis.strip("a * b"))
        assertEquals("**", Emphasis.strip("**"))
        assertEquals("Ola***", Emphasis.strip("Ola***"))
    }

    @Test fun boldButtonWithNothingSelected() {
        val r = Emphasis.toggle(Emphasis.Edit("Hi ", 3), 2)
        assertEquals("Hi **$z**", r.text)
        assertEquals(6, r.selStart) // after the placeholder
    }

    @Test fun boldThenItalicMakesBoth() {
        val b = Emphasis.toggle(Emphasis.Edit("", 0), 2)
        val bi = Emphasis.toggle(b, 1)
        assertEquals("***$z***", bi.text)
        assertEquals(4, bi.selStart)
        // and pressing B again leaves just italic
        val i = Emphasis.toggle(bi, 2)
        assertEquals("*$z*", i.text)
    }

    @Test fun stepOutOfItalicKeepsBold() {
        // "***abc|***", press I: italic ends, bold carries on.
        val r = Emphasis.toggle(Emphasis.Edit("***abc***", 6), 1)
        assertEquals("***abc*$z**", r.text)
        val typed = r.text.substring(0, r.selStart) + "d" + r.text.substring(r.selStart)
        assertEquals("abc" + z + "d", Emphasis.strip(typed).let { it.substring(0, 3) + z + it.substring(3) }.replace(z + z, z))
        val spans = Emphasis.parse("***abc*d**")
        assertTrue(spans.any { it.bold && !it.italic })
        assertTrue(spans.any { it.italic && !it.bold })
    }

    @Test fun selectionToggles() {
        val on = Emphasis.toggle(Emphasis.Edit("make this bold", 10, 14), 2)
        assertEquals("make this **bold**", on.text)
        val off = Emphasis.toggle(on, 2)
        assertEquals("make this bold", off.text)
        val both = Emphasis.toggle(Emphasis.toggle(Emphasis.Edit("x yz", 2, 4), 2), 1)
        assertEquals("x ***yz***", both.text)
    }

    @Test fun selectionWithSpacesAtTheEdges() {
        val r = Emphasis.toggle(Emphasis.Edit("a word here", 1, 7), 2)
        assertEquals("a **word** here", r.text)
    }
}
