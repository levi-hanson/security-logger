package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
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
        if (Updater.enabled) {
            thread {
                try { Updater.check(this) } catch (e: Exception) { /* offline; keep what we knew */ }
                runOnUiThread { showUpdate() }
            }
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
