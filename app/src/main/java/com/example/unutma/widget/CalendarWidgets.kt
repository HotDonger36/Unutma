package com.example.unutma.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
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

    fun renderAll(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) try { mgr.updateAppWidget(id, build(ctx, id)) } catch (_: Exception) {}
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

/** Today's date with a + button, and the next few events (with "TOMORROW" and day headings). */
class CalAgendaWidget : CalendarWidgetBase() {
    override fun build(ctx: Context, widgetId: Int): RemoteViews {
        val today = Routines.today()
        val now = System.currentTimeMillis()
        val ms = Routines.startOf(today)
        val accent = Widgets.accent(ctx)
        return RemoteViews(ctx.packageName, R.layout.widget_cal_agenda).apply {
            setTextViewText(R.id.w_ca_dow, SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms)).uppercase())
            setTextColor(R.id.w_ca_dow, accent)
            setTextViewText(R.id.w_ca_day, SimpleDateFormat("d", Locale.getDefault()).format(Date(ms)))
            setTextViewText(R.id.w_ca_month, SimpleDateFormat("MMM", Locale.getDefault()).format(Date(ms)).uppercase())
            setOnClickPendingIntent(R.id.w_ca_root, openCalendar(ctx, 80_000 + widgetId))
            setOnClickPendingIntent(R.id.w_ca_new, newEvent(ctx, 81_000 + widgetId))
            if (!Cal.canRead(ctx)) {
                setViewVisibility(R.id.w_ca_list, View.GONE); setViewVisibility(R.id.w_ca_empty, View.VISIBLE)
                setTextViewText(R.id.w_ca_empty, CONNECT)
                return@apply
            }
            val events = Cal.events(ctx, now, Routines.startOf(today + 7)).filter { it.end > now }
            if (events.isEmpty()) {
                setViewVisibility(R.id.w_ca_list, View.GONE); setViewVisibility(R.id.w_ca_empty, View.VISIBLE)
                setTextViewText(R.id.w_ca_empty, "Nothing this week")
                return@apply
            }
            val heads = listOf(R.id.w_ca_head0, R.id.w_ca_head1, R.id.w_ca_head2, R.id.w_ca_head3, R.id.w_ca_head4)
            val rows = listOf(R.id.w_ca_row0, R.id.w_ca_row1, R.id.w_ca_row2, R.id.w_ca_row3, R.id.w_ca_row4)
            val times = listOf(R.id.w_ca_time0, R.id.w_ca_time1, R.id.w_ca_time2, R.id.w_ca_time3, R.id.w_ca_time4)
            val dots = listOf(R.id.w_ca_dot0, R.id.w_ca_dot1, R.id.w_ca_dot2, R.id.w_ca_dot3, R.id.w_ca_dot4)
            val titles = listOf(R.id.w_ca_title0, R.id.w_ca_title1, R.id.w_ca_title2, R.id.w_ca_title3, R.id.w_ca_title4)
            var lastDay = today
            for ((i, e) in events.take(5).withIndex()) {
                val day = Routines.dayOf(e.begin).coerceAtLeast(today)
                if (day != lastDay) {
                    setViewVisibility(heads[i], View.VISIBLE)
                    setTextViewText(heads[i], if (day == today + 1) "TOMORROW"
                    else SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(Routines.startOf(day))).uppercase())
                    lastDay = day
                }
                setViewVisibility(rows[i], View.VISIBLE)
                setTextViewText(times[i], if (e.allDay) "all day" else Fmt.time(e.begin))
                if (day == today && (Cal.soonText(e) != null)) setTextColor(times[i], color(e))
                setImageViewBitmap(dots[i], ring(ctx, color(e)))
                setTextViewText(titles[i], e.title)
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
                setTextViewText(R.id.w_cn_title, "Connect your calendar"); setTextViewText(R.id.w_cn_time, "in Unutma › Calendar")
                return@apply
            }
            val e = Cal.events(ctx, now, now + 60L * 24 * 60 * 60 * 1000).firstOrNull { !it.allDay && it.begin > now }
                ?: Cal.events(ctx, now, now + 60L * 24 * 60 * 60 * 1000).firstOrNull { it.end > now }
            if (e == null) {
                setTextViewText(R.id.w_cn_title, "Nothing coming up"); return@apply
            }
            val diff = e.begin - now
            val (n, unit) = when {
                diff <= 0 -> "now" to ""
                diff < 60 * 60_000L -> "${(diff / 60_000).coerceAtLeast(1)}" to "min"
                diff < 48 * 60 * 60_000L -> "${diff / 3_600_000}" to (if (diff / 3_600_000 == 1L) "hour" else "hours")
                else -> "${diff / (24 * 3_600_000)}" to "days"
            }
            val c = color(e)
            setTextViewText(R.id.w_cn_number, n); setTextColor(R.id.w_cn_number, c)
            setTextViewText(R.id.w_cn_unit, unit); setTextColor(R.id.w_cn_unit, c)
            setTextViewText(R.id.w_cn_title, e.title)
            val day = Routines.dayOf(e.begin)
            val today = Routines.today()
            val dayText = when (day) {
                today -> ""
                today + 1 -> "Tomorrow "
                else -> SimpleDateFormat("EEE d ", Locale.getDefault()).format(Date(e.begin))
            }
            setTextViewText(R.id.w_cn_time, dayText + Cal.timeText(e))
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
                setTextViewText(R.id.w_cw_empty, CONNECT)
                return@apply
            }
            val perDay = Cal.events(ctx, Routines.startOf(monday), Routines.startOf(monday + 7)).groupingBy { Routines.dayOf(it.begin) }.eachCount()
            setTextViewText(R.id.w_cw_count, perDay.values.sum().toString())
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
