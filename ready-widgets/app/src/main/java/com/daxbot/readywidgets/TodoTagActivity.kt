package com.daxbot.readywidgets

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Opened by tapping a to-do on the widget: retag it Personal, one of your roles, or untagged. */
class TodoTagActivity : Activity() {
    private data class Tag(val label: String, val personal: Boolean, val roleUuid: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Tag")
        setContentView(R.layout.activity_todo_tag)

        val titleView = findViewById<TextView>(R.id.todo_title)
        val group = findViewById<RadioGroup>(R.id.tags)
        val error = findViewById<TextView>(R.id.error)
        val save = findViewById<Button>(R.id.save)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        val uuid = intent.getStringExtra(EXTRA_UUID)
        val cached = Store(this).cachedTodos?.let { JSONObject(it) }
        val todos = cached?.optJSONArray("todos")
        val todo = (0 until (todos?.length() ?: 0)).map { todos!!.getJSONObject(it) }.firstOrNull { it.str("uuid") == uuid }
        if (uuid == null || cached == null || todo == null) {
            Toast.makeText(this, "That to-do is gone; refreshing", Toast.LENGTH_SHORT).show()
            ReadyWidgetProvider.refresh(this)
            finish()
            return
        }
        titleView.text = todo.str("title")

        // Personal first, then the roles you hold, then no tag.
        val tags = ArrayList<Tag>()
        tags.add(Tag("Personal", true, null))
        cached.optJSONArray("tags")?.let { list ->
            for (i in 0 until list.length()) {
                val t = list.getJSONObject(i)
                val role = t.str("role_uuid") ?: continue
                if (!t.optBoolean("held", true)) continue
                tags.add(Tag(t.str("name") ?: "Role", false, role))
            }
        }
        tags.add(Tag("No tag", false, null))

        val currentPersonal = todo.optBoolean("personal")
        val currentRole = todo.str("role_uuid")
        tags.forEachIndexed { i, tag ->
            val button = RadioButton(this)
            button.id = View.generateViewId()
            button.text = tag.label
            button.tag = i
            button.textSize = 16f
            button.setPadding(8, 16, 8, 16)
            group.addView(button)
            val isCurrent = when {
                tag.personal -> currentPersonal
                tag.roleUuid != null -> !currentPersonal && currentRole == tag.roleUuid
                else -> !currentPersonal && currentRole == null
            }
            if (isCurrent) group.check(button.id)
        }

        save.setOnClickListener {
            val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return@setOnClickListener
            val tag = tags[checked.tag as Int]
            save.isEnabled = false
            error.visibility = View.GONE
            thread {
                try {
                    val path = "/v1/todos/$uuid"
                    if (tag.personal) {
                        Ready.patch(this, path, JSONObject().put("personal", true))
                    } else {
                        // Personal and a role are separate fields: clear Personal, then set the role (or none).
                        if (currentPersonal) Ready.patch(this, path, JSONObject().put("personal", false))
                        if (currentRole != tag.roleUuid) {
                            Ready.patch(this, path, JSONObject().put("role_uuid", tag.roleUuid ?: JSONObject.NULL))
                        }
                    }
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        Toast.makeText(this, "Tagged ${tag.label}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        error.text = e.message ?: e.javaClass.simpleName
                        error.visibility = View.VISIBLE
                        save.isEnabled = true
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_UUID = "uuid"
    }
}
