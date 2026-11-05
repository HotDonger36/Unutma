package com.example.unutma.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import com.example.unutma.Label
import com.example.unutma.MainActivity
import com.example.unutma.Note
import com.example.unutma.R
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.checkProgress
import com.example.unutma.data.firstUnchecked
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.previewLine
import com.example.unutma.data.stripMarkdown
import com.example.unutma.notify.Notifier
import com.example.unutma.ui.theme.swatch
import com.example.unutma.util.Fmt
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Shared helpers for all Unutma home screen widgets. */
object Widgets {
    const val ACTION_TOGGLE = "com.example.unutma.widget.TOGGLE"
    const val EXTRA_CHECK_INDEX = "check_index"

    val noteProviders: List<Class<out NoteWidgetBase>> = listOf(
        CountdownWidget::class.java, ListSmallWidget::class.java, ListWidget::class.java, NoteWidget::class.java,
        DailyListWidget::class.java, RingListWidget::class.java, NextUpWidget::class.java, StreakWidget::class.java,
    )

    /** Redraws every widget. Called after any change to a note. */
    fun refreshAll(ctx: Context) {
        val app = ctx.applicationContext
        Repo.scope.launch {
            try {
                val mgr = AppWidgetManager.getInstance(app)
                QuickCaptureWidget.render(app, mgr, mgr.getAppWidgetIds(ComponentName(app, QuickCaptureWidget::class.java)))
                NotesListWidget.refresh(app, mgr)
                CalendarWidgetBase.refresh(app, mgr)
                for (cls in noteProviders) {
                    val ids = mgr.getAppWidgetIds(ComponentName(app, cls))
                    if (ids.isEmpty()) continue
                    val provider = cls.getDeclaredConstructor().newInstance()
                    provider.renderAll(app, mgr, ids)
                }
            } catch (_: Exception) {}
        }
    }

    fun labelColor(ctx: Context, label: Label?): Int =
        swatch(label?.color ?: Prefs.get(ctx).current.accentKey).dark.toArgb()

    fun accent(ctx: Context): Int = swatch(Prefs.get(ctx).current.accentKey).dark.toArgb()

    /** Applies the widget's text size (its own, or the default from Settings): layout size × scale. */
    fun scaleText(ctx: Context, widgetId: Int, v: RemoteViews, vararg sizes: Pair<Int, Float>) {
        val scale = Prefs.get(ctx).widgetTextSize(widgetId).scale
        if (scale == 1f) return
        for ((id, sp) in sizes) v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * scale)
    }

    const val MUTED = 0xFF8A8A8A.toInt()
    const val TEXT = 0xFFEDEDED.toInt()
    const val DONE = 0xFF6B6B6B.toInt()
    const val TRACK = 0xFF333333.toInt()

    /** The checklist progress ring as a bitmap (widgets cannot draw arcs themselves). */
    fun ring(ctx: Context, done: Int, total: Int, color: Int, sizeDp: Int, strokeDp: Float): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = strokeDp * d
        val inset = sw / 2f + px * 0.07f
        val r = RectF(inset, inset, px - inset, px - inset)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; this.color = TRACK }
        if (total > 0 && done >= total) {
            // All done: a solid circle in the label (or accent) colour, same as in the app.
            p.style = Paint.Style.FILL; p.color = color
            c.drawCircle(px / 2f, px / 2f, (px - inset * 2) / 2f + sw / 2f, p)
            return bmp
        }
        c.drawArc(r, 0f, 360f, false, p)
        if (total > 0 && done > 0) {
            p.color = color; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, -90f, 360f * done / total, false, p)
        }
        return bmp
    }

    fun openApp(ctx: Context, action: String, request: Int, noteId: Int? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            this.action = action
            if (noteId != null) putExtra(Notifier.EXTRA_NOTE_ID, noteId)
            // Unique per widget, so Android never merges two widgets' intents (which made a tap open the wrong place).
            data = Uri.parse("unutma://open/$request/${noteId ?: 0}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun configure(ctx: Context, widgetId: Int, provider: Class<*>): PendingIntent {
        val i = Intent(ctx, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(WidgetConfigActivity.EXTRA_PROVIDER, provider.name)
            data = Uri.parse("unutma://widget/$widgetId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(ctx, 50_000 + widgetId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

// ─── Quick capture bar ───────────────────────────────────────────────────────

class QuickCaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = render(context, mgr, ids)

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
                v.setOnClickPendingIntent(R.id.w_quick_name, Widgets.openApp(ctx, Intent.ACTION_MAIN, 40_001))
                v.setOnClickPendingIntent(R.id.w_quick_note, Widgets.openApp(ctx, MainActivity.ACTION_NEW_NOTE, 40_002))
                v.setOnClickPendingIntent(R.id.w_quick_list, Widgets.openApp(ctx, MainActivity.ACTION_NEW_LIST, 40_003))
                v.setOnClickPendingIntent(R.id.w_quick_mic, Widgets.openApp(ctx, MainActivity.ACTION_VOICE, 40_004))
                v.setInt(R.id.w_quick_mic, "setColorFilter", Widgets.accent(ctx))
                mgr.updateAppWidget(id, v)
            }
        }
    }
}

