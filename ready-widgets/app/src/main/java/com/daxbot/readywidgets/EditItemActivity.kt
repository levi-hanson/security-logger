package com.daxbot.readywidgets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Opened by tapping a to-do or an issue on the widget: edit its title, add a note, and for a
 * to-do change its tag (Personal, one of your roles, or none).
 */
class EditItemActivity : Activity() {
    private data class Tag(val label: String, val personal: Boolean, val roleUuid: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_item)

        val kind = intent.getStringExtra(EXTRA_KIND)
        val uuid = intent.getStringExtra(EXTRA_UUID)
        val store = Store(this)
        val isTodo = kind == KIND_TODO
        val found = when {
            uuid == null -> null
            isTodo -> findTodo(store, uuid)
            else -> findIssue(store, uuid)
        }
        if (found == null) {
            Toast.makeText(this, "That item is gone; refreshing", Toast.LENGTH_SHORT).show()
            ReadyWidgetProvider.refresh(this)
            finish()
            return
        }
        val (item, contextLabel) = found
        setTitle(if (isTodo) "To-do" else "Issue")

        val titleField = findViewById<EditText>(R.id.title)
        val group = findViewById<RadioGroup>(R.id.tags)
        val notesView = findViewById<TextView>(R.id.notes)
        val newNote = findViewById<EditText>(R.id.new_note)
        val doneBox = findViewById<CheckBox>(R.id.done)
        val error = findViewById<TextView>(R.id.error)
        val save = findViewById<Button>(R.id.save)
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        findViewById<TextView>(R.id.context).text = contextLabel
        val originalTitle = item.str("title") ?: ""
        titleField.setText(originalTitle)

        val existingNotes = formatNotes(item)
        if (existingNotes.isEmpty()) {
            notesView.visibility = View.GONE
        } else {
            notesView.text = existingNotes
        }

