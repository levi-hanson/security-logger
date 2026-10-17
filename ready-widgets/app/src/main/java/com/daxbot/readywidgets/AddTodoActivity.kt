package com.daxbot.readywidgets

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Quick add from the widget. To-dos are Personal unless you untick it. */
class AddTodoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("New to-do")
        setContentView(R.layout.activity_add_todo)

        val title = findViewById<EditText>(R.id.title)
        val personal = findViewById<CheckBox>(R.id.personal)
        val error = findViewById<TextView>(R.id.error)
        val save = findViewById<Button>(R.id.save)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        if (!Store(this).signedIn) {
            error.text = "Sign in first: open the Ready Widgets app."
            error.visibility = View.VISIBLE
            save.isEnabled = false
            return
        }

        fun submit() {
            val text = title.text.toString().trim()
            if (text.isEmpty()) {
                title.error = "Required"
                return
            }
            save.isEnabled = false
            error.visibility = View.GONE
            val body = JSONObject().put("title", text)
            if (personal.isChecked) body.put("personal", true)
            thread {
                try {
                    Ready.post(this, "/v1/todos", body)
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        Toast.makeText(this, "To-do added", Toast.LENGTH_SHORT).show()
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

        save.setOnClickListener { submit() }
        title.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }
}
