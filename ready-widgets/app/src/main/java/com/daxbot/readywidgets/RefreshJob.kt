package com.daxbot.readywidgets

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Talks to Ready on the widget's behalf: optionally marks a to-do done, then runs
 * [ReadyWidgetProvider.fetchNow]. A job, because Android gives a running job network access
 * even when the app is in the background, which a widget tap leaves it in.
 */
class RefreshJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val doneUuid = params.extras.getString(EXTRA_DONE_UUID)
        val done = params.extras.getBoolean(EXTRA_DONE_VALUE, true)
        val archiveDone = params.extras.getBoolean(EXTRA_ARCHIVE_DONE, false)
        thread {
            if (archiveDone) {
                try {
                    Ready.post(applicationContext, "/v1/todos/archive_done", JSONObject())
                } catch (e: Exception) {
                    Store(applicationContext).lastError = e.message ?: e.javaClass.simpleName
                }
            }
            if (doneUuid != null) {
                try {
                    Ready.patch(applicationContext, "/v1/todos/$doneUuid", JSONObject().put("done", done))
                } catch (e: Exception) {
                    Store(applicationContext).lastError = e.message ?: e.javaClass.simpleName
                }
            }
            ReadyWidgetProvider.fetchNow(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val REFRESH_JOB_ID = 1
        private const val EXTRA_DONE_UUID = "done_uuid"
        private const val EXTRA_DONE_VALUE = "done_value"
        private const val EXTRA_ARCHIVE_DONE = "archive_done"
        private const val ARCHIVE_JOB_ID = 2

        fun schedule(context: Context) {
            schedule(context, REFRESH_JOB_ID, PersistableBundle())
        }

        /** Its own job per to-do, so quick taps on several boxes don't replace each other. */
        fun scheduleDone(context: Context, uuid: String, done: Boolean) {
            val extras = PersistableBundle()
            extras.putString(EXTRA_DONE_UUID, uuid)
            extras.putBoolean(EXTRA_DONE_VALUE, done)
            schedule(context, 1000 + (uuid.hashCode() and 0xffff), extras)
        }

        fun scheduleArchiveDone(context: Context) {
            val extras = PersistableBundle()
            extras.putBoolean(EXTRA_ARCHIVE_DONE, true)
            schedule(context, ARCHIVE_JOB_ID, extras)
        }

        private fun schedule(context: Context, jobId: Int, extras: PersistableBundle) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, RefreshJob::class.java)
            val expedited = JobInfo.Builder(jobId, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setExpedited(true)
                .build()
            // Expedited jobs run right away but have a quota; past it, run as soon as the system allows.
            if (scheduler.schedule(expedited) != JobScheduler.RESULT_SUCCESS) {
                scheduler.schedule(
                    JobInfo.Builder(jobId, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setExtras(extras)
                        .build(),
                )
            }
        }
    }
}
