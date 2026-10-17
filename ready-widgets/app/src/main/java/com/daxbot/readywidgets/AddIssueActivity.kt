package com.daxbot.readywidgets

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Raise an issue onto one of your lists (a department or a meeting). */
class AddIssueActivity : Activity() {
    private data class IssueList(val key: String, val name: String) {
        override fun toString() = name
    }

    private lateinit var spinner: Spinner
    private lateinit var error: TextView
    private lateinit var save: Button
    private var lists: List<IssueList> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Raise an issue")
        setContentView(R.layout.activity_add_issue)

        spinner = findViewById(R.id.list)
        error = findViewById(R.id.error)
        save = findViewById(R.id.save)
        val title = findViewById<EditText>(R.id.title)
        val note = findViewById<EditText>(R.id.note)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        val store = Store(this)
        if (!store.signedIn) {
            showError("Sign in first: open the Ready Widgets app.")
            save.isEnabled = false
            return
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                lists.getOrNull(position)?.let { store.lastIssueList = it.key }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val cached = store.cachedIssues
        if (cached != null) showLists(JSONObject(cached)) else loadLists()

        save.setOnClickListener {
            val list = lists.getOrNull(spinner.selectedItemPosition)
            val text = title.text.toString().trim()
            if (list == null) {
                showError("No list to raise it on.")
                return@setOnClickListener
            }
            if (text.isEmpty()) {
                title.error = "Required"
                return@setOnClickListener
            }
            save.isEnabled = false
            error.visibility = View.GONE
            val body = JSONObject().put("list_key", list.key).put("title", text)
            note.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body.put("note", it) }
            thread {
                try {
                    Ready.post(this, "/v1/issues", body)
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        Toast.makeText(this, "Issue raised on ${list.name}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showError(e.message ?: e.javaClass.simpleName)
                        save.isEnabled = true
                    }
                }
            }
        }
    }

    private fun loadLists() {
        save.isEnabled = false
        thread {
            try {
                val json = Ready.get(this, "/v1/issues")
                Store(this).cachedIssues = json.toString()
                runOnUiThread {
                    save.isEnabled = true
                    showLists(json)
                }
            } catch (e: Exception) {
                runOnUiThread { showError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Lists you belong to come first (as Ready orders them); lists you only see from above follow. */
    private fun showLists(json: JSONObject) {
        val all = json.optJSONArray("lists") ?: return
        val mine = ArrayList<IssueList>()
        val others = ArrayList<IssueList>()
        for (i in 0 until all.length()) {
            val list = all.getJSONObject(i)
            if (list.optJSONObject("can")?.optBoolean("act") != true) continue
            val item = IssueList(list.str("key") ?: continue, list.str("name") ?: continue)
            if (list.optBoolean("member")) mine.add(item) else others.add(item)
        }
        lists = mine + others
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lists)
        val last = lists.indexOfFirst { it.key == Store(this).lastIssueList }
        if (last >= 0) spinner.setSelection(last)
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
}
