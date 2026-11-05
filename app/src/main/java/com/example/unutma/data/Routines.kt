package com.example.unutma.data

import android.content.Context
import com.example.unutma.Note
import com.example.unutma.RESET_DAILY
import com.example.unutma.RESET_NONE
import com.example.unutma.RESET_WEEKLY
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Routines: checklists that untick themselves on a schedule, with a streak.
 *
 * The schedule is kept in [Note.resetMode] (so the database didn't change):
 *  • ""                 not a routine
 *  • "DAILY"            every day
 *  • "WEEKLY"           once a week (unticks on Monday)
 *  • "DAYS:1,3,5"       on chosen weekdays (1 = Monday … 7 = Sunday); unticks each morning
 *  • "EVERY:3:20400"    every 3 days, counted from day 20400 (a local epoch day)
 *
 * How much was ticked each day is kept in Prefs (`progress_<noteId>`, day → percent), which is
 * what the streak, the best streak and the week squares are worked out from.
 */
object Routines {
    private const val DAY = 24L * 60 * 60 * 1000

    // ─── Days ────────────────────────────────────────────────────────────────

    /** A time as a whole number of days, in the phone's time zone. */
    fun dayOf(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / DAY
    fun today(): Long = dayOf(System.currentTimeMillis())

    /** Local midnight at the start of [day]. */
    fun startOf(day: Long): Long {
        val guess = day * DAY
        return guess - TimeZone.getDefault().getOffset(guess)
    }

    /** 1 = Monday … 7 = Sunday. Day 0 (1 Jan 1970) was a Thursday. */
    fun weekday(day: Long): Int = (((day + 3) % 7 + 7) % 7 + 1).toInt()
    fun mondayOf(day: Long): Long = day - (weekday(day) - 1)

    // ─── Schedules ───────────────────────────────────────────────────────────

    fun isRoutine(note: Note) = note.resetMode != RESET_NONE

    fun weekdaysMode(days: Set<Int>) = "DAYS:" + days.sorted().joinToString(",")
    fun everyMode(n: Int, from: Long = today()) = "EVERY:$n:$from"

    fun weekdays(mode: String): Set<Int> =
        if (mode.startsWith("DAYS:")) mode.removePrefix("DAYS:").split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet() else emptySet()

    /** (every N days, counted from day) for an "EVERY" schedule. */
    fun every(mode: String): Pair<Int, Long>? {
        if (!mode.startsWith("EVERY:")) return null
        val p = mode.split(':')
        val n = p.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: return null
        return n to (p.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /** Is the routine due on [day]? (Plain checklists count as every day.) */
    fun isScheduled(mode: String, day: Long): Boolean = when {
        mode.startsWith("DAYS:") -> weekday(day) in weekdays(mode)
        mode.startsWith("EVERY:") -> every(mode)!!.let { (n, from) -> ((day - from) % n + n) % n == 0L }
        else -> true
    }

    fun describe(mode: String): String = when {
        mode == RESET_NONE -> "Off"
        mode == RESET_DAILY -> "Every day"
        mode == RESET_WEEKLY -> "Every week"
        mode.startsWith("DAYS:") -> weekdays(mode).sorted().joinToString(", ") { DAY_NAMES[it - 1] }.ifEmpty { "No days" }
        mode.startsWith("EVERY:") -> "Every ${every(mode)?.first ?: 1} days"
        else -> "Off"
    }

    val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** When the current period began (the boxes were last unticked), as epoch ms. */
    fun periodStart(mode: String, now: Long): Long {
        val today = dayOf(now)
        return when {
            mode == RESET_WEEKLY -> startOf(mondayOf(today))
            mode.startsWith("EVERY:") -> {
                val (n, from) = every(mode)!!
                startOf(today - ((today - from) % n + n) % n)
            }
            else -> startOf(today) // daily, and chosen weekdays (a fresh list each day)
        }
    }

    /** When the boxes will next be unticked. */
    fun nextPeriodStart(mode: String, now: Long): Long {
        val today = dayOf(now)
        return when {
            mode == RESET_WEEKLY -> startOf(mondayOf(today) + 7)
            mode.startsWith("EVERY:") -> startOf(dayOf(periodStart(mode, now)) + every(mode)!!.first)
            mode.startsWith("DAYS:") -> {
                val days = weekdays(mode)
                val next = (1..7).firstOrNull { weekday(today + it) in days } ?: 1
                startOf(today + next)
            }
            else -> startOf(today + 1)
        }
    }

    // ─── Progress history ────────────────────────────────────────────────────

    /** Day → percent of boxes ticked that day (the best it got to). */
    fun history(ctx: Context, noteId: Int): Map<Long, Int> {
        val p = Prefs.get(ctx)
        val out = HashMap<Long, Int>()
        // Days recorded by the first streak widget count as complete.
        for (d in p.doneDays(noteId)) out[d] = 100
        p.raw("progress_$noteId")?.let { s ->
            try {
                val o = JSONObject(s)
                for (k in o.keys()) k.toLongOrNull()?.let { out[it] = o.optInt(k) }
            } catch (_: Exception) {}
        }
        return out
    }

    /** Remembers how much of [note] is ticked today. Unticking later the same day lowers it again. */
    fun record(ctx: Context, note: Note) {
        val (done, total) = parseBlocks(note).checkProgress() ?: return
        val today = today()
        val pct = if (total == 0) 0 else done * 100 / total
        val p = Prefs.get(ctx)
        val o = try { JSONObject(p.raw("progress_${note.id}") ?: "{}") } catch (_: Exception) { JSONObject() }
        if (o.optInt(today.toString(), -1) == pct) return
        o.put(today.toString(), pct)
        // Keep about a year.
        val old = o.keys().asSequence().toList().filter { (it.toLongOrNull() ?: 0) < today - 400 }
        old.forEach { o.remove(it) }
        p.setRaw("progress_${note.id}", o.toString())
        // The first streak widget's record of completed days stays in step.
        val days = p.doneDays(note.id)
        val updated = if (pct == 100) days + today else days - today
        if (updated != days) p.setDoneDays(note.id, updated.filter { it > today - 400 }.toSet())
        val best = streak(note.resetMode, history(ctx, note.id), today)
        if (best > p.bestStreak(note.id)) p.setBestStreak(note.id, best)
    }

    fun forget(ctx: Context, noteId: Int) {
        val p = Prefs.get(ctx)
        p.setRaw("progress_$noteId", null); p.clearDoneDays(noteId); p.setBestStreak(noteId, 0)
    }

    /**
     * The current streak: scheduled days (or weeks, for a weekly routine) in a row with every box
     * ticked. Days it isn't due don't break it, and today only counts once it's finished.
     */
    fun streak(mode: String, hist: Map<Long, Int>, today: Long = today()): Int {
        if (mode == RESET_WEEKLY) {
            fun weekDone(monday: Long) = (0..6).any { (hist[monday + it] ?: 0) >= 100 }
            var m = mondayOf(today)
            if (!weekDone(m)) m -= 7
            var n = 0
            while (n < 60 && weekDone(m)) { n++; m -= 7 }
            return n
        }
        var n = 0
        var d = today
        for (i in 0 until 400) {
            if (isScheduled(mode, d)) {
                val done = (hist[d] ?: 0) >= 100
                if (done) n++ else if (d != today) break
            }
            d--
        }
        return n
    }

    fun best(ctx: Context, note: Note): Int =
        maxOf(Prefs.get(ctx).bestStreak(note.id), streak(note.resetMode, history(ctx, note.id)))

    /** One square of a week strip. */
    data class DaySquare(val day: Long, val percent: Int, val scheduled: Boolean, val isToday: Boolean, val future: Boolean)

    /** Monday to Sunday of this week. */
    fun week(mode: String, hist: Map<Long, Int>, today: Long = today()): List<DaySquare> {
        val monday = mondayOf(today)
        return (0..6).map { i ->
            val d = monday + i
            DaySquare(d, hist[d] ?: 0, mode == RESET_WEEKLY || isScheduled(mode, d), d == today, d > today)
        }
    }
}
