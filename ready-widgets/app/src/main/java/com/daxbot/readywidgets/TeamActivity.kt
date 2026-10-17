package com.daxbot.readywidgets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Your team. Without [EXTRA_PERSON]: everyone below you, searchable; tap someone to open
 * their list. With it: that person's to-dos as you may see them (never their Personal ones),
 * and a button to propose them a to-do.
 */
class TeamActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)

        val person = intent.getStringExtra(EXTRA_PERSON)
        if (person == null) showPeople() else showPerson(person)
    }

    override fun onResume() {
        super.onResume()
        // Back from proposing: show the new to-do on their list.
        intent.getStringExtra(EXTRA_PERSON)?.let { loadList(it) }
    }

    private fun showPeople() {
        findViewById<TextView>(R.id.heading).text = "Team"
        findViewById<TextView>(R.id.subheading).text = "Tap someone to see their to-dos or propose one."
        findViewById<View>(R.id.propose).visibility = View.GONE
        val search = findViewById<EditText>(R.id.search)

        var everyone = withoutMe(People.cached(this))
        // simple_list_item_2 needs both lines filled, so bind them by hand.
        val twoLine = object : ArrayAdapter<People.Person>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val p = getItem(position)!!
                view.findViewById<TextView>(android.R.id.text1).text = p.name
                view.findViewById<TextView>(android.R.id.text2).text = p.roles
                return view
            }
        }
        list.adapter = twoLine
        fun refreshShown() {
            val q = search.text.toString().trim().lowercase()
            val shown = everyone.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.roles.lowercase().contains(q) }
            twoLine.clear()
            twoLine.addAll(shown)
            status.text = if (everyone.isEmpty()) "Loading…" else if (shown.isEmpty()) "No one matches." else ""
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshShown()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val p = twoLine.getItem(position) ?: return@setOnItemClickListener
            startActivity(Intent(this, TeamActivity::class.java).putExtra(EXTRA_PERSON, p.uuid))
        }
        refreshShown()

        thread {
            try {
                val fresh = withoutMe(People.fetch(this))
                runOnUiThread {
                    everyone = fresh
                    refreshShown()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (everyone.isEmpty()) {
                        status.text = "Couldn't load your team: ${e.message ?: e.javaClass.simpleName}"
                        status.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** The people list starts with me; the team screen is everyone else. */
    private fun withoutMe(people: List<People.Person>): List<People.Person> = people.drop(1)

    private fun showPerson(uuid: String) {
        findViewById<View>(R.id.search).visibility = View.GONE
        val name = People.cached(this).firstOrNull { it.uuid == uuid }
        findViewById<TextView>(R.id.heading).text = name?.name ?: "To-dos"
        findViewById<TextView>(R.id.subheading).text = name?.roles ?: ""
        findViewById<Button>(R.id.propose).setOnClickListener {
            startActivity(Intent(this, AddTodoActivity::class.java).putExtra(AddTodoActivity.EXTRA_FOR, uuid))
        }
        status.text = "Loading…"
    }

    private fun loadList(uuid: String) {
        thread {
            try {
                val json = Ready.get(this, "/v1/todos?user=$uuid")
                runOnUiThread { showList(json) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't load their list: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun showList(json: JSONObject) {
        json.optJSONObject("owner")?.str("name")?.let { findViewById<TextView>(R.id.heading).text = it }
        if (json.optJSONObject("can")?.optBoolean("propose") == false) {
            findViewById<View>(R.id.propose).visibility = View.GONE
        }
        val codes = HashMap<String, String>()
        json.optJSONArray("tags")?.let { tags ->
            for (i in 0 until tags.length()) {
                val t = tags.getJSONObject(i)
                val key = t.str("key") ?: continue
                codes[key] = t.str("code") ?: continue
            }
        }
        val todos = json.optJSONArray("todos")
        val lines = (0 until (todos?.length() ?: 0)).map { i ->
            val todo = todos!!.getJSONObject(i)
            val done = Todos.isDone(todo)
            val box = when {
                done -> "☑"
                Todos.isPendingProposal(todo) -> "◇"
                else -> "☐"
            }
            val meta = listOfNotNull(
                Todos.proposer(todo)?.let { "proposed by $it" },
                todo.str("due_date"),
                todo.str("tag_key")?.let { codes[it] },
            ).joinToString(" · ")
            "$box  ${todo.str("title") ?: ""}" + if (meta.isEmpty()) "" else "\n      $meta"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        status.text = if (lines.isEmpty()) "No to-dos you can see. (Their Personal ones are always hidden.)" else ""
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_PERSON = "person"
    }
}
