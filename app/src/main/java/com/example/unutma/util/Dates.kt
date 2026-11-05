package com.example.unutma.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Dates are written the European way: 24-hour clock, day before month. */
object Fmt {
    private val loc = Locale.UK
    private const val DAY = 24L * 60 * 60 * 1000

    private fun f(p: String, ms: Long) = SimpleDateFormat(p, loc).format(Date(ms))

    fun time(ms: Long) = f("HH:mm", ms)

    /** "Tue 27 Oct 07:00", with the year added when it is not this year. */
    fun dateTime(ms: Long): String =
        if (sameYear(ms, System.currentTimeMillis())) f("EEE d MMM HH:mm", ms) else f("EEE d MMM yyyy HH:mm", ms)

    fun date(ms: Long): String =
        if (sameYear(ms, System.currentTimeMillis())) f("EEE d MMM", ms) else f("EEE d MMM yyyy", ms)

    fun dayMonth(ms: Long): String =
        if (sameYear(ms, System.currentTimeMillis())) f("d MMM", ms) else f("d MMM yyyy", ms)

    /** Compact form for list rows: "Mon 09:30" within the coming week, else "12 Dec 09:30". */
    fun reminderShort(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = daysBetween(now, ms)
        return if (d in 0..6) f("EEE HH:mm", ms) else f("d MMM HH:mm", ms)
    }

    fun expiryShort(ms: Long) = "exp " + f("d MMM", ms)

    /** "today", "tomorrow", "in 3d" — or with [long] = true, "in 3 days". */
    fun relative(ms: Long, now: Long = System.currentTimeMillis(), long: Boolean = false): String {
        val d = daysBetween(now, ms)
        return when {
            d == 0L -> "today"
            d == 1L -> "tomorrow"
            d == -1L -> "yesterday"
            d > 1 -> if (long) "in $d days" else "in ${d}d"
            else -> if (long) "${-d} days ago" else "${-d}d ago"
        }
    }

    /**
     * Countdown text. Under a day away it counts hours and minutes ("in 5h", "in 5 h 20 min"),
     * otherwise days ("in 3d", "in 3 days"). Once the moment has passed: "now", then "2d ago".
     */
    fun countdown(ms: Long, now: Long = System.currentTimeMillis(), long: Boolean = false): String {
        val diff = ms - now
        if (diff in 0 until DAY) {
            val h = diff / 3_600_000
            val m = (diff / 60_000) % 60
            return when {
                h == 0L && m == 0L -> "now"
                h == 0L -> if (long) "in $m min" else "in ${m}m"
                else -> if (long) (if (m > 0) "in $h h $m min" else "in $h h") else "in ${h}h"
            }
        }
        if (diff < 0 && -diff < 60 * 60 * 1000) return "now"
        return relative(ms, now, long)
    }

    /** Whole calendar days from [from] to [to] (midnight to midnight). */
    fun daysBetween(from: Long, to: Long): Long {
        val a = startOfDay(from)
        val b = startOfDay(to)
        return Math.round((b - a).toDouble() / DAY)
    }

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }

    /** "Soon" items are highlighted in their label colour on the notes screen. */
    fun isSoon(ms: Long, days: Int = 7, now: Long = System.currentTimeMillis()): Boolean =
        ms >= now - DAY && ms - now <= days * DAY

    fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}

data class DetectedDate(val at: Long, val range: IntRange, val hasTime: Boolean)

/**
 * Finds the first date/time written in a note, e.g. "Dentist on Monday 09:30",
 * "train 27 Oct at 08:00", "call mum tomorrow at 5pm", "27/10", "at 18h30".
 */
