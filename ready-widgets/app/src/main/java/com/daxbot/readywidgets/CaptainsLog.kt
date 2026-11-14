package com.daxbot.readywidgets

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The captain's log: voice or typed entries, kept on the phone until they reach the Google
 * Sheet's web app (see ready-widgets/captains-log/Code.gs). Entries wait in a queue, so one
 * recorded with no signal is sent later by [LogSyncJob]. Each entry carries an id, and the
 * sheet skips an id it already has, so a resend after an unclear failure never doubles a row.
 */
object CaptainsLog {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences("captains_log", Context.MODE_PRIVATE)

    /** The sheet's web app URL (ends in /exec). Anyone with it can add rows, so it stays on the phone. */
    fun endpoint(context: Context): String? = prefs(context).getString("endpoint", null)

    fun setEndpoint(context: Context, url: String?) {
        prefs(context).edit().putString("endpoint", url?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    fun lastError(context: Context): String? = prefs(context).getString("error", null)

    private fun pending(context: Context): JSONArray =
        try { JSONArray(prefs(context).getString("pending", "[]")) } catch (e: Exception) { JSONArray() }

    fun pendingCount(context: Context): Int = synchronized(lock) { pending(context).length() }

    /** Saves an entry on the phone and starts sending it. */
    fun add(context: Context, text: String, source: String) {
        val entry = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("at", Instant.now().toString())
            .put("text", text)
            .put("source", source)
        synchronized(lock) {
            val queue = pending(context).put(entry)
            prefs(context).edit().putString("pending", queue.toString()).apply()
        }
        LogSyncJob.schedule(context)
    }

    /** Sends waiting entries, oldest first, until one fails. Blocks. Returns how many were sent. */
    fun flush(context: Context): Int {
        val url = endpoint(context) ?: throw IOException("Add your sheet's web app address in the Ready Widgets app.")
        var sent = 0
        while (true) {
            val entry = synchronized(lock) { pending(context).optJSONObject(0) } ?: break
            try {
                post(url, entry)
            } catch (e: IOException) {
                prefs(context).edit().putString("error", e.message ?: e.javaClass.simpleName).apply()
                throw e
            }
            synchronized(lock) {
                val queue = pending(context)
                val rest = JSONArray()
                for (i in 0 until queue.length()) {
                    val item = queue.getJSONObject(i)
                    if (item.optString("id") != entry.optString("id")) rest.put(item)
                }
                prefs(context).edit().putString("pending", rest.toString()).remove("error").apply()
            }
            sent++
        }
        return sent
    }

    private fun post(url: String, entry: JSONObject) {
        val request = Request.Builder().url(url).post(entry.toString().toRequestBody(JSON)).build()
        // Apps Script answers a POST with a redirect to its result, which OkHttp follows.
        Ready.http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw IOException(
                    "The sheet asked for a Google sign-in (${response.code}). Use the deployment's /exec address " +
                        "(not /dev), with \"Who has access\" set to \"Anyone\".",
                )
            }
            if (!response.isSuccessful) throw IOException("The sheet answered ${response.code}")
            val json = try { JSONObject(body) } catch (e: Exception) {
                throw IOException("The sheet didn't answer as expected. Deploy its web app with access set to \"Anyone\".")
            }
            if (!json.optBoolean("ok")) throw IOException("The sheet refused the entry: ${json.optString("error", "unknown reason")}")
        }
    }

    /** For the settings screen: send now, off the main thread. */
    fun flushInBackground(context: Context, done: (String) -> Unit) {
        thread {
            val message = try {
                val sent = flush(context)
                if (sent == 0) "Nothing waiting to send." else "Sent $sent ${if (sent == 1) "entry" else "entries"}."
            } catch (e: Exception) {
                "Couldn't send: ${e.message ?: e.javaClass.simpleName}"
            }
            done(message)
        }
    }
}

/** Sends waiting log entries once the phone has a connection; Android retries it if a send fails. */
class LogSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val retry = try {
                CaptainsLog.flush(applicationContext)
                false
            } catch (e: Exception) {
                CaptainsLog.endpoint(applicationContext) != null
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 3

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, LogSyncJob::class.java)
            val expedited = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExpedited(true)
                .build()
            if (scheduler.schedule(expedited) != JobScheduler.RESULT_SUCCESS) {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build(),
                )
            }
        }
    }
}