// ─── Widgets that show one chosen note ───────────────────────────────────────

abstract class NoteWidgetBase : AppWidgetProvider() {
    /** Which notes can be chosen for this widget in the picker. */
    abstract fun accepts(note: Note): Boolean
    abstract fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews
    open val pickerTitle = "Choose a note"

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Repo.scope.launch {
            try { renderAll(context.applicationContext, mgr, ids) } finally { pending.finish() }
        }
    }

    /** Ticking a checkbox straight from a widget (the checklist widgets send this). */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_TOGGLE) {
            val noteId = intent.getIntExtra(Notifier.EXTRA_NOTE_ID, -1)
            val index = intent.getIntExtra(Widgets.EXTRA_CHECK_INDEX, -1)
            if (noteId > 0 && index >= 0) {
                val pending = goAsync()
                Repo.scope.launch {
                    try { Repo.toggleCheck(context.applicationContext, noteId, index) } finally { pending.finish() }
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val p = Prefs.get(context)
        ids.forEach { p.clearWidget(it) }
    }

    /** Resizing a widget redraws it, so the content is fitted to the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        onUpdate(context, mgr, intArrayOf(id))
    }

    /**
     * The widget's height in dp (portrait), divided by its text scale, while [build] runs — so
     * builders can drop rows or lines that wouldn't fit instead of letting text spill out.
     */
    protected var roomDp: Int = 0

    /** How many rows of [rowDp] fit after [usedDp] of fixed content. */
    protected fun rowsThatFit(usedDp: Int, rowDp: Int, max: Int): Int =
        if (roomDp <= 0) max else ((roomDp - usedDp) / rowDp).coerceIn(0, max)

    suspend fun renderAll(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val labels = Repo.labels(ctx).all()
        val p = Prefs.get(ctx)
        for (id in ids) {
            val opts = mgr.getAppWidgetOptions(id)
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
                ?: opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            roomDp = if (h <= 0) 0 else (h / p.widgetTextSize(id).scale).toInt()
            val noteId = p.widgetNote(id)
            val note = if (noteId > 0) Repo.notes(ctx).get(noteId) else null
            val views = if (note == null || note.isArchived) empty(ctx, id)
            else build(ctx, id, Repo.resetIfDue(note) ?: note, labels.firstOrNull { it.id == note.labelId })
            mgr.updateAppWidget(id, views)
            if (this is ListWidget) mgr.notifyAppWidgetViewDataChanged(id, R.id.w_list_items)
        }
    }

    private fun empty(ctx: Context, id: Int): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_empty).apply {
            setOnClickPendingIntent(R.id.w_empty_root, Widgets.configure(ctx, id, this@NoteWidgetBase.javaClass))
        }
}

