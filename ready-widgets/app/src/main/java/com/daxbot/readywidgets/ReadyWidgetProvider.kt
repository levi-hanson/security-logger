package com.daxbot.readywidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * The home-screen widget: a collapsible To-dos section and a collapsible Issues section
 * (each issue list collapsible too), with buttons to add a to-do or raise an issue.
 */
class ReadyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        private const val ACTION_REFRESH = "com.daxbot.readywidgets.REFRESH"

        /** Fill-in extras for taps on list rows; handled by [WidgetClickActivity]. */
        const val EXTRA_TOGGLE = "toggle"
        const val EXTRA_TODO = "todo"
        const val EXTRA_ISSUE = "issue"
        const val EXTRA_DONE = "done"
        const val EXTRA_DONE_VALUE = "done_value"

        private const val KEY_TODOS = "todos"
        private const val KEY_ISSUES = "issues"

        @Volatile private var refreshing = false

        /**
         * Asks for fresh to-dos and issues. The fetch runs as a job: a widget tap reaches the app
         * in the background, where Android may cut off its network, but a running job has it.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            if (!Store(app).signedIn) {
                render(app)
                return
            }
            refreshing = true
            render(app)
            RefreshJob.schedule(app)
        }

        /** Fetches to-dos and issues from Ready and redraws every widget. Blocks; called by [RefreshJob]. */
        fun fetchNow(context: Context) {
            val store = Store(context)
            try {
                if (store.signedIn) {
                    val todos = Ready.get(context, "/v1/todos")
                    val issues = Ready.get(context, "/v1/issues")
                    store.cachedTodos = todos.toString()
                    store.cachedIssues = issues.toString()
                    store.updatedAt = System.currentTimeMillis()
                    store.lastError = null
                }
            } catch (e: Exception) {
                store.lastError = e.message ?: e.javaClass.simpleName
            } finally {
                refreshing = false
                render(context)
            }
            Updater.checkIfDue(context)
            render(context)
        }

        fun toggle(context: Context, key: String) {
            Store(context).toggleCollapsed(key)
            render(context)
        }

        /** Redraws every widget from what is cached; no network. */
        fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReadyWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, build(context))
        }

        private fun build(context: Context): RemoteViews {
            val store = Store(context)
            val views = RemoteViews(context.packageName, R.layout.widget)

            views.setOnClickPendingIntent(R.id.title_area, activityIntent(context, SetupActivity::class.java, 10))
            views.setOnClickPendingIntent(R.id.btn_add_todo, activityIntent(context, AddTodoActivity::class.java, 11))
            views.setOnClickPendingIntent(R.id.btn_add_issue, activityIntent(context, AddIssueActivity::class.java, 12))
            views.setOnClickPendingIntent(R.id.btn_refresh, broadcast(context, ACTION_REFRESH, 13))

            views.setTextViewText(R.id.status, statusLine(context, store))

            // Rows fill in what was tapped; the template must be mutable for that.
            val rowTap = PendingIntent.getActivity(
                context, 14, Intent(context, WidgetClickActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.list, rowTap)
            views.setEmptyView(R.id.list, R.id.empty)
            if (store.signedIn) {
                views.setTextViewText(R.id.empty, if (refreshing) "Loading…" else "Nothing to show yet. Tap ↻ to refresh.")
                views.setOnClickPendingIntent(R.id.empty, broadcast(context, ACTION_REFRESH, 15))
            } else {
                views.setTextViewText(R.id.empty, "Tap here to sign in to Ready")
                views.setOnClickPendingIntent(R.id.empty, activityIntent(context, SetupActivity::class.java, 16))
            }

            views.setRemoteAdapter(R.id.list, items(context, store))
            return views
        }

        private fun statusLine(context: Context, store: Store): String {
            if (!store.signedIn) return "Not signed in"
            if (refreshing) return "Refreshing…"
            if (Updater.updateAvailable(context)) return "Update available: tap Ready"
            store.lastError?.let { return "⚠ $it" }
            if (store.updatedAt == 0L) return store.userName ?: ""
            return "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(store.updatedAt))
        }

        private fun items(context: Context, store: Store): RemoteViews.RemoteCollectionItems {
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(4)
            if (!store.signedIn) return builder.build()

            val todosJson = store.cachedTodos?.let { JSONObject(it) }
            val issuesJson = store.cachedIssues?.let { JSONObject(it) }
            if (todosJson == null && issuesJson == null) return builder.build()

            addTodos(context, store, builder, todosJson)
            addIssues(context, store, builder, issuesJson)
            return builder.build()
        }

        private fun addTodos(
            context: Context,
            store: Store,
            builder: RemoteViews.RemoteCollectionItems.Builder,
            json: JSONObject?,
        ) {
            val tagCodes = HashMap<String, String>()
            json?.optJSONArray("tags")?.let { tags ->
                for (i in 0 until tags.length()) {
                    val tag = tags.getJSONObject(i)
                    val key = tag.str("key") ?: continue
                    tagCodes[key] = tag.str("code") ?: continue
                }
            }

            val todos = json?.optJSONArray("todos")
            val count = todos?.length() ?: 0
            var open = 0
            for (i in 0 until count) if (!Todos.isDone(todos!!.getJSONObject(i))) open++

            val collapsed = store.isCollapsed(KEY_TODOS)
            builder.addItem(stableId("h", KEY_TODOS), header(context, R.layout.row_header, KEY_TODOS, "To-dos", open.toString(), collapsed))
            if (collapsed) return
            if (count == 0) {
                builder.addItem(stableId("m", KEY_TODOS), message(context, "No to-dos. Tap + To-do to add one."))
                return
            }
            for (i in 0 until count) {
                val todo = todos!!.getJSONObject(i)
                val done = Todos.isDone(todo)
                val pending = Todos.isPendingProposal(todo)
                val tagKey = when {
                    todo.optBoolean("personal") -> "personal"
                    todo.str("role_uuid") != null -> "role:" + todo.str("role_uuid")
                    else -> null
                }
                val meta = listOfNotNull(
                    if (pending) "proposed" else null,
                    todo.str("due_date")?.let { shortDate(it) },
                    tagKey?.let { tagCodes[it] },
                ).joinToString(" · ")
                val bullet = when {
                    pending -> "◇"
                    done -> "☑"
                    else -> "☐"
                }
                val row = item(context, bullet, todo.str("title") ?: "", meta, done)
                todo.str("uuid")?.let { uuid ->
                    val openIt = Intent().putExtra(EXTRA_TODO, uuid)
                    row.setOnClickFillInIntent(R.id.row_root, openIt)
                    // The box marks it done; a proposal has to be accepted first, so its box opens it instead.
                    val check = if (pending) openIt else Intent().putExtra(EXTRA_DONE, uuid).putExtra(EXTRA_DONE_VALUE, !done)
                    row.setOnClickFillInIntent(R.id.bullet, check)
                }
                if (todo.optInt("indent", 0) == 1) row.setViewPadding(R.id.row_root, dp(context, 22), dp(context, 5), dp(context, 10), dp(context, 5))
                builder.addItem(stableId("t", todo.str("uuid") ?: "$i"), row)
            }
        }

        private fun addIssues(
            context: Context,
            store: Store,
            builder: RemoteViews.RemoteCollectionItems.Builder,
            json: JSONObject?,
        ) {
            // The lists I belong to (my departments and meetings), with the issues still open on each.
            val lists = json?.optJSONArray("lists")
            data class Open(val key: String, val name: String, val issues: List<JSONObject>)
            val open = ArrayList<Open>()
            if (lists != null) {
                for (i in 0 until lists.length()) {
                    val list = lists.getJSONObject(i)
                    if (!list.optBoolean("member")) continue
                    val issues = list.optJSONArray("issues") ?: continue
                    val waiting = (0 until issues.length()).map { issues.getJSONObject(it) }
                        .filter { it.isNull("resolution") }
                    if (waiting.isNotEmpty()) {
                        open.add(Open(list.str("key") ?: "$i", list.str("name") ?: "List", waiting))
                    }
                }
            }

            val total = open.sumOf { it.issues.size }
            val collapsed = store.isCollapsed(KEY_ISSUES)
            builder.addItem(stableId("h", KEY_ISSUES), header(context, R.layout.row_header, KEY_ISSUES, "Issues", total.toString(), collapsed))
            if (collapsed) return
            if (open.isEmpty()) {
                builder.addItem(stableId("m", KEY_ISSUES), message(context, "No open issues on your lists."))
                return
            }
            for (list in open) {
                val listKey = "list:" + list.key
                val listCollapsed = store.isCollapsed(listKey)
                builder.addItem(stableId("h", listKey), header(context, R.layout.row_sub, listKey, list.name, list.issues.size.toString(), listCollapsed))
                if (listCollapsed) continue
                for (issue in list.issues) {
                    val notes = issue.optJSONArray("notes")?.length() ?: 0
                    val meta = when {
                        issue.optBoolean("unseen") -> "new"
                        issue.optInt("unread_notes", 0) > 0 -> "${issue.optInt("unread_notes")} unread"
                        notes > 0 -> "$notes note" + (if (notes == 1) "" else "s")
                        else -> ""
                    }
                    val row = item(context, "•", issue.str("title") ?: "", meta, false)
                    issue.str("uuid")?.let { row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(EXTRA_ISSUE, it)) }
                    builder.addItem(stableId("i", issue.str("uuid") ?: issue.toString()), row)
                }
            }
        }

        private fun header(context: Context, layout: Int, key: String, label: String, count: String, collapsed: Boolean): RemoteViews {
            val row = RemoteViews(context.packageName, layout)
            row.setTextViewText(R.id.chevron, if (collapsed) "▸" else "▾")
            row.setTextViewText(R.id.label, label)
            row.setTextViewText(R.id.count, count)
            row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(EXTRA_TOGGLE, key))
            return row
        }

        private fun item(context: Context, bullet: String, text: String, meta: String, struck: Boolean): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.row_item)
            row.setTextViewText(R.id.bullet, bullet)
            if (struck) {
                val s = SpannableString(text)
                s.setSpan(StrikethroughSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                row.setTextViewText(R.id.text, s)
            } else {
                row.setTextViewText(R.id.text, text)
            }
            row.setTextViewText(R.id.meta, meta)
            return row
        }

        private fun message(context: Context, text: String): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.row_message)
            row.setTextViewText(R.id.text, text)
            return row
        }

        /** "2026-10-21" → "Oct 21". */
        private fun shortDate(iso: String): String {
            val parts = iso.split("-")
            if (parts.size != 3) return iso
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val m = parts[1].toIntOrNull() ?: return iso
            val d = parts[2].toIntOrNull() ?: return iso
            return if (m in 1..12) "${months[m - 1]} $d" else iso
        }

        private fun stableId(kind: String, key: String): Long = (kind + ":" + key).hashCode().toLong()

        private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        private fun activityIntent(context: Context, cls: Class<*>, requestCode: Int): PendingIntent {
            val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, ReadyWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
