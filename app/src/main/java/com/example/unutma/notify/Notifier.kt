package com.example.unutma.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import com.example.unutma.MainActivity
import com.example.unutma.Note
import com.example.unutma.R
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.TextBlock
import com.example.unutma.data.stripMarkdown
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.plainText
import com.example.unutma.data.previewLine
import com.example.unutma.ui.theme.LABEL_COLOURS

object Notifier {
    const val CH_PIN = "pinned_notes"
    private const val CH_PIN_TOP = "pinned_notes_top"
    const val EXTRA_CHECK_INDEX = "check_index"
    private const val PIN_ROWS = 6   // rows in notif_pin_big.xml
    private const val TAG_REMINDER = "reminder"
    private const val TAG_PIN = "pin"
    const val EXTRA_NOTE_ID = "note_id"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * Android does not let an app change a channel's sound or vibration after creating it,
     * so each Sound/Vibration combination from Settings gets its own channel.
     */
    fun reminderChannel(ctx: Context): String {
        val s = Prefs.get(ctx).current
        val id = "reminders_s${if (s.sound) 1 else 0}_v${if (s.vibration) 1 else 0}"
        if (Build.VERSION.SDK_INT >= 26) {
            val m = nm(ctx)
            if (m.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(id, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders you set on notes"
                    if (!s.sound) setSound(null, null)
                    enableVibration(s.vibration)
                    if (s.vibration) vibrationPattern = longArrayOf(0, 250, 120, 250)
                }
                m.createNotificationChannel(ch)
            }
            m.notificationChannels.filter { it.id.startsWith("reminders_") && it.id != id }
                .forEach { m.deleteNotificationChannel(it.id) }
        }
        return id
    }