/** Small countdown: big number of days until a countdown (or reminder). */
class CountdownWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a countdown"
    override fun accepts(note: Note) = note.countdownAt != null || note.reminderAt != null || note.expiresAt != null

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val target = note.countdownAt ?: note.reminderAt ?: note.expiresAt
        val now = System.currentTimeMillis()
        val diff = if (target == null) 0L else target - now
        // Days, or hours/minutes once it is less than a day away.
        val (number, unit) = when {
            target == null || diff <= 0 -> 0L to "days"
            diff < 60 * 60 * 1000L -> (diff / 60_000).coerceAtLeast(1) to "min"
            diff < 24 * 60 * 60 * 1000L -> (diff / 3_600_000) to (if (diff / 3_600_000 == 1L) "hour" else "hours")
            else -> Fmt.daysBetween(now, target).let { it to (if (it == 1L) "day" else "days") }
        }
        return RemoteViews(ctx.packageName, R.layout.widget_countdown).apply {
            setTextViewText(R.id.w_cd_label, (label?.name ?: "Countdown").uppercase())
            setTextColor(R.id.w_cd_label, color)
            setTextViewText(R.id.w_cd_number, number.toString())
            setTextColor(R.id.w_cd_number, color)
            setTextViewText(R.id.w_cd_unit, unit)
            setTextColor(R.id.w_cd_unit, color)
            setTextViewText(R.id.w_cd_title, noteDisplayTitle(note))
            setTextViewText(R.id.w_cd_date, if (target == null) "" else Fmt.dateTime(target))
            setOnClickPendingIntent(R.id.w_cd_root, Widgets.openApp(ctx, MainActivity.ACTION_OPEN_NOTE, 60_000 + widgetId, note.id))
            Widgets.scaleText(ctx, widgetId, this, R.id.w_cd_label to 11f, R.id.w_cd_number to 56f, R.id.w_cd_unit to 13f, R.id.w_cd_title to 16f, R.id.w_cd_date to 12f)
        }
    }
}

/**
 * Small list: uses the fuller "Daily checklist" design (SL-1 in the widget designs) — title and
 * progress, items you can tick, and when it resets — instead of the old mostly-empty layout.
 */
class ListSmallWidget : DailyListWidget()

/** Full checklist you can tick straight from the home screen. */
class ListWidget : NoteWidgetBase() {
    override val pickerTitle = "Choose a checklist"
    override fun accepts(note: Note) = parseBlocks(note).any { it is CheckBlock }

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val blocks = parseBlocks(note)
        val (done, total) = blocks.checkProgress() ?: (0 to 0)
        return RemoteViews(ctx.packageName, R.layout.widget_list).apply {
            setTextViewText(R.id.w_l_label, (label?.name ?: "List").uppercase())
            setTextColor(R.id.w_l_label, color)
            setTextViewText(R.id.w_l_title, noteDisplayTitle(note, blocks))
            setImageViewBitmap(R.id.w_l_ring, Widgets.ring(ctx, done, total, color, 16, 2f))
            setTextViewText(R.id.w_l_count, "$done/$total")
            setInt(R.id.w_l_rule, "setBackgroundColor", color)
            setOnClickPendingIntent(R.id.w_l_header, Widgets.openApp(ctx, MainActivity.ACTION_OPEN_NOTE, 62_000 + widgetId, note.id))
            Widgets.scaleText(ctx, widgetId, this, R.id.w_l_label to 11f, R.id.w_l_title to 17f, R.id.w_l_count to 12f, R.id.w_l_locked to 12f)

            if (note.isLocked) {
                setViewVisibility(R.id.w_list_items, View.GONE)
                setViewVisibility(R.id.w_l_locked, View.VISIBLE)
            } else {
                setViewVisibility(R.id.w_list_items, View.VISIBLE)
                setViewVisibility(R.id.w_l_locked, View.GONE)
                val svc = Intent(ctx, ListWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse("unutma://list/$widgetId")
                }
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.w_list_items, svc)
                val toggle = Intent(ctx, ListWidget::class.java).apply {
                    action = Widgets.ACTION_TOGGLE
                    putExtra(Notifier.EXTRA_NOTE_ID, note.id)
                }
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                setPendingIntentTemplate(
                    R.id.w_list_items,
                    PendingIntent.getBroadcast(ctx, 63_000 + widgetId, toggle, PendingIntent.FLAG_UPDATE_CURRENT or mutable),
                )
            }
        }
    }

}

