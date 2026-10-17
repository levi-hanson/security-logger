package com.daxbot.readywidgets

import android.content.Context
import android.content.SharedPreferences

/** Everything the app remembers: the server, the OAuth session, and the last data fetched for the widget. */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ready", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

    var authorizationEndpoint: String?
        get() = prefs.getString("authorization_endpoint", null)
        set(v) = prefs.edit().putString("authorization_endpoint", v).apply()

    var tokenEndpoint: String?
        get() = prefs.getString("token_endpoint", null)
        set(v) = prefs.edit().putString("token_endpoint", v).apply()

    var clientId: String?
        get() = prefs.getString("client_id", null)
        set(v) = prefs.edit().putString("client_id", v).apply()

    /** The server the client_id was registered with; a different server needs a new registration. */
    var clientServer: String?
        get() = prefs.getString("client_server", null)
        set(v) = prefs.edit().putString("client_server", v).apply()

    var pendingVerifier: String?
        get() = prefs.getString("pending_verifier", null)
        set(v) = prefs.edit().putString("pending_verifier", v).apply()

    var pendingState: String?
        get() = prefs.getString("pending_state", null)
        set(v) = prefs.edit().putString("pending_state", v).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    /** Epoch millis; 0 when the server gave no expiry. */
    var accessTokenExpiresAt: Long
        get() = prefs.getLong("access_token_expires_at", 0L)
        set(v) = prefs.edit().putLong("access_token_expires_at", v).apply()

    var userName: String?
        get() = prefs.getString("user_name", null)
        set(v) = prefs.edit().putString("user_name", v).apply()

    val signedIn: Boolean get() = serverUrl != null && accessToken != null

    var cachedTodos: String?
        get() = prefs.getString("cached_todos", null)
        set(v) = prefs.edit().putString("cached_todos", v).apply()

    var cachedIssues: String?
        get() = prefs.getString("cached_issues", null)
        set(v) = prefs.edit().putString("cached_issues", v).apply()

    /** Other people's to-dos I watch, including the ones I proposed (GET /v1/todos/watching). */
    var cachedWatching: String?
        get() = prefs.getString("cached_watching", null)
        set(v) = prefs.edit().putString("cached_watching", v).apply()

    /** Me, then everyone below me (GET /v1/todos/people). */
    var cachedPeople: String?
        get() = prefs.getString("cached_people", null)
        set(v) = prefs.edit().putString("cached_people", v).apply()

    var updatedAt: Long
        get() = prefs.getLong("updated_at", 0L)
        set(v) = prefs.edit().putLong("updated_at", v).apply()

    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) = prefs.edit().putString("last_error", v).apply()

    var lastIssueList: String?
        get() = prefs.getString("last_issue_list", null)
        set(v) = prefs.edit().putString("last_issue_list", v).apply()

    /** To-dos start open; Issues, each issue list and Delegated start collapsed, until you tap them. */
    fun isCollapsed(key: String): Boolean =
        prefs.getBoolean("collapsed:$key", key == "issues" || key == "delegated" || key.startsWith("list:"))

    fun toggleCollapsed(key: String) {
        prefs.edit().putBoolean("collapsed:$key", !isCollapsed(key)).apply()
    }

    fun signOut() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("access_token_expires_at")
            .remove("user_name")
            .remove("cached_todos")
            .remove("cached_issues")
            .remove("cached_watching")
            .remove("cached_people")
            .remove("updated_at")
            .remove("last_error")
            .apply()
    }
}
