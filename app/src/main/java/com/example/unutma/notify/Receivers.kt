package com.example.unutma.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.unutma.data.Repo
import kotlinx.coroutines.launch

/** Runs [block] off the main thread and keeps the receiver alive until it finishes. */
private fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    Repo.scope.launch {
        try { block() } catch (_: Exception) {} finally { pending.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMIND = "com.example.unutma.REMIND"
        const val ACTION_EXPIRE = "com.example.unutma.EXPIRE"
        const val ACTION_MAINTENANCE = "com.example.unutma.MAINTENANCE"
        const val ACTION_DONE = "com.example.unutma.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.example.unutma.REMINDER_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(Notifier.EXTRA_NOTE_ID, -1)
        when (intent.action) {
            ACTION_REMIND -> work { fireReminder(ctx, id) }
            ACTION_EXPIRE -> work { Repo.maintenance(ctx) }
            ACTION_MAINTENANCE -> work { Repo.maintenance(ctx) }
            ACTION_DONE -> work { Repo.reminderDone(ctx, id) }
            ACTION_SNOOZE -> work { Repo.snooze(ctx, id, 10) }
        }
    }

    private suspend fun fireReminder(ctx: Context, id: Int) {
        val note = Repo.notes(ctx).get(id) ?: return
        val at = note.reminderAt ?: return
        if (note.isArchived) return
        val now = System.currentTimeMillis()
        if (at > now + 60_000) { Alarms.syncNote(ctx, note); return } // an old alarm; the real one is later
        val label = note.labelId?.let { lid -> Repo.labels(ctx).all().firstOrNull { it.id == lid } }
        Notifier.showReminder(ctx, note, label?.color)
        // Nagging reminders come back every N minutes until "Done" is tapped.
        val next = if (note.nagMinutes > 0) now + note.nagMinutes * 60_000L else null
        Repo.save(ctx, note.copy(reminderAt = next), touch = false)
    }
}

/** Keeps "pinned to the notification bar" notes in the bar. */
class PinReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISSED = "com.example.unutma.PIN_DISMISSED"
        const val ACTION_UNPIN = "com.example.unutma.UNPIN"
        const val ACTION_TOGGLE = "com.example.unutma.PIN_TOGGLE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(Notifier.EXTRA_NOTE_ID, -1)
        when (intent.action) {
            ACTION_DISMISSED -> work {
                val n = Repo.notes(ctx).get(id) ?: return@work
                if (n.pinnedToBar && !n.isArchived) {
                    val label = n.labelId?.let { lid -> Repo.labels(ctx).all().firstOrNull { it.id == lid } }
                    Notifier.syncPin(ctx, n, label?.color)
                }
            }
            ACTION_UNPIN -> work {
                val n = Repo.notes(ctx).get(id) ?: return@work
                Repo.save(ctx, n.copy(pinnedToBar = false), touch = false)
            }
            // A checkbox tapped in the notification. Saving re-posts the notification with the new tick.
            ACTION_TOGGLE -> work {
                val index = intent.getIntExtra(Notifier.EXTRA_CHECK_INDEX, -1)
                if (id > 0 && index >= 0) Repo.toggleCheck(ctx, id, index)
            }
        }
    }
}

/** After a restart (or an app update) alarms and pinned notifications have to be put back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> work { restoreAll(ctx) }
        }
    }

    companion object {
        suspend fun restoreAll(ctx: Context) {
            Repo.maintenance(ctx)
            CalendarWatch.schedule(ctx)
            val labels = Repo.labels(ctx).all()
            val now = System.currentTimeMillis()
            for (n in Repo.notes(ctx).activeNow()) {
                val at = n.reminderAt
                if (at != null && at <= now) {
                    // Missed while the phone was off: remind now.
                    Notifier.showReminder(ctx, n, labels.firstOrNull { it.id == n.labelId }?.color)
                    val next = if (n.nagMinutes > 0) now + n.nagMinutes * 60_000L else null
                    Repo.save(ctx, n.copy(reminderAt = next), touch = false)
                } else {
                    Alarms.syncNote(ctx, n)
                }
                if (n.pinnedToBar) Notifier.syncPin(ctx, n, labels.firstOrNull { it.id == n.labelId }?.color)
            }
        }
    }
}
