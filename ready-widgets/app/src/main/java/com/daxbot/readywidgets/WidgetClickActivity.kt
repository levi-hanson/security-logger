package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives taps on the widget's list rows (an invisible activity, so a to-do tap can open
 * the tag picker): a header toggles its section, a to-do opens [TodoTagActivity].
 */
class WidgetClickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TOGGLE)?.let {
            ReadyWidgetProvider.toggle(this, it)
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TODO)?.let {
            startActivity(Intent(this, TodoTagActivity::class.java).putExtra(TodoTagActivity.EXTRA_UUID, it))
        }
        finish()
    }
}
