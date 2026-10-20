package com.daxbot.readywidgets

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Closes an issue, Ready's way: resolve it (the root issue and at least one to-do that solves
 * it) or discard it (a duplicate, or dropped with a reason). Ready allows undoing either for
 * 5 minutes, from its Issues page.
 */
class CloseIssueActivity : Activity() {
    private data class Person(val uuid: String, val label: String) {
        override fun toString() = label
    }

    private lateinit var rows: LinearLayout
    private var people: List<Person> = emptyList()
    private val spinners = ArrayList<Spinner>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Close issue")
        setContentView(R.layout.activity_close_issue)

        val uuid = intent.getStringExtra(EXTRA_UUID)
        val found = uuid?.let { findIssue(it) }
        if (uuid == null || found == null) {
            Toast.makeText(this, "That issue is gone; refreshing", Toast.LENGTH_SHORT).show()
            ReadyWidgetProvider.refresh(this)
            finish()
            return
        }
        val (issue, listKey) = found

        rows = findViewById(R.id.todo_rows)
        val mode = findViewById<RadioGroup>(R.id.mode)
        val resolveSection = findViewById<View>(R.id.resolve_section)
        val discardSection = findViewById<View>(R.id.discard_section)
        val rootIssue = findViewById<EditText>(R.id.root_issue)
        val duplicate = findViewById<CheckBox>(R.id.duplicate)
        val reason = findViewById<EditText>(R.id.reason)
        val error = findViewById<TextView>(R.id.error)
        val submit = findViewById<Button>(R.id.submit)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.issue_title).text = issue.str("title")

        fun resolving() = mode.checkedRadioButtonId == R.id.mode_resolve
        mode.setOnCheckedChangeListener { _, _ ->
            resolveSection.visibility = if (resolving()) View.VISIBLE else View.GONE
            discardSection.visibility = if (resolving()) View.GONE else View.VISIBLE
            submit.text = if (resolving()) "Resolve" else "Discard"
        }
        duplicate.setOnCheckedChangeListener { _, checked -> reason.isEnabled = !checked }

        // The first solution to-do defaults to the issue's own title, assigned to me.
        addRow(issue.str("title") ?: "")
        findViewById<Button>(R.id.add_row).setOnClickListener { if (spinners.size < 10) addRow("") }
        loadPeople(listKey)

        submit.setOnClickListener {
            error.visibility = View.GONE
            val path: String
            val body = JSONObject()
            if (resolving()) {
                val root = rootIssue.text.toString().trim()
                if (root.isEmpty()) {
                    rootIssue.error = "Ready needs the root issue"
                    return@setOnClickListener
                }
                val todos = JSONArray()
                for (i in 0 until rows.childCount) {
                    val row = rows.getChildAt(i) as LinearLayout
                    val title = (row.getChildAt(0) as EditText).text.toString().trim()
                    if (title.isEmpty()) continue
                    val owner = people.getOrNull(spinners[i].selectedItemPosition)?.uuid ?: myUuid() ?: continue
                    todos.put(JSONObject().put("owner_uuid", owner).put("title", title))
                }
                if (todos.length() == 0) {
                    showError("Add at least one to-do that solves it, or discard the issue instead.")
                    return@setOnClickListener
                }
                path = "/v1/issues/$uuid/resolve"
                body.put("root_issue", root).put("todos", todos)
            } else {
                val why = if (duplicate.isChecked) "duplicate" else reason.text.toString().trim()
                if (why.isEmpty()) {
                    reason.error = "Say why, or tick duplicate"
                    return@setOnClickListener
                }
                path = "/v1/issues/$uuid/discard"
                body.put("reason", why)
            }

            submit.isEnabled = false
            thread {
                try {
                    Ready.post(this, path, body)
                    markClosedInCache(uuid)
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        val done = if (resolving()) "Resolved" else "Discarded"
                        Toast.makeText(this, "$done. You can undo it in Ready for 5 minutes.", Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showError(e.message ?: e.javaClass.simpleName)
                        submit.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        val error = findViewById<TextView>(R.id.error)
        error.text = message
        error.visibility = View.VISIBLE
    }

    /** One solution to-do: its title and whose list it goes on. */
    private fun addRow(title: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val field = EditText(this)
        field.hint = "To-do"
        field.setText(title)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val spinner = Spinner(this)
        spinner.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(field)
        row.addView(spinner)
        rows.addView(row)
        spinners.add(spinner)
        bindPeople(spinner)
    }

    private fun bindPeople(spinner: Spinner) {
        val choices = people.ifEmpty { listOfNotNull(myUuid()?.let { Person(it, "Me") }) }
        val keep = spinner.selectedItemPosition
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        if (keep in choices.indices) spinner.setSelection(keep)
    }

    /** Who a solution to-do may go to: me, the list's people, and everyone below me. Me first. */
    private fun loadPeople(listKey: String) {
        thread {
            try {
                val json = Ready.get(this, "/v1/issues/people?list=" + java.net.URLEncoder.encode(listKey, "UTF-8"))
                val arr = json.optJSONArray("people") ?: json.optJSONArray("result") ?: JSONArray()
                val me = myUuid()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val p = arr.getJSONObject(i)
                    val id = p.str("uuid") ?: return@mapNotNull null
                    Person(id, if (id == me) "Me" else p.str("name") ?: "Someone")
                }.sortedBy { if (it.uuid == me) 0 else 1 }
                runOnUiThread {
                    people = list
                    spinners.forEach { bindPeople(it) }
                }
            } catch (e: Exception) {
                // Offline or refused: the to-dos can still go to me.
            }
        }
    }

    private fun myUuid(): String? =
        Store(this).cachedTodos?.let { JSONObject(it) }?.optJSONObject("owner")?.str("uuid")

    /** The issue and its list's key, from the widget's cache. */
    private fun findIssue(uuid: String): Pair<JSONObject, String>? {
        val lists = Store(this).cachedIssues?.let { JSONObject(it) }?.optJSONArray("lists") ?: return null
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i)
            val issues = list.optJSONArray("issues") ?: continue
            for (j in 0 until issues.length()) {
                val issue = issues.getJSONObject(j)
                if (issue.str("uuid") == uuid) return issue to (issue.str("list_key") ?: list.str("key") ?: return null)
            }
        }
        return null
    }

    /** Takes it off the widget at once; the refresh that follows brings Ready's view. */
    private fun markClosedInCache(uuid: String) {
        val store = Store(this)
        val json = store.cachedIssues?.let { JSONObject(it) } ?: return
        val lists = json.optJSONArray("lists") ?: return
        for (i in 0 until lists.length()) {
            val issues = lists.getJSONObject(i).optJSONArray("issues") ?: continue
            for (j in 0 until issues.length()) {
                val issue = issues.getJSONObject(j)
                if (issue.str("uuid") == uuid) issue.put("resolution", JSONObject().put("outcome", "closed"))
            }
        }
        store.cachedIssues = json.toString()
        ReadyWidgetProvider.render(this)
    }

    companion object {
        const val EXTRA_UUID = "uuid"
    }
}
