package com.daxbot.readywidgets

import android.content.Context
import org.json.JSONObject

/** You and the people below you: whose lists you can open and propose to-dos to. */
object People {
    data class Person(val uuid: String, val name: String, val roles: String) {
        override fun toString() = name
    }

    /** From the cache; empty until [fetch] has run once. */
    fun cached(context: Context): List<Person> = parse(Store(context).cachedPeople)

    /** GET /v1/todos/people. Blocks. */
    fun fetch(context: Context): List<Person> {
        val json = Ready.get(context, "/v1/todos/people")
        Store(context).cachedPeople = json.toString()
        return parse(json.toString())
    }

    private fun parse(text: String?): List<Person> {
        val people = text?.let { JSONObject(it) }?.optJSONArray("people") ?: return emptyList()
        return (0 until people.length()).mapNotNull { i ->
            val p = people.getJSONObject(i)
            val uuid = p.str("uuid") ?: return@mapNotNull null
            val roles = p.optJSONArray("roles")
            val roleText = (0 until (roles?.length() ?: 0)).joinToString(", ") { roles!!.getString(it) }
            Person(uuid, p.str("name") ?: "Someone", roleText)
        }
    }
}
