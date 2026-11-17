package com.example.unutma.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.unutma.MainActivity
import com.example.unutma.R
import com.example.unutma.data.Cal
import com.example.unutma.data.CalEvent
import com.example.unutma.data.Repo
import com.example.unutma.data.Routines
import com.example.unutma.util.Fmt
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Calendar widgets: Agenda, Next event and This week. They read the phone's calendar
 * (once Unutma has been allowed to), so they show "Connect your calendar" until then.
 */
abstract class CalendarWidgetBase : AppWidgetProvider() {
    abstract fun build(ctx: Context, widgetId: Int): RemoteViews

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Repo.scope.launch {
            try { renderAll(context.applicationContext, mgr, ids) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        onUpdate(context, mgr, intArrayOf(id))

    open fun afterRender(ctx: Context, mgr: AppWidgetManager, id: Int) {}

    fun renderAll(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) try { mgr.updateAppWidget(id, build(ctx, id)); afterRender(ctx, mgr, id) } catch (_: Exception) {}
    }

    companion object {
        val providers: List<Class<out CalendarWidgetBase>> =
            listOf(CalAgendaWidget::class.java, CalNextWidget::class.java, CalWeekWidget::class.java)

        fun refresh(ctx: Context, mgr: AppWidgetManager) {
            for (cls in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) cls.getDeclaredConstructor().newInstance().renderAll(ctx, mgr, ids)
            }
        }

        fun color(e: CalEvent) = e.color or 0xFF000000.toInt()

        /** The ring used for events (notes use a filled dot). */
        fun ring(ctx: Context, color: Int): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val px = (8 * d).toInt().coerceAtLeast(4)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; this.color = color }
            Canvas(bmp).drawCircle(px / 2f, px / 2f, px / 2f - d, p)
            return bmp
        }

        fun openCalendar(ctx: Context, request: Int) = Widgets.openApp(ctx, MainActivity.ACTION_OPEN_CALENDAR, request)
        fun newEvent(ctx: Context, request: Int) = Widgets.openApp(ctx, MainActivity.ACTION_NEW_EVENT, request)
        const val CONNECT = "Open Unutma › Calendar to connect your calendar"
    }
}

/** Today's date with a + button, and a scrolling list of every event in the coming 30 days (with day and month headings). */
class CalAgendaWidget : CalendarWidgetBase() {
    companion object {
        const val DAYS_AHEAD = 30

        /** "TOMORROW", or "WED 18 NOV" so the month is always there. */
        fun dayHeading(day: Long, today: Long): String =
            if (day == today + 1) "TOMORROW"
            else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(Routines.startOf(day))).uppercase()
    }

    override fun build(ctx: Context, widgetId: Int): RemoteViews {
        val today = Routines.today()
        val ms = Routines.startOf(today)
        val accent = Widgets.accent(ctx)
        return RemoteViews(ctx.packageName, R.layout.widget_cal_agenda).apply {
            text(R.id.w_ca_dow, SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms)).uppercase())
            setTextColor(R.id.w_ca_dow, accent)
            text(R.id.w_ca_day, SimpleDateFormat("d", Locale.getDefault()).format(Date(ms)))
            text(R.id.w_ca_month, SimpleDateFormat("MMM", Locale.getDefault()).format(Date(ms)).uppercase())
            setOnClickPendingIntent(R.id.w_ca_root, openCalendar(ctx, 80_000 + widgetId))
            setOnClickPendingIntent(R.id.w_ca_new, newEvent(ctx, 81_000 + widgetId))
            text(R.id.w_ca_empty,
                if (Cal.canRead(ctx)) "Nothing in the next $DAYS_AHEAD days" else CONNECT)
            val svc = Intent(ctx, CalAgendaService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("unutma://cal-agenda/$widgetId")
            }
            @Suppress("DEPRECATION")
            setRemoteAdapter(R.id.w_ca_list, svc)
            setEmptyView(R.id.w_ca_list, R.id.w_ca_empty)
            // Tapping an event opens the calendar, the same as tapping anywhere else on the widget.
            val open = Intent(ctx, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CALENDAR
                data = Uri.parse("unutma://cal-agenda-open/$widgetId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            setPendingIntentTemplate(R.id.w_ca_list,
                PendingIntent.getActivity(ctx, 84_000 + widgetId, open, PendingIntent.FLAG_UPDATE_CURRENT or mutable))
        }
    }

    override fun afterRender(ctx: Context, mgr: AppWidgetManager, id: Int) =
        mgr.notifyAppWidgetViewDataChanged(id, R.id.w_ca_list)
}

/** The scrolling event rows for [CalAgendaWidget]. */
class CalAgendaService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var events: List<CalEvent> = emptyList()
        private var today = Routines.today()

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = events.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = false
        override fun getItemId(position: Int) = position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun onDataSetChanged() {
            today = Routines.today()
            val now = System.currentTimeMillis()
            events = if (!Cal.canRead(ctx)) emptyList()
            else Cal.events(ctx, now, Routines.startOf(today + CalAgendaWidget.DAYS_AHEAD)).filter { it.end > now }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val e = events.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_cal_agenda_item)
            val day = Routines.dayOf(e.begin).coerceAtLeast(today)
            val prev = events.getOrNull(position - 1)?.let { Routines.dayOf(it.begin).coerceAtLeast(today) } ?: today
            return RemoteViews(ctx.packageName, R.layout.widget_cal_agenda_item).apply {
                if (day != prev) {
                    setViewVisibility(R.id.w_cai_head, View.VISIBLE)
                    text(R.id.w_cai_head, CalAgendaWidget.dayHeading(day, today))
                } else setViewVisibility(R.id.w_cai_head, View.GONE)
                text(R.id.w_cai_time, if (e.allDay) "all day" else Fmt.time(e.begin))
                if (day == today && Cal.soonText(e) != null) setTextColor(R.id.w_cai_time, CalendarWidgetBase.color(e))
                setImageViewBitmap(R.id.w_cai_dot, CalendarWidgetBase.ring(ctx, CalendarWidgetBase.color(e)))
                text(R.id.w_cai_title, e.title)
                setOnClickFillInIntent(R.id.w_cai_root, Intent())
            }
        }
    }
}

