package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Receives taps on the widget's list rows (an invisible activity, so a to-do tap can open
 * the edit screen): a header toggles its section, a to-do's box marks it done, and a to-do
 * or issue opens [EditItemActivity].
 */
class WidgetClickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TOGGLE)?.let {
            ReadyWidgetProvider.toggle(this, it)
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TODO)?.let { open(EditItemActivity.KIND_TODO, it) }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_ISSUE)?.let { open(EditItemActivity.KIND_ISSUE, it) }
        if (intent.getBooleanExtra(ReadyWidgetProvider.EXTRA_OPEN_READY, false)) {
            Store(this).serverUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_OPEN_WIP)?.let { uuid ->
            Store(this).serverUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$it/wip/edit/$uuid"))) }
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_TEAM)?.let { person ->
            val team = Intent(this, TeamActivity::class.java)
            if (person.isNotEmpty()) team.putExtra(TeamActivity.EXTRA_PERSON, person)
            startActivity(team)
        }
        intent.getStringExtra(ReadyWidgetProvider.EXTRA_DONE)?.let {
            Todos.setDoneFromWidget(this, it, intent.getBooleanExtra(ReadyWidgetProvider.EXTRA_DONE_VALUE, true))
        }
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
