package com.example.unutma.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import com.example.unutma.Label
import com.example.unutma.MainActivity
import com.example.unutma.Note
import com.example.unutma.R
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_WEEKLY
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.Routines
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.stripMarkdown
import com.example.unutma.notify.Notifier
import java.util.Calendar
import kotlin.math.roundToInt

/** Drawing and tap helpers shared by the four small checklist widgets below. */
internal object CheckWidgets {
    private const val TICK = 0xFF0A0A0A.toInt()

    /** Checkboxes with text, numbered the same way as [Repo.toggleCheck]. */
    fun checks(note: Note): List<CheckBlock> = parseBlocks(note).filterIsInstance<CheckBlock>().filter { it.text.isNotBlank() }

    fun itemText(c: CheckBlock) = stripMarkdown(c.text).trim() + (if (c.qty != null) "  ×${c.qty}" else "")

    fun struck(s: String): CharSequence =
        SpannableString(s).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /** A rounded checkbox: an outline, or filled in [color] with a tick. */
    fun box(ctx: Context, checked: Boolean, color: Int, sizeDp: Int = 16): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 3.5f * d
        if (checked) {
            p.color = color
            c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)
            p.color = TICK; p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * d
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            val path = Path().apply {
                moveTo(px * 0.27f, px * 0.52f); lineTo(px * 0.43f, px * 0.68f); lineTo(px * 0.74f, px * 0.35f)
            }
            c.drawPath(path, p)
        } else {
            val sw = 1.2f * d
            p.color = Widgets.MUTED; p.style = Paint.Style.STROKE; p.strokeWidth = sw
            c.drawRoundRect(RectF(sw / 2, sw / 2, px - sw / 2, px - sw / 2), r, r, p)
        }
        return bmp
    }

    /**
     * A day square for the streak widget (same look as in the app): it fills from the bottom to the
     * share of boxes ticked — see-through while unfinished, solid when finished. Today gets a
     * coloured border; days the routine isn't due show a small dot.
     */
    fun day(ctx: Context, sq: Routines.DaySquare, color: Int, sizeDp: Int = 16): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 4f * d
        val full = RectF(0f, 0f, px.toFloat(), px.toFloat())
        if (!sq.scheduled) {
            p.color = Widgets.TRACK
            c.drawCircle(px / 2f, px / 2f, 1.6f * d, p)
            return bmp
        }
        if (sq.percent > 0 && !sq.future) {
            c.save()
            c.clipRect(0f, px * (1 - sq.percent / 100f), px.toFloat(), px.toFloat())
            p.color = color
            if (sq.percent < 100) p.alpha = 102 // about 40 %
            c.drawRoundRect(full, r, r, p)
            c.restore()
        }
        val sw = (if (sq.isToday) 1.6f else 1f) * d
        p.alpha = 255; p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.color = if (sq.isToday) color else if (sq.percent >= 100) color else Widgets.TRACK
        c.drawRoundRect(RectF(sw / 2, sw / 2, px - sw / 2, px - sw / 2), r, r, p)
        return bmp
    }

    /** Tapping this ticks/unticks the [index]-th checkbox (handled in [NoteWidgetBase.onReceive]). */
    fun toggle(ctx: Context, provider: Class<*>, widgetId: Int, noteId: Int, index: Int): PendingIntent {
        val i = Intent(ctx, provider).apply {
            action = Widgets.ACTION_TOGGLE
            data = Uri.parse("unutma://toggle/$widgetId/$index") // keeps each row's intent separate
            putExtra(Notifier.EXTRA_NOTE_ID, noteId)
            putExtra(Widgets.EXTRA_CHECK_INDEX, index)
        }
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun open(ctx: Context, request: Int, noteId: Int) = Widgets.openApp(ctx, MainActivity.ACTION_OPEN_NOTE, request, noteId)

    /** "9 H", "40 MIN", "3 D" until [ms] from now. */
    private fun until(ms: Long): String = when {
        ms < 60 * 60_000L -> "${(ms / 60_000).coerceAtLeast(1)} MIN"
        ms < 48 * 60 * 60_000L -> "${ms / (60 * 60_000)} H"
        else -> "${ms / (24 * 60 * 60_000)} D"
    }

    /** When a routine next unticks itself, or how many items are left. */
    fun resetText(note: Note, done: Int, total: Int): String {
        val now = System.currentTimeMillis()
        return if (Routines.isRoutine(note)) "RESETS IN " + until(Routines.nextPeriodStart(note.resetMode, now) - now)
        else if (done == total) "ALL DONE" else "${total - done} TO GO"
    }
}

