package com.example.unutma.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.unutma.MainActivity
import com.example.unutma.Note
import com.example.unutma.R
import com.example.unutma.data.CheckBlock
import com.example.unutma.data.Prefs
import com.example.unutma.data.Repo
import com.example.unutma.data.TextBlock
import com.example.unutma.data.noteDisplayTitle
import com.example.unutma.data.parseBlocks
import com.example.unutma.data.stripMarkdown
import com.example.unutma.notify.Notifier
import kotlinx.coroutines.runBlocking

/** Several notes at once as cards (all notes, or one label's — chosen in Widget settings) and a + button. */
class NotesListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = render(context, mgr, ids)

    override fun onDeleted(context: Context, ids: IntArray) {
        val p = Prefs.get(context)
        ids.forEach { p.clearWidget(it) }
    }

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_notes)
                val svc = Intent(ctx, NotesListService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse("unutma://notes/$id")
                }
                @Suppress("DEPRECATION")
                v.setRemoteAdapter(R.id.w_ns_list, svc)
                v.setEmptyView(R.id.w_ns_list, R.id.w_ns_empty)
                // Tapping a card opens that note (the card fills in which one).
                val open = Intent(ctx, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_NOTE
                    data = Uri.parse("unutma://notes-open/$id")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                v.setPendingIntentTemplate(R.id.w_ns_list,
                    PendingIntent.getActivity(ctx, 70_000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT or mutable))

                val accent = Widgets.accent(ctx)
                v.setInt(R.id.w_ns_fab_bg, "setColorFilter", accent)
                v.setInt(R.id.w_ns_fab_plus, "setColorFilter", 0xFF0A0A0A.toInt())
                v.setOnClickPendingIntent(R.id.w_ns_fab, Widgets.openApp(ctx, MainActivity.ACTION_NEW_NOTE, 71_000 + id))
                mgr.updateAppWidget(id, v)
                mgr.notifyAppWidgetViewDataChanged(id, R.id.w_ns_list)
            }
        }

        fun refresh(ctx: Context, mgr: AppWidgetManager) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesListWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        /** Up to three lines for a card: the text, then the items still to do. */
        fun preview(note: Note): String {
            if (note.isLocked) return "Locked"
            val blocks = parseBlocks(note)
            val lines = ArrayList<String>()
            for (b in blocks) when (b) {
                is TextBlock -> b.text.lines().map { stripMarkdown(it).trim() }.filter { it.isNotEmpty() }.forEach { lines += it }
                is CheckBlock -> if (b.text.isNotBlank() && !b.checked) lines += "· " + stripMarkdown(b.text).trim()
                else -> Unit
            }
            // If the title came from the first line, don't repeat it.
            if (note.title.isBlank() && lines.isNotEmpty()) lines.removeAt(0)
            return lines.take(3).joinToString("\n")
        }
    }
}

/** The note cards for [NotesListWidget]. */
class NotesListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(val ctx: Context, val widgetId: Int) : RemoteViewsFactory {
        private var notes: List<Note> = emptyList()
        private var colors: Map<Int, Int> = emptyMap()

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = notes.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = true
        override fun getItemId(position: Int) = notes.getOrNull(position)?.id?.toLong() ?: position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun onDataSetChanged() {
            runBlocking {
                val labelFilter = Prefs.get(ctx).widgetLabel(widgetId)
                val labels = Repo.labels(ctx).all()
                colors = labels.associate { it.id to Widgets.labelColor(ctx, it) }
                notes = Repo.notes(ctx).activeNow()
                    .filter { labelFilter < 0 || it.labelId == labelFilter }
                    .sortedWith(compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt })
                    .take(50)
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val n = notes.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_notes_item)
            return RemoteViews(ctx.packageName, R.layout.widget_notes_item).apply {
                setTextViewText(R.id.w_ni_title, noteDisplayTitle(n))
                val body = NotesListWidget.preview(n)
                setTextViewText(R.id.w_ni_body, body)
                setViewVisibility(R.id.w_ni_body, if (body.isEmpty()) View.GONE else View.VISIBLE)
                val c = n.labelId?.let { colors[it] }
                if (c != null) {
                    setViewVisibility(R.id.w_ni_dot, View.VISIBLE)
                    setInt(R.id.w_ni_dot, "setColorFilter", c)
                } else setViewVisibility(R.id.w_ni_dot, View.GONE)
                setOnClickFillInIntent(R.id.w_ni_root, Intent().putExtra(Notifier.EXTRA_NOTE_ID, n.id))
                Widgets.scaleText(ctx, widgetId, this, R.id.w_ni_title to 15f, R.id.w_ni_body to 13f)
            }
        }
    }
}