    /**
     * Normally pinned notes sit quietly in Android's "Silent" group, where they start collapsed.
     * With "Show pinned notes opened" on, they use a channel of normal importance (still no sound),
     * so they sit in the main list where Android opens up the top notification.
     */
    private fun pinChannel(ctx: Context): String {
        val top = Prefs.get(ctx).current.pinExpanded
        val id = if (top) CH_PIN_TOP else CH_PIN
        if (Build.VERSION.SDK_INT >= 26 && nm(ctx).getNotificationChannel(id) == null) {
            val ch = if (top) NotificationChannel(id, "Pinned notes (shown opened)", NotificationManager.IMPORTANCE_DEFAULT)
            else NotificationChannel(id, "Notes pinned to the notification bar", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            nm(ctx).createNotificationChannel(ch)
        }
        return id
    }

    fun openNoteIntent(ctx: Context, noteId: Int, requestOffset: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_NOTE
            putExtra(EXTRA_NOTE_ID, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, noteId * 10 + requestOffset, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcast(ctx: Context, cls: Class<*>, action: String, noteId: Int, requestOffset: Int): PendingIntent {
        val i = Intent(ctx, cls).apply { this.action = action; putExtra(EXTRA_NOTE_ID, noteId) }
        return PendingIntent.getBroadcast(
            ctx, noteId * 10 + requestOffset, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun labelColorInt(ctx: Context, note: Note, labelKey: String?): Int =
        (LABEL_COLOURS.firstOrNull { it.key == (labelKey ?: Prefs.get(ctx).current.accentKey) } ?: LABEL_COLOURS[0])
            .dark.toArgb()

    // ─── Reminders ───────────────────────────────────────────────────────────

    fun showReminder(ctx: Context, note: Note, labelKey: String?) {
        if (!canPost(ctx)) return
        val blocks = parseBlocks(note)
        val title = noteDisplayTitle(note, blocks)
        val text = when {
            note.isLocked -> "Locked note"
            note.nagMinutes > 0 -> "Reminding you every ${note.nagMinutes} min until you tap Done"
            else -> blocks.previewLine().ifBlank { "Reminder" }
        }
        val b = NotificationCompat.Builder(ctx, reminderChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_unutma)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (note.isLocked) text else blocks.plainText().ifBlank { text }))
            .setColor(labelColorInt(ctx, note, labelKey))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openNoteIntent(ctx, note.id, 1))
            .addAction(0, "Done", broadcast(ctx, AlarmReceiver::class.java, AlarmReceiver.ACTION_DONE, note.id, 2))
            .addAction(0, "Snooze 10 min", broadcast(ctx, AlarmReceiver::class.java, AlarmReceiver.ACTION_SNOOZE, note.id, 3))
        if (Build.VERSION.SDK_INT < 26) {
            val s = Prefs.get(ctx).current
            var defaults = 0
            if (s.sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (s.vibration) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            b.setDefaults(defaults)
        }
        try { NotificationManagerCompat.from(ctx).notify(TAG_REMINDER, note.id, b.build()) } catch (_: SecurityException) {}
    }

    fun cancelReminder(ctx: Context, noteId: Int) {
        NotificationManagerCompat.from(ctx).cancel(TAG_REMINDER, noteId)
    }

    // ─── Pinned to the notification bar ──────────────────────────────────────

    /** Ticks/unticks the [index]-th checkbox of a pinned note (handled by [PinReceiver]). */
    private fun togglePi(ctx: Context, noteId: Int, index: Int): PendingIntent {
        val i = Intent(ctx, PinReceiver::class.java).apply {
            action = PinReceiver.ACTION_TOGGLE
            data = Uri.parse("unutma://pin/$noteId/$index") // keeps each row's intent separate
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_CHECK_INDEX, index)
        }
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun struck(s: String): CharSequence =
        SpannableString(s).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun itemText(c: CheckBlock) = stripMarkdown(c.text).trim() + (if (c.qty != null) "  ×${c.qty}" else "")

    /** One checkbox row: the box in the label colour when ticked, the text struck through. Tapping it ticks/unticks. */
    private fun RemoteViews.checkRow(ctx: Context, row: Int, box: Int, text: Int, c: CheckBlock, index: Int, noteId: Int, color: Int) {
        setViewVisibility(row, View.VISIBLE)
        if (c.checked) {
            setImageViewResource(box, R.drawable.w_check_on)
            setInt(box, "setColorFilter", color)
            setTextViewText(text, struck(itemText(c)))
            setTextColor(text, DONE_GREY)
        } else {
            setImageViewResource(box, R.drawable.w_check_off)
            setInt(box, "setColorFilter", DONE_GREY)
            setTextViewText(text, itemText(c))
        }
        setViewPadding(row, if (c.indent > 0) (20 * ctx.resources.displayMetrics.density).toInt() else 0, 0, 0, 0)
        setContentDescription(row, (if (c.checked) "Done: " else "Not done: ") + itemText(c))
        setOnClickPendingIntent(row, togglePi(ctx, noteId, index))
    }

    private const val DONE_GREY = 0xFF8A8A8A.toInt()

    /** Posts or removes the ongoing notification for a note, depending on its pinnedToBar flag. */
    fun syncPin(ctx: Context, note: Note, labelKey: String? = null) {
        if (!note.pinnedToBar || note.isArchived) { cancelPin(ctx, note.id); return }
        if (!canPost(ctx)) return
        val blocks = parseBlocks(note)
        val title = noteDisplayTitle(note, blocks)
        val color = labelColorInt(ctx, note, labelKey)
        // Same numbering as Repo.toggleCheck: checkboxes that have text.
        val checks = blocks.filterIsInstance<CheckBlock>().filter { it.text.isNotBlank() }
        val text = blocks.filterIsInstance<TextBlock>().flatMap { it.text.lines() }
            .map { stripMarkdown(it).trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        val small = RemoteViews(ctx.packageName, R.layout.notif_pin_small)
        val big = RemoteViews(ctx.packageName, R.layout.notif_pin_big)
        for (v in listOf(small, big)) v.setTextViewText(R.id.pin_title, title)
        var summary: String

        if (note.isLocked) {
            summary = "Locked note"
            small.setViewVisibility(R.id.pin_line, View.VISIBLE); small.setTextViewText(R.id.pin_line, summary)
            big.setViewVisibility(R.id.pin_body, View.VISIBLE); big.setTextViewText(R.id.pin_body, "Locked note. Open Unutma to see it.")
        } else if (checks.isNotEmpty()) {
            val done = checks.count { it.checked }
            for (v in listOf(small, big)) {
                v.setViewVisibility(R.id.pin_count, View.VISIBLE)
                v.setTextViewText(R.id.pin_count, "$done/${checks.size}")
                v.setTextColor(R.id.pin_count, color)
            }
            // Collapsed: the next item to tick, or (all ticked) the items struck through.
            val next = checks.indexOfFirst { !it.checked }
            if (next >= 0) {
                small.checkRow(ctx, R.id.pin_next_row, R.id.pin_next_box, R.id.pin_next_text, checks[next], next, note.id, color)
                summary = checks.filter { !it.checked }.joinToString(" · ") { itemText(it) }
            } else {
                summary = checks.joinToString(" · ") { itemText(it) }
                small.setViewVisibility(R.id.pin_line, View.VISIBLE)
                small.setTextViewText(R.id.pin_line, struck(summary))
                small.setTextColor(R.id.pin_line, DONE_GREY)
            }
            // Expanded: any text (two lines), then the items.
            if (text.isNotEmpty()) {
                big.setViewVisibility(R.id.pin_body, View.VISIBLE)
                big.setTextViewText(R.id.pin_body, text)
                big.setInt(R.id.pin_body, "setMaxLines", 2)
            }
            val rows = listOf(
                Triple(R.id.pin_row_0, R.id.pin_box_0, R.id.pin_text_0), Triple(R.id.pin_row_1, R.id.pin_box_1, R.id.pin_text_1),
                Triple(R.id.pin_row_2, R.id.pin_box_2, R.id.pin_text_2), Triple(R.id.pin_row_3, R.id.pin_box_3, R.id.pin_text_3),
                Triple(R.id.pin_row_4, R.id.pin_box_4, R.id.pin_text_4), Triple(R.id.pin_row_5, R.id.pin_box_5, R.id.pin_text_5),
            )
            checks.take(PIN_ROWS).forEachIndexed { i, c ->
                val (row, box, t) = rows[i]
                big.checkRow(ctx, row, box, t, c, i, note.id, color)
            }
            if (checks.size > PIN_ROWS) {
                big.setViewVisibility(R.id.pin_more, View.VISIBLE)
                big.setTextViewText(R.id.pin_more, "+${checks.size - PIN_ROWS} more · tap to open")
            }
        } else {
            summary = text.lineSequence().firstOrNull().orEmpty().ifEmpty { blocks.previewLine() }
            small.setViewVisibility(R.id.pin_line, View.VISIBLE); small.setTextViewText(R.id.pin_line, summary)
            big.setViewVisibility(R.id.pin_body, View.VISIBLE); big.setTextViewText(R.id.pin_body, text.ifEmpty { summary })
        }

        val top = Prefs.get(ctx).current.pinExpanded
        val b = NotificationCompat.Builder(ctx, pinChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(summary)  // used where custom views aren't shown (watches, some lock screens)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setColor(color)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(if (top) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openNoteIntent(ctx, note.id, 4))
            // Android 14+ lets people swipe even ongoing notifications away; we put it straight back.
            .setDeleteIntent(broadcast(ctx, PinReceiver::class.java, PinReceiver.ACTION_DISMISSED, note.id, 5))
            .addAction(0, "Unpin", broadcast(ctx, PinReceiver::class.java, PinReceiver.ACTION_UNPIN, note.id, 6))
        try { NotificationManagerCompat.from(ctx).notify(TAG_PIN, note.id, b.build()) } catch (_: SecurityException) {}
    }

    fun cancelPin(ctx: Context, noteId: Int) {
        NotificationManagerCompat.from(ctx).cancel(TAG_PIN, noteId)
    }

    /** Re-posts every pinned note (after the "shown opened" setting changes). */
    suspend fun resyncPins(ctx: Context) {
        val labels = Repo.labels(ctx).all()
        for (n in Repo.notes(ctx).activeNow()) if (n.pinnedToBar) {
            cancelPin(ctx, n.id)  // moving to another channel needs a fresh post
            syncPin(ctx, n, labels.firstOrNull { it.id == n.labelId }?.color)
        }
    }
}
