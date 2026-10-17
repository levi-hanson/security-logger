package com.daxbot.readywidgets

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Runs [ReadyWidgetProvider.fetchNow] as a job, which Android gives network access even in the background. */
class RefreshJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            ReadyWidgetProvider.fetchNow(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, RefreshJob::class.java)
            val expedited = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExpedited(true)
                .build()
            // Expedited jobs run right away but have a quota; past it, run as soon as the system allows.
            if (scheduler.schedule(expedited) != JobScheduler.RESULT_SUCCESS) {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build(),
                )
            }
        }
    }
}
