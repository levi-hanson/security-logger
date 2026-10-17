package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import kotlin.concurrent.thread

/** Ready's sign-in page sends the browser back here; trade the code for tokens and refresh the widgets. */
class OAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            text = "Finishing sign-in…"
        }
        setContentView(label)
        setFinishOnTouchOutside(false)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        thread {
            try {
                val name = Ready.finishSignIn(this, data)
                ReadyWidgetProvider.refresh(this)
                runOnUiThread {
                    label.text = "Signed in as $name."
                    label.postDelayed({
                        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }, 900)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    label.text = "Sign-in failed: ${e.message ?: e.javaClass.simpleName}"
                    setFinishOnTouchOutside(true)
                }
            }
        }
    }
}
