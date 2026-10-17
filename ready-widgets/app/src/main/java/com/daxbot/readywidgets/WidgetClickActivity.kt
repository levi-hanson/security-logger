package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives taps on the widget's list rows (an invisible activity, so a to-do tap can open
 * the edit screen): a header toggles its section, a to-do or issue opens [EditItemActivity].
 */
class WidgetClickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TOGGLE)?.let {
            ReadyWidgetProvider.toggle(this, it)
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TODO)?.let { open(EditItemActivity.KIND_TODO, it) }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_ISSUE)?.let { open(EditItemActivity.KIND_ISSUE, it) }
        finish()
    }

    private fun open(kind: String, uuid: String) {
        startActivity(
            Intent(this, EditItemActivity::class.java)
                .putExtra(EditItemActivity.EXTRA_KIND, kind)
                .putExtra(EditItemActivity.EXTRA_UUID, uuid),
        )
    }
}