/** Rows for [ListWidget]. */
class ListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(val ctx: Context, val widgetId: Int) : RemoteViewsFactory {
        private var items: List<CheckBlock> = emptyList()
        private var color: Int = Widgets.accent(ctx)

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = false
        override fun getItemId(position: Int) = position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun onDataSetChanged() {
            runBlocking {
                val noteId = Prefs.get(ctx).widgetNote(widgetId)
                val note = if (noteId > 0) Repo.notes(ctx).get(noteId) else null
                items = if (note == null) emptyList()
                else parseBlocks(note).filterIsInstance<CheckBlock>().filter { it.text.isNotBlank() }
                val label = note?.labelId?.let { id -> Repo.labels(ctx).all().firstOrNull { it.id == id } }
                color = Widgets.labelColor(ctx, label)
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val item = items.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_list_item)
            return RemoteViews(ctx.packageName, R.layout.widget_list_item).apply {
                val text = stripMarkdown(item.text) + (if (item.qty != null) "  ×${item.qty}" else "")
                if (item.checked) {
                    val s = SpannableString(text)
                    s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setTextViewText(R.id.w_li_text, s)
                    setTextColor(R.id.w_li_text, Widgets.DONE)
                    setImageViewResource(R.id.w_li_box, R.drawable.w_check_on)
                    setInt(R.id.w_li_box, "setColorFilter", color)
                } else {
                    setTextViewText(R.id.w_li_text, text)
                    setTextColor(R.id.w_li_text, Widgets.TEXT)
                    setImageViewResource(R.id.w_li_box, R.drawable.w_check_off)
                    setInt(R.id.w_li_box, "setColorFilter", Widgets.MUTED)
                }
                setViewPadding(R.id.w_li_root, if (item.indent > 0) (24 * ctx.resources.displayMetrics.density).toInt() else 0, 0, 0, 0)
                setContentDescription(R.id.w_li_root, (if (item.checked) "Done: " else "Not done: ") + text)
                setOnClickFillInIntent(R.id.w_li_root, Intent().putExtra(Widgets.EXTRA_CHECK_INDEX, position))
                Widgets.scaleText(ctx, widgetId, this, R.id.w_li_text to 15f)
            }
        }
    }
}

/** Text note: label, reminder, title and three lines of text. */
class NoteWidget : NoteWidgetBase() {
    override fun accepts(note: Note) = true

    override fun build(ctx: Context, widgetId: Int, note: Note, label: Label?): RemoteViews {
        val color = Widgets.labelColor(ctx, label)
        val blocks = parseBlocks(note)
        val remind = note.reminderAt
        val body = if (note.isLocked) "Locked note" else
            blocks.filterIsInstance<com.example.unutma.data.TextBlock>().joinToString("\n") { stripMarkdown(it.text) }.trim()
                .ifBlank { blocks.previewLine() }
        return RemoteViews(ctx.packageName, R.layout.widget_note).apply {
            setTextViewText(R.id.w_n_label, (label?.name ?: "Note").uppercase())
            setTextColor(R.id.w_n_label, color)
            setInt(R.id.w_n_dot, "setColorFilter", color)
            if (remind != null) {
                setViewVisibility(R.id.w_n_remind, View.VISIBLE)
                setTextViewText(R.id.w_n_remind_text, Fmt.reminderShort(remind))
                setTextColor(R.id.w_n_remind_text, color)
                setInt(R.id.w_n_bell, "setColorFilter", color)
            } else setViewVisibility(R.id.w_n_remind, View.GONE)
            setTextViewText(R.id.w_n_title, noteDisplayTitle(note, blocks))
            setTextViewText(R.id.w_n_body, body)
            // As many lines as fit under the label and title (about 20 dp each), so nothing spills out.
            val lines = rowsThatFit(usedDp = 92, rowDp = 20, max = 14)
            setViewVisibility(R.id.w_n_body, if (lines == 0) View.GONE else View.VISIBLE)
            setInt(R.id.w_n_body, "setMaxLines", lines.coerceAtLeast(1))
            setOnClickPendingIntent(R.id.w_n_root, Widgets.openApp(ctx, MainActivity.ACTION_OPEN_NOTE, 64_000 + widgetId, note.id))
            Widgets.scaleText(ctx, widgetId, this, R.id.w_n_label to 11f, R.id.w_n_remind_text to 12f, R.id.w_n_title to 17f, R.id.w_n_body to 14f)
        }
    }
}
