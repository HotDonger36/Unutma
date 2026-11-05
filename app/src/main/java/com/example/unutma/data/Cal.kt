package com.example.unutma.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/** One occurrence of a calendar event (repeating events give one per day they happen). */
data class CalEvent(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val location: String?,
    val repeats: Boolean,
)

/** A calendar on the phone (Google accounts, or local ones). */
data class CalInfo(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean)

/**
 * Google Calendar, through Android's calendar storage (the events your Google account syncs to the
 * phone). Reading needs READ_CALENDAR, adding and deleting WRITE_CALENDAR. Every call reads or
 * writes on the calling thread, so call these off the main thread.
 */
object Cal {
    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalInfo> {
        if (!canRead(ctx)) return emptyList()
        val out = ArrayList<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
                while (c.moveToNext()) out += CalInfo(
                    id = c.getLong(0), name = c.getString(1) ?: "Calendar", account = c.getString(2) ?: "",
                    color = c.getInt(3), writable = c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        } catch (_: Exception) {}
        return out
    }

    /** Every occurrence between [from] and [to] (epoch ms), earliest first. */
    fun events(ctx: Context, from: Long, to: Long): List<CalEvent> {
        if (!canRead(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.RRULE,
        )
        val out = ArrayList<CalEvent>()
        try {
            ctx.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    // All-day events are stored at midnight UTC; move them to local midnight so they land on the right day.
                    val begin = if (allDay) utcDayToLocal(c.getLong(1)) else c.getLong(1)
                    val end = if (allDay) utcDayToLocal(c.getLong(2)) else c.getLong(2)
                    out += CalEvent(
                        eventId = c.getLong(0), begin = begin, end = end, title = c.getString(3)?.ifBlank { null } ?: "(No title)",
                        allDay = allDay, calendarId = c.getLong(5), calendarName = c.getString(6) ?: "",
                        color = c.getInt(7), location = c.getString(8)?.ifBlank { null }, repeats = !c.getString(9).isNullOrBlank(),
                    )
                }
            }
        } catch (_: Exception) {}
        return out.sortedWith(compareBy<CalEvent> { !it.allDay }.thenBy { it.begin })
            .sortedBy { Routines.dayOf(it.begin) }
    }

    private fun utcDayToLocal(utcMs: Long): Long {
        val day = utcMs / (24L * 60 * 60 * 1000)
        return Routines.startOf(day)
    }

    /** Today's events, and everything until the end of the week's last day. */
    fun today(ctx: Context): List<CalEvent> {
        val t = Routines.today()
        return events(ctx, Routines.startOf(t), Routines.startOf(t + 1))
    }

    /**
     * Adds an event. [repeat] is NONE / DAILY / WEEKLY / MONTHLY / YEARLY, [reminderMinutes] null for none.
     * Returns the new event's id, or null if the calendar refused it.
     */
    fun insert(
        ctx: Context, calendarId: Long, title: String, start: Long, end: Long, allDay: Boolean,
        reminderMinutes: Int?, repeat: String,
    ): Long? {
        if (!canWrite(ctx)) return null
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calendarId)
        v.put(CalendarContract.Events.TITLE, title)
        val (s, e) = if (allDay) localDayToUtc(start) to localDayToUtc(end).coerceAtLeast(localDayToUtc(start) + 24L * 60 * 60 * 1000)
        else start to end.coerceAtLeast(start)
        v.put(CalendarContract.Events.DTSTART, s)
        v.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
        if (repeat == "NONE") v.put(CalendarContract.Events.DTEND, e)
        else {
            // Repeating events have a length instead of an end time.
            v.put(CalendarContract.Events.RRULE, "FREQ=$repeat")
            v.put(CalendarContract.Events.DURATION, if (allDay) "P${((e - s) / (24L * 60 * 60 * 1000)).coerceAtLeast(1)}D" else "PT${((e - s) / 60_000).coerceAtLeast(1)}M")
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
            val id = ContentUris.parseId(uri)
            if (reminderMinutes != null) {
                val r = ContentValues()
                r.put(CalendarContract.Reminders.EVENT_ID, id)
                r.put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
            }
            id
        } catch (_: Exception) { null }
    }

    private fun localDayToUtc(localMs: Long): Long = Routines.dayOf(localMs) * 24L * 60 * 60 * 1000

    /** Deletes the event (every repeat of it). */
    fun delete(ctx: Context, eventId: Long): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0
        } catch (_: Exception) { false }.also { if (it) Prefs.get(ctx).setRaw("event_note_$eventId", null) }
    }

    // ─── Notes linked to events ──────────────────────────────────────────────

    fun linkedNote(ctx: Context, eventId: Long): Int? = Prefs.get(ctx).raw("event_note_$eventId")?.toIntOrNull()
    fun setLinkedNote(ctx: Context, eventId: Long, noteId: Int?) = Prefs.get(ctx).setRaw("event_note_$eventId", noteId?.toString())

    /** "11:00–12:00", "All day", or "from 19:30" for one that runs past midnight. */
    fun timeText(e: CalEvent): String {
        if (e.allDay) return "All day"
        val sameDay = Routines.dayOf(e.begin) == Routines.dayOf(e.end - 1)
        return if (sameDay) "${com.example.unutma.util.Fmt.time(e.begin)}–${com.example.unutma.util.Fmt.time(e.end)}"
        else "from ${com.example.unutma.util.Fmt.time(e.begin)}"
    }

    /** "in 4 h", "in 20 min", "now" for events today; null otherwise. */
    fun soonText(e: CalEvent, now: Long = System.currentTimeMillis()): String? {
        if (e.allDay || Routines.dayOf(e.begin) != Routines.dayOf(now)) return null
        val diff = e.begin - now
        return when {
            now in e.begin until e.end -> "now"
            diff <= 0 -> null
            diff < 60 * 60_000L -> "in ${(diff / 60_000).coerceAtLeast(1)} min"
            else -> "in ${diff / 3_600_000} h"
        }
    }

    /** A sensible start for a new event: the next full hour. */
    fun nextHour(): Long = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
