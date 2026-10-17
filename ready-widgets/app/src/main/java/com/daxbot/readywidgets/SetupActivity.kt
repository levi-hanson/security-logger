package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

/** Where you enter Ready's address and sign in or out. */
class SetupActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var status: TextView
    private lateinit var signIn: Button
    private lateinit var signOut: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        server = findViewById(R.id.server)
        status = findViewById(R.id.status)
        signIn = findViewById(R.id.sign_in)
        signOut = findViewById(R.id.sign_out)

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
                runOnUiThread {
                    signIn.isEnabled = true
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