object DateDetector {
    private val dows = linkedMapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY,
        "mon" to Calendar.MONDAY, "tues" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY, "thurs" to Calendar.THURSDAY, "thur" to Calendar.THURSDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sun" to Calendar.SUNDAY,
    )
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val relRe = Regex("\\b(today|tonight|tomorrow|tmrw)\\b")
    private val dowRe = Regex("\\b(?:next\\s+|on\\s+)?(" + dows.keys.joinToString("|") + ")\\b")
    private val dmRe = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH\\b(?:\\s+(\\d{4}))?")
    private val mdRe = Regex("\\b$MONTH\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?")
    private val numRe = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
    private val time24Re = Regex("\\b(?:at\\s+)?([01]?\\d|2[0-3])[:h]([0-5]\\d)\\b")
    private val time12Re = Regex("\\b(?:at\\s+)?(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*(am|pm)\\b")
    private val atHourRe = Regex("\\bat\\s+([01]?\\d|2[0-3])\\b(?![:h./\\d])")

    fun detect(text: String, now: Long = System.currentTimeMillis()): DetectedDate? {
        val s = text.lowercase(Locale.ROOT)
        val today = Calendar.getInstance().apply { timeInMillis = now }

        var dayCal: Calendar? = null
        var dateRange: IntRange? = null
        var isDow = false

        relRe.find(s)?.let { m ->
            dayCal = (today.clone() as Calendar).apply {
                if (m.groupValues[1] == "tomorrow" || m.groupValues[1] == "tmrw") add(Calendar.DAY_OF_MONTH, 1)
            }
            dateRange = m.range
        }
        if (dayCal == null) dmRe.find(s)?.let { m ->
            val c = dayFor(today, m.groupValues[1].toInt(), monthIndex(m.groupValues[2]), m.groupValues[3])
            if (c != null) { dayCal = c; dateRange = m.range }
        }
        if (dayCal == null) mdRe.find(s)?.let { m ->
            val c = dayFor(today, m.groupValues[2].toInt(), monthIndex(m.groupValues[1]), m.groupValues[3])
            if (c != null) { dayCal = c; dateRange = m.range }
        }
        if (dayCal == null) numRe.find(s)?.let { m ->
            val c = dayFor(today, m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3])
            if (c != null) { dayCal = c; dateRange = m.range }
        }
        if (dayCal == null) dowRe.find(s)?.let { m ->
            val target = dows[m.groupValues[1]] ?: return@let
            val c = today.clone() as Calendar
            var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (m.value.startsWith("next") && add == 0) add = 7
            c.add(Calendar.DAY_OF_MONTH, add)
            dayCal = c; dateRange = m.range; isDow = true
        }

        var hour = -1
        var minute = 0
        var timeRange: IntRange? = null
        time24Re.find(s)?.let { m ->
            hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt(); timeRange = m.range
        }
        if (hour < 0) time12Re.find(s)?.let { m ->
            var h = m.groupValues[1].toInt() % 12
            if (m.groupValues[3] == "pm") h += 12
            hour = h; minute = m.groupValues[2].toIntOrNull() ?: 0; timeRange = m.range
        }
        if (hour < 0) atHourRe.find(s)?.let { m ->
            hour = m.groupValues[1].toInt(); minute = 0; timeRange = m.range
        }
        if (hour < 0 && s.contains("tonight")) hour = 20

        val day = dayCal
        if (day == null && hour < 0) return null

        val result = (day ?: (today.clone() as Calendar)).apply {
            set(Calendar.HOUR_OF_DAY, if (hour >= 0) hour else 9)
            set(Calendar.MINUTE, if (hour >= 0) minute else 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // A time on its own, or "Monday" when Monday's time has passed, means the next one.
        if (result.timeInMillis <= now) {
            when {
                day == null -> result.add(Calendar.DAY_OF_MONTH, 1)
                isDow -> result.add(Calendar.DAY_OF_MONTH, 7)
                else -> if (Fmt.daysBetween(now, result.timeInMillis) < 0) return null
            }
        }
        if (result.timeInMillis <= now) return null

        val ranges = listOfNotNull(dateRange, timeRange)
        val range = ranges.minOf { it.first }..ranges.maxOf { it.last }
        return DetectedDate(result.timeInMillis, range, hour >= 0)
    }

    private fun monthIndex(name: String): Int = months.indexOf(name.take(3))

    private fun dayFor(today: Calendar, day: Int, month: Int, yearText: String): Calendar? {
        if (month !in 0..11 || day !in 1..31) return null
        val c = today.clone() as Calendar
        val explicitYear = yearText.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        c.set(Calendar.YEAR, explicitYear ?: today.get(Calendar.YEAR))
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.MONTH, month)
        if (day > c.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        c.set(Calendar.DAY_OF_MONTH, day)
        // "27 Oct" after 27 October means next year.
        if (explicitYear == null && Fmt.daysBetween(today.timeInMillis, c.timeInMillis) < 0) c.add(Calendar.YEAR, 1)
        return c
    }
}
