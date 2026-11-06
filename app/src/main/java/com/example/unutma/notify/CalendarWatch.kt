package com.example.unutma.notify

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import com.example.unutma.data.Cal
import com.example.unutma.data.Repo
import com.example.unutma.widget.CalendarWidgetBase
import kotlinx.coroutines.launch

/**
 * Keeps the calendar widgets up to date: Android runs this job whenever anything in the phone's
 * calendars changes (events added, moved or deleted here or in Google Calendar), and it redraws the
 * Agenda, Next event and This week widgets. Each run sets up the next one. The day changing is
 * covered by the nightly tidy-up, which redraws every widget.
 */
class CalendarWatch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Repo.scope.launch {
            try { CalendarWidgetBase.refresh(ctx, AppWidgetManager.getInstance(ctx)) }
            finally {
                schedule(ctx)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4711

        /** Starts watching the calendars (once Unutma may read them). */
        fun schedule(ctx: Context) {
            if (!Cal.canRead(ctx)) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CalendarWatch::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2_000)   // wait for a burst of changes to settle
                .setTriggerContentMaxDelay(15_000)
                .build()
            try { ctx.getSystemService(JobScheduler::class.java)?.schedule(job) } catch (_: Exception) {}
        }
    }
}