// ─── A: Daily checklist ──────────────────────────────────────────────────────

/** Title, progress, three items you can tick, and when the list resets. */
open class DailyListWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a checklist"
    override fun accepts(note: Note) = parseBlocks(note).any { it is CheckBlock }

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val checks = CheckWidgets.checks(note)
        val done = checks.count { it.checked }
        val total = checks.size
        val rows = listOf(
            Triple(R.id.w_a_row0, R.id.w_a_box0, R.id.w_a_text0),
            Triple(R.id.w_a_row1, R.id.w_a_box1, R.id.w_a_text1),
            Triple(R.id.w_a_row2, R.id.w_a_box2, R.id.w_a_text2),
        )
        return RemoteViews(ctx.packageName, R.layout.widget_daily).apply {
            setTextViewText(R.id.w_a_title, noteDisplayTitle(note))
            setImageViewBitmap(R.id.w_a_ring, Widgets.ring(ctx, done, total, color, 14, 2f))
            setTextViewText(R.id.w_a_count, "$done/$total")
            setInt(R.id.w_a_rule, "setBackgroundColor", color)
            setOnClickPendingIntent(R.id.w_a_root, CheckWidgets.open(ctx, 65_000 + widgetId, note.id))
            if (note.isLocked) {
                setViewVisibility(R.id.w_a_locked, View.VISIBLE)
                setViewVisibility(R.id.w_a_space, View.GONE)
                setTextViewText(R.id.w_a_footer, "")
            } else {
                // Up to three items around the first one still to do — fewer if the widget is short.
                val fit = rowsThatFit(usedDp = 77, rowDp = 32, max = rows.size)
                val first = checks.indexOfFirst { !it.checked }.let { if (it < 0) 0 else it }
                val start = (first - (if (fit >= 3) 1 else 0)).coerceIn(0, (total - fit).coerceAtLeast(0))
                if (roomDp in 1 until 100) setViewVisibility(R.id.w_a_footer, View.GONE)
                for ((slot, ids) in rows.take(fit).withIndex()) {
                    val i = start + slot
                    val c = checks.getOrNull(i) ?: continue
                    val (row, box, text) = ids
                    setViewVisibility(row, View.VISIBLE)
                    setImageViewBitmap(box, CheckWidgets.box(ctx, c.checked, color))
                    if (c.checked) {
                        setTextViewText(text, CheckWidgets.struck(CheckWidgets.itemText(c)))
                        setTextColor(text, Widgets.DONE)
                    } else setTextViewText(text, CheckWidgets.itemText(c))
                    setContentDescription(row, (if (c.checked) "Done: " else "Not done: ") + CheckWidgets.itemText(c))
                    setOnClickPendingIntent(row, CheckWidgets.toggle(ctx, this@DailyListWidget.javaClass, widgetId, note.id, i))
                }
                setTextViewText(R.id.w_a_footer, CheckWidgets.resetText(note, done, total))
            }
            Widgets.scaleText(ctx, widgetId, this, R.id.w_a_title to 16f, R.id.w_a_count to 12f, R.id.w_a_text0 to 14f,
                R.id.w_a_text1 to 14f, R.id.w_a_text2 to 14f, R.id.w_a_footer to 10f, R.id.w_a_locked to 12f)
        }
    }
}

// ─── B: Progress ring ────────────────────────────────────────────────────────

