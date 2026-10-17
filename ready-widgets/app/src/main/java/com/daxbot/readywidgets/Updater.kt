package com.daxbot.readywidgets

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Updates the app from its GitHub release, so a new build installs with one tap instead of
 * a re-download. The release notes carry "build N" (the build's versionCode).
 */
object Updater {
    private const val CHECK_EVERY_MILLIS = 6 * 60 * 60 * 1000L
    private val BUILD = Regex("""build (\d+)""")

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val enabled: Boolean get() = BuildConfig.UPDATE_TAG.isNotEmpty()

    fun availableBuild(context: Context): Int = prefs(context).getInt("build", 0)

    fun updateAvailable(context: Context): Boolean = enabled && availableBuild(context) > BuildConfig.VERSION_CODE

    fun lastError(context: Context): String? = prefs(context).getString("error", null)

    fun checkIfDue(context: Context) {
        if (System.currentTimeMillis() - prefs(context).getLong("checked_at", 0L) < CHECK_EVERY_MILLIS) return
        try { check(context) } catch (e: IOException) { /* try again next time */ }
    }

    /** Looks up the latest build on the release. Blocks. */
    fun check(context: Context) {
        if (!enabled) return
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/${BuildConfig.UPDATE_TAG}"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        val release = Ready.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            JSONObject(response.body?.string() ?: "{}")
        }
        val build = BUILD.find(release.str("body") ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val assets = release.optJSONArray("assets")
        var apk: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.getJSONObject(i)
            if (asset.str("name")?.endsWith(".apk") == true) apk = asset.str("browser_download_url")
        }
        prefs(context).edit()
            .putInt("build", if (apk != null) build else 0)
            .putString("apk_url", apk)
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
    }

    /** Downloads the new build and hands it to Android's installer, which asks to confirm. Blocks. */
    fun install(context: Context) {
        val url = prefs(context).getString("apk_url", null) ?: throw IOException("No update found")
        prefs(context).edit().remove("error").apply()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                Ready.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                    val body = response.body ?: throw IOException("Download was empty")
                    session.openWrite("ready-widgets.apk", 0, body.contentLength()).use { out ->
                        body.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    fun recordError(context: Context, message: String) {
        prefs(context).edit().putString("error", message).apply()
    }
}

/** Android's installer reports here: show its confirm screen, or remember why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                var message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    message += ". This build is signed differently: uninstall the app and install it from the release page once."
                }
                Updater.recordError(context, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
