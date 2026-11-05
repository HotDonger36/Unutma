package com.example.unutma.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.unutma.Note
import java.util.Calendar

/** Schedules the exact alarms behind reminders, nagging, expiring notes and the daily tidy-up. */
object Alarms {
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun pi(ctx: Context, action: String, noteId: Int, slot: Int, create: Boolean = true): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(Notifier.EXTRA_NOTE_ID, noteId)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, 1_000_000 + noteId * 10 + slot, i, flags)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun setExact(ctx: Context, at: Long, p: PendingIntent) {
        val m = am(ctx)
        try {
            if (canExact(ctx)) m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** Keeps a note's alarms in line with its reminder and expiry times. */
    fun syncNote(ctx: Context, note: Note) {
        val now = System.currentTimeMillis()
        val remind = note.reminderAt
        if (!note.isArchived && remind != null && remind > now) {
            setExact(ctx, remind, pi(ctx, AlarmReceiver.ACTION_REMIND, note.id, 1)!!)
        } else cancel(ctx, AlarmReceiver.ACTION_REMIND, note.id, 1)

        val exp = note.expiresAt
        if (!note.isArchived && exp != null && exp > now) {
            setExact(ctx, exp, pi(ctx, AlarmReceiver.ACTION_EXPIRE, note.id, 2)!!)
        } else cancel(ctx, AlarmReceiver.ACTION_EXPIRE, note.id, 2)
    }

    fun cancelNote(ctx: Context, noteId: Int) {
        cancel(ctx, AlarmReceiver.ACTION_REMIND, noteId, 1)
        cancel(ctx, AlarmReceiver.ACTION_EXPIRE, noteId, 2)
    }

    private fun cancel(ctx: Context, action: String, noteId: Int, slot: Int) {
        pi(ctx, action, noteId, slot, create = false)?.let { am(ctx).cancel(it); it.cancel() }
    }

    /** Shortly after midnight: purge the archive, archive expired notes, reset checklists, refresh widgets. */
    fun scheduleMaintenance(ctx: Context) {
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 2)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val p = pi(ctx, AlarmReceiver.ACTION_MAINTENANCE, 0, 9)!!
        am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, p)
    }
}