/** How long until the next event, and what it is. */
class CalNextWidget : CalendarWidgetBase() {
    override fun build(ctx: Context, widgetId: Int): RemoteViews {
        val now = System.currentTimeMillis()
        return RemoteViews(ctx.packageName, R.layout.widget_cal_next).apply {
            setOnClickPendingIntent(R.id.w_cn_root, openCalendar(ctx, 82_000 + widgetId))
            if (!Cal.canRead(ctx)) {
                text(R.id.w_cn_title, "Connect your calendar"); text(R.id.w_cn_time, "in Unutma › Calendar")
                return@apply
            }
            val e = Cal.events(ctx, now, now + 60L * 24 * 60 * 60 * 1000).firstOrNull { !it.allDay && it.begin > now }
                ?: Cal.events(ctx, now, now + 60L * 24 * 60 * 60 * 1000).firstOrNull { it.end > now }
            if (e == null) {
                text(R.id.w_cn_title, "Nothing coming up"); return@apply
            }
            val diff = e.begin - now
            val (n, unit) = when {
                diff <= 0 -> "now" to ""
                diff < 60 * 60_000L -> "${(diff / 60_000).coerceAtLeast(1)}" to "min"
                diff < 48 * 60 * 60_000L -> "${diff / 3_600_000}" to (if (diff / 3_600_000 == 1L) "hour" else "hours")
                else -> "${diff / (24 * 3_600_000)}" to "days"
            }
            val c = color(e)
            text(R.id.w_cn_number, n); setTextColor(R.id.w_cn_number, c)
            text(R.id.w_cn_unit, unit); setTextColor(R.id.w_cn_unit, c)
            text(R.id.w_cn_title, e.title)
            val day = Routines.dayOf(e.begin)
            val today = Routines.today()
            val dayText = when (day) {
                today -> ""
                today + 1 -> "Tomorrow "
                else -> SimpleDateFormat("EEE d MMM ", Locale.getDefault()).format(Date(e.begin))
            }
            text(R.id.w_cn_time, dayText + Cal.timeText(e))
        }
    }
}

/** Monday to Sunday: a little stack of blocks for each day's events; today in the accent colour. */
class CalWeekWidget : CalendarWidgetBase() {
    override fun build(ctx: Context, widgetId: Int): RemoteViews {
        val accent = Widgets.accent(ctx)
        val today = Routines.today()
        val monday = Routines.mondayOf(today)
        return RemoteViews(ctx.packageName, R.layout.widget_cal_week).apply {
            setOnClickPendingIntent(R.id.w_cw_root, openCalendar(ctx, 83_000 + widgetId))
            if (!Cal.canRead(ctx)) {
                setViewVisibility(R.id.w_cw_days, View.GONE); setViewVisibility(R.id.w_cw_empty, View.VISIBLE)
                text(R.id.w_cw_empty, CONNECT)
                return@apply
            }
            val perDay = Cal.events(ctx, Routines.startOf(monday), Routines.startOf(monday + 7)).groupingBy { Routines.dayOf(it.begin) }.eachCount()
            text(R.id.w_cw_count, perDay.values.sum().toString())
            val bars = listOf(R.id.w_cw_bar0, R.id.w_cw_bar1, R.id.w_cw_bar2, R.id.w_cw_bar3, R.id.w_cw_bar4, R.id.w_cw_bar5, R.id.w_cw_bar6)
            val letters = listOf(R.id.w_cw_letter0, R.id.w_cw_letter1, R.id.w_cw_letter2, R.id.w_cw_letter3, R.id.w_cw_letter4, R.id.w_cw_letter5, R.id.w_cw_letter6)
            for (i in 0..6) {
                val d = monday + i
                setImageViewBitmap(bars[i], stack(ctx, perDay[d] ?: 0, if (d == today) accent else dim(accent), d < today))
                setContentDescription(bars[i], "${Routines.DAY_NAMES[i]}: ${perDay[d] ?: 0} events")
                if (d == today) setTextColor(letters[i], accent)
            }
        }
    }

    private fun dim(c: Int): Int {
        // The accent at about 40 % on the dark background.
        val r = (c shr 16 and 0xFF) * 4 / 10; val g = (c shr 8 and 0xFF) * 4 / 10; val b = (c and 0xFF) * 4 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Up to three blocks from the bottom (one per event, "3" meaning three or more); an outline when there are none. */
    private fun stack(ctx: Context, count: Int, color: Int, past: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (20 * d).toInt(); val h = (58 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val block = 16 * d; val gap = 3 * d; val r = 4 * d
        if (count == 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Widgets.TRACK
            c.drawRoundRect(RectF(d / 2, h - block + d / 2, w - d / 2, h - d / 2), r, r, p)
        } else for (i in 0 until count.coerceAtMost(3)) {
            p.color = color; if (past) p.alpha = 140
            val bottom = h - i * (block + gap)
            c.drawRoundRect(RectF(0f, bottom - block, w.toFloat(), bottom), r, r, p)
        }
        return bmp
    }
}