/** A big ring with done/total inside, the list's name, and the next item (tap its box to tick it). */
class RingListWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a checklist"
    override fun accepts(note: Note) = parseBlocks(note).any { it is CheckBlock }

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val checks = CheckWidgets.checks(note)
        val done = checks.count { it.checked }
        val total = checks.size
        val allDone = total > 0 && done == total
        return RemoteViews(ctx.packageName, R.layout.widget_ring).apply {
            setImageViewBitmap(R.id.w_b_ring, Widgets.ring(ctx, done, total, color, 64, 4f))
            // "1" large, "/3" smaller and dimmer. On the filled (all done) ring the text turns dark.
            val count = SpannableStringBuilder("$done").apply {
                val s = length
                append("/$total")
                setSpan(RelativeSizeSpan(0.75f), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(if (allDone) 0xFF2A2A2A.toInt() else Widgets.MUTED), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextViewText(R.id.w_b_count, count)
            if (allDone) setTextColor(R.id.w_b_count, 0xFF0A0A0A.toInt())
            setContentDescription(R.id.w_b_ring, "$done of $total done")
            setTextViewText(R.id.w_b_label, (label?.name ?: "List").uppercase())
            setTextColor(R.id.w_b_label, color)
            setTextViewText(R.id.w_b_title, noteDisplayTitle(note))
            setOnClickPendingIntent(R.id.w_b_root, CheckWidgets.open(ctx, 66_000 + widgetId, note.id))

            val next = checks.indexOfFirst { !it.checked }
            when {
                note.isLocked -> {
                    setViewVisibility(R.id.w_b_box, View.GONE)
                    setTextViewText(R.id.w_b_next, "Locked")
                }
                next >= 0 -> {
                    setImageViewBitmap(R.id.w_b_box, CheckWidgets.box(ctx, false, color, 14))
                    setTextViewText(R.id.w_b_next, CheckWidgets.itemText(checks[next]))
                    setContentDescription(R.id.w_b_next_row, "Tick " + CheckWidgets.itemText(checks[next]))
                    setOnClickPendingIntent(R.id.w_b_next_row, CheckWidgets.toggle(ctx, this@RingListWidget.javaClass, widgetId, note.id, next))
                }
                else -> {
                    setImageViewBitmap(R.id.w_b_box, CheckWidgets.box(ctx, true, color, 14))
                    setTextViewText(R.id.w_b_next, "All done")
                    setTextColor(R.id.w_b_next, color)
                }
            }
            // Too short for the ring and the NEXT row: keep the ring.
            if (roomDp in 1 until 160) for (v in listOf(R.id.w_b_rule, R.id.w_b_next_label, R.id.w_b_next_row)) setViewVisibility(v, View.GONE)
            Widgets.scaleText(ctx, widgetId, this, R.id.w_b_count to 20f, R.id.w_b_label to 11f, R.id.w_b_title to 17f,
                R.id.w_b_next_label to 10f, R.id.w_b_next to 15f)
        }
    }
}

// ─── C: Next up ──────────────────────────────────────────────────────────────

/** Title, a segmented progress bar, and a big card that ticks the next item when tapped. */
class NextUpWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a checklist"
    override fun accepts(note: Note) = parseBlocks(note).any { it is CheckBlock }

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val checks = CheckWidgets.checks(note)
        val done = checks.count { it.checked }
        val total = checks.size
        return RemoteViews(ctx.packageName, R.layout.widget_segments).apply {
            setTextViewText(R.id.w_c_title, noteDisplayTitle(note))
            setTextViewText(R.id.w_c_count, "$done/$total")
            setTextColor(R.id.w_c_count, color)
            setTextColor(R.id.w_c_hint, color)
            setOnClickPendingIntent(R.id.w_c_root, CheckWidgets.open(ctx, 67_000 + widgetId, note.id))

            // One segment per item; long lists are shown as ten segments.
            removeAllViews(R.id.w_c_segs)
            val segs = total.coerceIn(1, 10)
            val filled = if (total <= 10) done else (done * 10f / total).roundToInt()
            repeat(segs) { i ->
                val seg = RemoteViews(ctx.packageName, R.layout.widget_seg)
                seg.setInt(R.id.w_seg, "setColorFilter", if (i < filled) color else Widgets.TRACK)
                addView(R.id.w_c_segs, seg)
            }

            val next = checks.indexOfFirst { !it.checked }
            when {
                note.isLocked -> {
                    setTextViewText(R.id.w_c_hint, "LOCKED")
                    setViewVisibility(R.id.w_c_box, View.GONE)
                    setTextViewText(R.id.w_c_next, "Open to see it")
                    setOnClickPendingIntent(R.id.w_c_card, CheckWidgets.open(ctx, 67_500 + widgetId, note.id))
                }
                next >= 0 -> {
                    setImageViewBitmap(R.id.w_c_box, CheckWidgets.box(ctx, false, color))
                    setTextViewText(R.id.w_c_next, CheckWidgets.itemText(checks[next]))
                    setContentDescription(R.id.w_c_card, "Tick " + CheckWidgets.itemText(checks[next]))
                    setOnClickPendingIntent(R.id.w_c_card, CheckWidgets.toggle(ctx, this@NextUpWidget.javaClass, widgetId, note.id, next))
                }
                else -> {
                    setTextViewText(R.id.w_c_hint, CheckWidgets.resetText(note, done, total))
                    setImageViewBitmap(R.id.w_c_box, CheckWidgets.box(ctx, true, color))
                    setTextViewText(R.id.w_c_next, "All done")
                    setOnClickPendingIntent(R.id.w_c_card, CheckWidgets.open(ctx, 67_500 + widgetId, note.id))
                }
            }
            // Short widget: one line in the card, and no hint above it when it's very short.
            if (roomDp in 1 until 150) setInt(R.id.w_c_next, "setMaxLines", 1)
            if (roomDp in 1 until 125) setViewVisibility(R.id.w_c_hint, View.GONE)
            Widgets.scaleText(ctx, widgetId, this, R.id.w_c_title to 16f, R.id.w_c_count to 12f, R.id.w_c_hint to 10f, R.id.w_c_next to 16f)
        }
    }
}

