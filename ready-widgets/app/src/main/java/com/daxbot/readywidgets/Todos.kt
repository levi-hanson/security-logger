package com.daxbot.readywidgets

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** To-do rules shared by the widget and the edit screen. */
object Todos {

    fun isDone(todo: JSONObject): Boolean = !todo.isNull("done_at") || todo.optBoolean("done")

    /**
     * A to-do a supervisor proposed that you have not accepted yet; Ready won't let it be
     * completed until it is. `proposed` is null on your own to-dos.
     */
    fun isPendingProposal(todo: JSONObject): Boolean {
        if (todo.isNull("proposed")) return false
        val proposed = todo.optJSONObject("proposed") ?: return todo.optBoolean("proposed")
        return proposed.isNull("accepted_at") && !proposed.optBoolean("accepted")
    }

    /** Who proposed it, when Ready says. */
    fun proposer(todo: JSONObject): String? {
        val proposed = todo.optJSONObject("proposed") ?: return null
        proposed.str("by_name")?.let { return it }
        for (key in listOf("by", "proposed_by", "proposer")) {
            proposed.optJSONObject(key)?.str("name")?.let { return it }
        }
        return null
    }

    /**
     * Marks a to-do done (or not) from the widget: flips it in the cached list so the widget
     * shows it at once, then sends it to Ready from [RefreshJob], which refetches after.
     */
    fun setDoneFromWidget(context: Context, uuid: String, done: Boolean) {
        val store = Store(context)
        store.cachedTodos?.let { cached ->
            val json = JSONObject(cached)
            val todos = json.optJSONArray("todos")
            for (i in 0 until (todos?.length() ?: 0)) {
                val todo = todos!!.getJSONObject(i)
                if (todo.str("uuid") == uuid) todo.put("done_at", if (done) nowIso() else JSONObject.NULL)
            }
            store.cachedTodos = json.toString()
        }
        ReadyWidgetProvider.render(context)
        RefreshJob.scheduleDone(context, uuid, done)
    }

    /** Clears done to-dos: drops them from the cached list at once, then archives them in Ready from [RefreshJob]. */
    fun archiveDoneFromWidget(context: Context) {
        val store = Store(context)
        store.cachedTodos?.let { cached ->
            val json = JSONObject(cached)
            val todos = json.optJSONArray("todos")
            if (todos != null) {
                val kept = org.json.JSONArray()
                for (i in 0 until todos.length()) {
                    val todo = todos.getJSONObject(i)
                    if (!isDone(todo)) kept.put(todo)
                }
                json.put("todos", kept)
                store.cachedTodos = json.toString()
            }
        }
        ReadyWidgetProvider.render(context)
        RefreshJob.scheduleArchiveDone(context)
    }

    // ---- Days ----
    // Ready plans each to-do for a day (planned_date: today to six days out; a past day reads as
    // today) or for Later (null). A project's to-do can instead be unscheduled, waiting under
    // its project.

    /** A choice of day: a date (YYYY-MM-DD), Later (date null), or back to its project. */
    data class Day(val label: String, val date: String?, val unscheduled: Boolean = false) {
        override fun toString() = label
    }

    /** Ready's "today" for the list (org-local), else this phone's date. */
    fun today(list: JSONObject?): String = list?.str("today") ?: java.time.LocalDate.now().toString()

    /** Which group a to-do sits in: "today", a future date, "later", or "project:<uuid>". */
    fun groupOf(todo: JSONObject, today: String): String {
        if (todo.optBoolean("unscheduled")) return "project:" + (todo.str("project_uuid") ?: "")
        val day = todo.str("planned_date") ?: return "later"
        return if (day <= today) "today" else day
    }

    /** "Today", "Tomorrow", else "Fri Nov 13". */
    fun dayLabel(date: String, today: String): String {
        val d = java.time.LocalDate.parse(date)
        val t = java.time.LocalDate.parse(today)
        return when {
            !d.isAfter(t) -> "Today"
            d == t.plusDays(1) -> "Tomorrow"
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))
        }
    }

    /** The days a to-do can be planned for: today and the next six, then Later. */
    fun dayChoices(today: String): List<Day> {
        val t = java.time.LocalDate.parse(today)
        return (0..6).map { val d = t.plusDays(it.toLong()).toString(); Day(dayLabel(d, today), d) } + Day("Later", null)
    }

    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
