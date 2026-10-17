package com.daxbot.readywidgets

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Quick add from the widget. For me, it's Personal unless you untick it; for someone below
 * you, it's proposed to them (they accept or decline it, and you watch it).
 */
class AddTodoActivity : Activity() {
    private var people: List<People.Person> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("New to-do")
        setContentView(R.layout.activity_add_todo)

        val title = findViewById<EditText>(R.id.title)
        val forSpinner = findViewById<Spinner>(R.id.for_person)
        val personal = findViewById<CheckBox>(R.id.personal)
        val note = findViewById<EditText>(R.id.note)
        val error = findViewById<TextView>(R.id.error)
        val save = findViewById<Button>(R.id.save)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        if (!Store(this).signedIn) {
            error.text = "Sign in first: open the Ready Widgets app."
            error.visibility = View.VISIBLE
            save.isEnabled = false
            return
        }

        val wanted = intent.getStringExtra(EXTRA_FOR)
        fun forMe(): Boolean = forSpinner.selectedItemPosition <= 0
        fun updateForChoice() {
            personal.visibility = if (forMe()) View.VISIBLE else View.GONE
            note.visibility = if (forMe()) View.GONE else View.VISIBLE
            save.text = if (forMe()) "Add to-do" else "Propose"
            setTitle(if (forMe()) "New to-do" else "Propose a to-do")
        }
        fun showPeople(list: List<People.Person>) {
            // Ready lists me first; show that entry as "Me".
            val keep = people.getOrNull(forSpinner.selectedItemPosition)?.uuid ?: wanted
            people = list
            val labels = list.mapIndexed { i, p -> if (i == 0) "Me" else p.name }
            forSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("Me") })
            val index = list.indexOfFirst { it.uuid == keep }
            if (index > 0) forSpinner.setSelection(index)
            updateForChoice()
        }
        forSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateForChoice()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        showPeople(People.cached(this))
        thread {
            try {
                val fresh = People.fetch(this)
                runOnUiThread { showPeople(fresh) }
            } catch (e: Exception) {
                // Offline: proposing waits for a list of people, adding for me still works.
            }
        }

        fun submit() {
            val text = title.text.toString().trim()
            if (text.isEmpty()) {
                title.error = "Required"
                return
            }
            save.isEnabled = false
            error.visibility = View.GONE
            val target = if (forMe()) null else people.getOrNull(forSpinner.selectedItemPosition)
            thread {
                try {
                    if (target == null) {
                        val body = JSONObject().put("title", text)
                        if (personal.isChecked) body.put("personal", true)
                        Ready.post(this, "/v1/todos", body)
                    } else {
                        val body = JSONObject().put("owner_uuid", target.uuid).put("title", text)
                        note.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body.put("note", it) }
                        Ready.post(this, "/v1/todos/propose", body)
                    }
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        Toast.makeText(this, if (target == null) "To-do added" else "Proposed to ${target.name}", Toast.LENGTH_SHORT).show()
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

    companion object {
        /** Preselect someone to propose to (from the team screen). */
        const val EXTRA_FOR = "for"
    }
}