// ─── D: Streak ───────────────────────────────────────────────────────────────

/** Days in a row with everything ticked, this week's days (Monday first), and the next item. */
class StreakWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a checklist"
    override fun accepts(note: Note) = parseBlocks(note).any { it is CheckBlock }

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val checks = CheckWidgets.checks(note)
        val done = checks.count { it.checked }
        val total = checks.size
        val hist = Routines.history(ctx, note.id)
        val today = Routines.today()
        val streak = Routines.streak(note.resetMode, hist, today)
        val weekly = note.resetMode == RESET_WEEKLY
        val dayIds = listOf(R.id.w_d_day0, R.id.w_d_day1, R.id.w_d_day2, R.id.w_d_day3, R.id.w_d_day4, R.id.w_d_day5, R.id.w_d_day6)
        val letterIds = listOf(R.id.w_d_letter0, R.id.w_d_letter1, R.id.w_d_letter2, R.id.w_d_letter3, R.id.w_d_letter4, R.id.w_d_letter5, R.id.w_d_letter6)
        return RemoteViews(ctx.packageName, R.layout.widget_streak).apply {
            setTextViewText(R.id.w_d_title, noteDisplayTitle(note))
            setTextViewText(R.id.w_d_count, "$done/$total today")
            setTextColor(R.id.w_d_count, color)
            setTextViewText(R.id.w_d_streak, streak.toString())
            setTextColor(R.id.w_d_streak, color)
            val unit = if (weekly) "week" else "day"
            setTextViewText(R.id.w_d_streak_unit, if (streak == 1) "$unit streak" else "${unit}s streak")
            setOnClickPendingIntent(R.id.w_d_root, CheckWidgets.open(ctx, 68_000 + widgetId, note.id))

            // This week, Monday first.
            for ((i, sq) in Routines.week(note.resetMode, hist, today).withIndex()) {
                setImageViewBitmap(dayIds[i], CheckWidgets.day(ctx, sq, color))
                if (sq.isToday) setTextColor(letterIds[i], Widgets.TEXT)
            }

            val next = checks.firstOrNull { !it.checked }
            setTextViewText(R.id.w_d_next, when {
                note.isLocked -> "Locked"
                next != null -> SpannableStringBuilder("Next: ").apply {
                    val s = length
                    append(CheckWidgets.itemText(next))
                    setSpan(ForegroundColorSpan(Widgets.TEXT), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> "All done today"
            })
            // Drop the "Next" line, then the week, when the widget is too short for them.
            if (roomDp in 1 until 178) setViewVisibility(R.id.w_d_next, View.GONE)
            if (roomDp in 1 until 150) setViewVisibility(R.id.w_d_week, View.GONE)
            Widgets.scaleText(ctx, widgetId, this, R.id.w_d_title to 16f, R.id.w_d_count to 12f, R.id.w_d_streak to 44f,
                R.id.w_d_streak_unit to 14f, R.id.w_d_next to 13f)
        }
    }
}