        // Done, for to-dos only. A proposal must be accepted (or declined) first.
        val originalDone = isTodo && Todos.isDone(item)
        val pending = isTodo && Todos.isPendingProposal(item)
        doneBox.isChecked = originalDone
        // Ready doesn't say whether a proposal was accepted yet, so Done stays offered;
        // if it wasn't, Ready refuses and the error shows here.
        doneBox.visibility = if (isTodo) View.VISIBLE else View.GONE
        if (!isTodo) {
            val close = findViewById<Button>(R.id.close_issue)
            close.visibility = View.VISIBLE
            close.setOnClickListener {
                startActivity(Intent(this, CloseIssueActivity::class.java).putExtra(CloseIssueActivity.EXTRA_UUID, uuid))
                finish()
            }
        }
        if (pending) {
            findViewById<View>(R.id.proposal).visibility = View.VISIBLE
            findViewById<TextView>(R.id.proposal_text).text =
                "Proposed by ${Todos.proposer(item) ?: "your supervisor"}. If you haven't yet, accept it to work on it, or decline it."
            findViewById<Button>(R.id.accept).setOnClickListener { answerProposal(uuid!!, "accept", "Accepted") }
            findViewById<Button>(R.id.decline).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Decline this to-do? It is removed from your list and the proposer is told.")
                    .setPositiveButton("Decline") { _, _ -> answerProposal(uuid!!, "decline", "Declined") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // Tags, for to-dos only: Personal first, then the roles you hold, then no tag.
        val tags = ArrayList<Tag>()
        val currentPersonal = item.optBoolean("personal")
        val currentRole = item.str("role_uuid")
        if (isTodo) {
            tags.add(Tag("Personal", true, null))
            store.cachedTodos?.let { JSONObject(it) }?.optJSONArray("tags")?.let { list ->
                for (i in 0 until list.length()) {
                    val t = list.getJSONObject(i)
                    val role = t.str("role_uuid") ?: continue
                    if (!t.optBoolean("held", true)) continue
                    tags.add(Tag(t.str("name") ?: "Role", false, role))
                }
            }
            tags.add(Tag("No tag", false, null))
            tags.forEachIndexed { i, tag ->
                val button = RadioButton(this)
                button.id = View.generateViewId()
                button.text = tag.label
                button.tag = i
                button.textSize = 16f
                button.setPadding(8, 12, 8, 12)
                group.addView(button)
                if (isCurrent(tag, currentPersonal, currentRole)) group.check(button.id)
            }
        } else {
            findViewById<View>(R.id.tags_label).visibility = View.GONE
            group.visibility = View.GONE
        }

        save.setOnClickListener {
            val title = titleField.text.toString().trim()
            if (title.isEmpty()) {
                titleField.error = "Required"
                return@setOnClickListener
            }
            val note = newNote.text.toString().trim()
            val tag = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.let { tags[it.tag as Int] }
            val retag = isTodo && tag != null && !isCurrent(tag, currentPersonal, currentRole)
            val doneChanged = isTodo && doneBox.isChecked != originalDone
            if (title == originalTitle && note.isEmpty() && !retag && !doneChanged) {
                finish()
                return@setOnClickListener
            }

            save.isEnabled = false
            error.visibility = View.GONE
            val path = (if (isTodo) "/v1/todos/" else "/v1/issues/") + uuid
            thread {
                try {
                    if (title != originalTitle) Ready.patch(this, path, JSONObject().put("title", title))
                    if (retag && tag != null) {
                        if (tag.personal) {
                            Ready.patch(this, path, JSONObject().put("personal", true))
                        } else {
                            // Personal and a role are separate fields: clear Personal, then set the role (or none).
                            if (currentPersonal) Ready.patch(this, path, JSONObject().put("personal", false))
                            if (currentRole != tag.roleUuid) {
                                Ready.patch(this, path, JSONObject().put("role_uuid", tag.roleUuid ?: JSONObject.NULL))
                            }
                        }
                    }
                    if (note.isNotEmpty()) Ready.post(this, "$path/notes", JSONObject().put("body", note))
                    if (doneChanged) Ready.patch(this, path, JSONObject().put("done", doneBox.isChecked))
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    // Whatever went through before the failure is saved; refresh so the widget shows it.
                    ReadyWidgetProvider.refresh(this)
                    runOnUiThread {
                        error.text = e.message ?: e.javaClass.simpleName
                        error.visibility = View.VISIBLE
                        save.isEnabled = true
                    }
                }
            }
        }
    }

    /** POST /v1/todos/{uuid}/accept or /decline, then close. */
    private fun answerProposal(uuid: String, action: String, doneMessage: String) {
        findViewById<Button>(R.id.accept).isEnabled = false
        findViewById<Button>(R.id.decline).isEnabled = false
        thread {
            try {
                Ready.post(this, "/v1/todos/$uuid/$action", JSONObject())
                ReadyWidgetProvider.refresh(this)
                runOnUiThread {
                    Toast.makeText(this, doneMessage, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val error = findViewById<TextView>(R.id.error)
                    error.text = e.message ?: e.javaClass.simpleName
                    error.visibility = View.VISIBLE
                    findViewById<Button>(R.id.accept).isEnabled = true
                    findViewById<Button>(R.id.decline).isEnabled = true
                }
            }
        }
    }

    private fun isCurrent(tag: Tag, currentPersonal: Boolean, currentRole: String?): Boolean = when {
        tag.personal -> currentPersonal
        tag.roleUuid != null -> !currentPersonal && currentRole == tag.roleUuid
        else -> !currentPersonal && currentRole == null
    }

    /** The to-do and a line saying whose list it is on. */
    private fun findTodo(store: Store, uuid: String): Pair<JSONObject, String>? {
        val json = store.cachedTodos?.let { JSONObject(it) } ?: return null
        val todos = json.optJSONArray("todos") ?: return null
        for (i in 0 until todos.length()) {
            val todo = todos.getJSONObject(i)
            if (todo.str("uuid") == uuid) return todo to "Your to-dos"
        }
        return null
    }

    /** The issue and the name of the list it is on. */
    private fun findIssue(store: Store, uuid: String): Pair<JSONObject, String>? {
        val json = store.cachedIssues?.let { JSONObject(it) } ?: return null
        val lists = json.optJSONArray("lists") ?: return null
        for (i in 0 until lists.length()) {
            val list = lists.getJSONObject(i)
            val issues = list.optJSONArray("issues") ?: continue
            for (j in 0 until issues.length()) {
                val issue = issues.getJSONObject(j)
                if (issue.str("uuid") == uuid) return issue to (list.str("name") ?: "Issue")
            }
        }
        return null
    }

    /** "Name · Oct 16" then the note, oldest first, as Ready keeps them. */
    private fun formatNotes(item: JSONObject): String {
        val notes = item.optJSONArray("notes") ?: return ""
        return (0 until notes.length()).joinToString("\n\n") { i ->
            val note = notes.getJSONObject(i)
            val author = note.optJSONObject("author")?.str("name") ?: "Someone"
            val date = note.str("created_at")?.take(10)?.let { shortDate(it) }
            listOfNotNull(author, date).joinToString(" · ") + "\n" + (note.str("body") ?: "")
        }
    }

    private fun shortDate(iso: String): String {
        val parts = iso.split("-")
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return iso
        val d = parts.getOrNull(2)?.toIntOrNull() ?: return iso
        return if (m in 1..12) "${months[m - 1]} $d" else iso
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_UUID = "uuid"
        const val KIND_TODO = "todo"
        const val KIND_ISSUE = "issue"
    }
}
