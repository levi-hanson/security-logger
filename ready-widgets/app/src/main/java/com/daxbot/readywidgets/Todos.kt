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

    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
