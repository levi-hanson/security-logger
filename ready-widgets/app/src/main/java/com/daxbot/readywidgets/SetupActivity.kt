package com.daxbot.readywidgets

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Where you enter Ready's address and sign in or out. */
class SetupActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var status: TextView
    private lateinit var signIn: Button
    private lateinit var signOut: Button
    private lateinit var updateStatus: TextView
    private lateinit var installUpdate: Button
    private lateinit var checkUpdates: Button
    private lateinit var logEndpoint: EditText
    private lateinit var logStatus: TextView
    private lateinit var logSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        server = findViewById(R.id.server)
        status = findViewById(R.id.status)
        signIn = findViewById(R.id.sign_in)
        signOut = findViewById(R.id.sign_out)
        updateStatus = findViewById(R.id.update_status)
        installUpdate = findViewById(R.id.install_update)
        installUpdate.setOnClickListener { startUpdate() }
        checkUpdates = findViewById(R.id.check_updates)
        checkUpdates.visibility = if (Updater.enabled) View.VISIBLE else View.GONE
        checkUpdates.setOnClickListener { checkNow() }

        logEndpoint = findViewById(R.id.log_endpoint)
        logStatus = findViewById(R.id.log_status)
        logSend = findViewById(R.id.log_send)
        CaptainsLog.endpoint(this)?.let { logEndpoint.setText(it) }
        findViewById<Button>(R.id.log_save).setOnClickListener { saveLogEndpoint() }
        findViewById<Button>(R.id.log_record).setOnClickListener {
            startActivity(Intent(this, RecordLogActivity::class.java))
        }
        findViewById<Button>(R.id.log_tile).setOnClickListener { addTile() }
        logSend.setOnClickListener { sendLog() }

        Store(this).serverUrl?.let { server.setText(it) }
        signIn.setOnClickListener { startSignIn() }
        signOut.setOnClickListener {
            Store(this).signOut()
            ReadyWidgetProvider.render(this)
            showState()
        }
    }

    override fun onResume() {
        super.onResume()
        showState()
        showUpdate()
        showLog()
        if (Updater.enabled) {
            thread {
                try { Updater.check(this) } catch (e: Exception) { /* offline; keep what we knew */ }
                runOnUiThread { showUpdate() }
            }
        }
    }

    private fun showLog() {
        val waiting = CaptainsLog.pendingCount(this)
        val parts = ArrayList<String>()
        if (CaptainsLog.endpoint(this) == null) parts.add("No sheet address yet. Entries are kept on the phone until you add one.")
        if (waiting > 0) parts.add("$waiting ${if (waiting == 1) "entry is" else "entries are"} waiting to send.")
        if (waiting > 0) CaptainsLog.lastError(this)?.let { parts.add("Last try: $it") }
        logStatus.text = parts.joinToString("\n")
        logSend.visibility = if (waiting > 0) View.VISIBLE else View.GONE
    }

    private fun saveLogEndpoint() {
        val url = logEndpoint.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("https://")) {
            logStatus.text = "The address should start with https:// (copy it from Apps Script's Deploy → Manage deployments)."
            return
        }
        CaptainsLog.setEndpoint(this, url)
        Toast.makeText(this, if (url.isEmpty()) "Sheet address removed" else "Sheet address saved", Toast.LENGTH_SHORT).show()
        if (url.isNotEmpty() && CaptainsLog.pendingCount(this) > 0) sendLog() else showLog()
    }

    private fun sendLog() {
        logSend.isEnabled = false
        CaptainsLog.flushInBackground(this) { message ->
            runOnUiThread {
                logSend.isEnabled = true
                showLog()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Android 13+ can add the tile with one confirm; older versions need the quick-settings editor. */
    private fun addTile() {
        val manual = "Pull down quick settings, tap the pencil (Edit), and drag \"Captain's log\" into place."
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, LogTileService::class.java),
                "Captain's log",
                Icon.createWithResource(this, R.drawable.ic_mic),
                mainExecutor,
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added. Find it in quick settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in quick settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                    else -> manual
                }
                message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        } else {
            Toast.makeText(this, manual, Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdate() {
        val version = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        val available = Updater.updateAvailable(this)
        installUpdate.visibility = if (available) View.VISIBLE else View.GONE
        installUpdate.text = "Install update (build ${Updater.availableBuild(this)})"
        updateStatus.text = version + (Updater.lastError(this)?.let { "\nLast update failed: $it" } ?: "")
    }

    private fun checkNow() {
        checkUpdates.isEnabled = false
        checkUpdates.text = "Checking…"
        thread {
            val result = try {
                Updater.check(this)
                if (Updater.updateAvailable(this)) "Build ${Updater.availableBuild(this)} is available." else "You're up to date."
            } catch (e: Exception) {
                "Couldn't check: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                checkUpdates.isEnabled = true
                checkUpdates.text = "Check for updates"
                showUpdate()
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startUpdate() {
        installUpdate.isEnabled = false
        installUpdate.text = "Downloading…"
        thread {
            try {
                Updater.install(this)
            } catch (e: Exception) {
                Updater.recordError(this, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                installUpdate.isEnabled = true
                showUpdate()
            }
        }
    }

    private fun showState() {
        val store = Store(this)
        signOut.isEnabled = store.signedIn
        signIn.text = if (store.signedIn) "Sign in again" else "Sign in"
        status.text = if (store.signedIn) {
            "Signed in as ${store.userName ?: "you"}.\n\nAdd the widget: long-press your home screen → Widgets → Ready Widgets." +
                (store.lastError?.let { "\n\nLast refresh failed: $it" } ?: "")
        } else {
            "Not signed in."
        }
    }

    private fun startSignIn() {
        val input = server.text.toString()
        if (input.isBlank()) {
            status.text = "Enter the address you use to open Ready in a browser."
            return
        }
        signIn.isEnabled = false
        status.text = "Contacting Ready…"
        thread {
            try {
                val url = Ready.beginSignIn(this, input)
                LoopbackReceiver.start(this) { _, error ->
                    runOnUiThread {
                        showState()
                        error?.let { status.text = "Sign-in failed: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
                runOnUiThread {
                    signIn.isEnabled = true
                    SignInService.start(this)
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    signIn.isEnabled = true
                    status.text = "Couldn't start sign-in: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }
}
