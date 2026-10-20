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
        /** Open [CloseIssueActivity] for this issue. */
        const val EXTRA_CLOSE_ISSUE = "close_issue"
        const val EXTRA_DONE = "done"
        const val EXTRA_DONE_VALUE = "done_value"
        /** Archive every done to-do (POST /v1/todos/archive_done). */
        const val EXTRA_ARCHIVE_DONE = "archive_done"
        /** A person's uuid to open their list in [TeamActivity], or "" for the team list. */
        const val EXTRA_TEAM = "team"
        /** Open Ready in the browser. */
        const val EXTRA_OPEN_READY = "open_ready"
        /** Open this WIP's page in Ready (/wip/edit/{uuid}). */
        const val EXTRA_OPEN_WIP = "open_wip"

        private const val KEY_TODOS = "todos"
        private const val KEY_ISSUES = "issues"
        private const val KEY_DELEGATED = "delegated"
        private const val KEY_TODOS_DONE = "todos:done"

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
                    val watching = Ready.get(context, "/v1/todos/watching")
                    store.cachedTodos = todos.toString()
                    store.cachedIssues = issues.toString()
                    store.cachedWatching = watching.toString()
                    // The inbox and escalations come from Ready's forms side; if one fails,
                    // keep the to-dos and issues that did load and show the error.
                    var partial: String? = null
                    try {
                        store.cachedInbox = Ready.get(context, "/v1/wips/home_view").toString()
                    } catch (e: Exception) {
                        partial = "Inbox: " + (e.message ?: e.javaClass.simpleName)
                    }
                    try {
                        store.cachedEscalations = Ready.get(
                            context, "/v1/forms?filter%5Bq%5D=escalation&filter%5Bstatus%5D=wip&page%5Bsize%5D=50",
                        ).toString()
                    } catch (e: Exception) {
                        partial = "Escalations: " + (e.message ?: e.javaClass.simpleName)
                    }
                    store.updatedAt = System.currentTimeMillis()
                    store.lastError = partial
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

            addEscalations(context, store, builder, store.cachedEscalations?.let { JSONObject(it) })
            addTodos(context, store, builder, todosJson)
            addInbox(context, store, builder, store.cachedInbox?.let { JSONObject(it) })
            addIssues(context, store, builder, issuesJson)
            addDelegated(context, store, builder, store.cachedWatching?.let { JSONObject(it) })
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
            fun addRow(todo: JSONObject, i: Int) {
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

            // Ready keeps done to-dos on the list until they're archived, so they move to a
            // collapsed Done group here, where they can be unticked or cleared for good.
            val all = (0 until count).map { todos!!.getJSONObject(it) }
            val doneOnes = all.filter { Todos.isDone(it) }
            all.forEachIndexed { i, todo -> if (!Todos.isDone(todo)) addRow(todo, i) }
            if (open == 0) builder.addItem(stableId("m", "alldone"), message(context, "All done."))
            if (doneOnes.isEmpty()) return

            val doneCollapsed = store.isCollapsed(KEY_TODOS_DONE)
            builder.addItem(stableId("h", KEY_TODOS_DONE), header(context, R.layout.row_sub, KEY_TODOS_DONE, "Done", doneOnes.size.toString(), doneCollapsed))
            if (doneCollapsed) return
            doneOnes.forEachIndexed { i, todo -> addRow(todo, count + i) }
            val clear = message(context, "Clear done →")
            clear.setOnClickFillInIntent(R.id.text, Intent().putExtra(EXTRA_ARCHIVE_DONE, true))
            builder.addItem(stableId("m", "cleardone"), clear)
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
                    val row = item(context, "○", issue.str("title") ?: "", meta, false)
                    issue.str("uuid")?.let {
                        row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(EXTRA_ISSUE, it))
                        // The circle closes it: resolve or discard.
                        row.setOnClickFillInIntent(R.id.bullet, Intent().putExtra(EXTRA_CLOSE_ISSUE, it))
                    }
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

        /**
         * Open escalations (Escalation Form WIPs not yet archived), at the very top. Only shown
         * when there are any, since they need attention now.
         */
        private fun addEscalations(context: Context, store: Store, builder: RemoteViews.RemoteCollectionItems.Builder, json: JSONObject?) {
            val forms = json?.optJSONArray("data") ?: return
            val open = (0 until forms.length()).map { forms.getJSONObject(it) }.filter {
                val a = it.optJSONObject("attributes")
                a != null && a.str("kind") == "wip" &&
                    a.optJSONObject("form_type")?.str("name")?.contains("Escalation", ignoreCase = true) == true
            }
            if (open.isEmpty()) return
            val collapsed = store.isCollapsed("escalations")
            builder.addItem(stableId("h", "escalations"), header(context, R.layout.row_header, "escalations", "⚠ Escalations", open.size.toString(), collapsed))
            if (collapsed) return
            for (form in open) {
                val a = form.getJSONObject("attributes")
                val name = (a.str("name") ?: "Escalation").removePrefix("Escalation Form").trim()
                val owner = a.optJSONObject("owner")?.str("name")?.substringBefore(' ')
                val row = item(context, "!", name.ifEmpty { "Escalation" }, owner ?: "", false)
                row.setOnClickFillInIntent(R.id.row_root, openWip(form.str("id")))
                builder.addItem(stableId("e", form.str("id") ?: name), row)
            }
        }

        /**
         * My Ready inbox, as on Ready's home page: what's waiting on me (inbox, for my role,
         * returned drafts, shift requests, to-do notifications), then what's in flight.
         */
        private fun addInbox(context: Context, store: Store, builder: RemoteViews.RemoteCollectionItems.Builder, json: JSONObject?) {
            json ?: return
            val wips = json.optJSONObject("wips") ?: JSONObject()
            val buckets = listOf(
                "inbox" to "Inbox",
                "for_your_role" to "For your role",
                "returned_drafts" to "Returned drafts",
                "shift_requests" to "Shift requests",
            )
            val notifications = json.optJSONArray("todo_notifications")
            val waiting = buckets.sumOf { wips.optJSONArray(it.first)?.length() ?: 0 } + (notifications?.length() ?: 0)

            val collapsed = store.isCollapsed("inbox")
            builder.addItem(stableId("h", "inbox"), header(context, R.layout.row_header, "inbox", "Inbox", waiting.toString(), collapsed))
            if (collapsed) return

            for (i in 0 until (notifications?.length() ?: 0)) {
                val n = notifications!!.getJSONObject(i)
                val title = n.optJSONObject("todo")?.str("title") ?: n.str("title") ?: "A to-do"
                val what = when (n.str("kind")) {
                    "completed" -> "done"
                    "declined" -> "declined"
                    "deleted" -> "deleted"
                    else -> n.str("kind") ?: ""
                }
                val row = item(context, "✉", title, what, false)
                row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(EXTRA_OPEN_READY, true))
                builder.addItem(stableId("n", n.str("uuid") ?: "$i"), row)
            }
            for ((key, label) in buckets + ("in_flight" to "In flight")) {
                val items = wips.optJSONArray(key) ?: continue
                if (items.length() == 0) continue
                val subKey = "inbox:$key"
                // The main inbox bucket needs no subheading when it's the only thing waiting.
                val showSub = !(key == "inbox" && waiting == items.length())
                val subCollapsed = showSub && store.isCollapsed(subKey)
                if (showSub) {
                    builder.addItem(stableId("h", subKey), header(context, R.layout.row_sub, subKey, label, items.length().toString(), subCollapsed))
                }
                if (subCollapsed) continue
                for (i in 0 until items.length()) {
                    val wip = items.getJSONObject(i)
                    val due = wip.str("due_date")?.take(10)?.let { shortDate(it) }
                    val meta = listOfNotNull(wip.str("department"), due?.let { "due $it" }).joinToString(" · ")
                    val row = item(context, "•", wip.str("save_name") ?: wip.str("name") ?: "Form", meta, false)
                    row.setOnClickFillInIntent(R.id.row_root, openWip(wip.str("uuid")))
                    builder.addItem(stableId("w", wip.str("uuid") ?: "$key$i"), row)
                }
            }
            if (waiting == 0 && (wips.optJSONArray("in_flight")?.length() ?: 0) == 0) {
                builder.addItem(stableId("m", "inbox"), message(context, "Nothing waiting on you."))
            }
        }

        /** Opens the WIP's page in Ready, or Ready's home page when its id is missing. */
        private fun openWip(uuid: String?): Intent =
            if (uuid != null) Intent().putExtra(EXTRA_OPEN_WIP, uuid) else Intent().putExtra(EXTRA_OPEN_READY, true)

        /** To-dos on other people's lists that I watch, including the ones I proposed. */
        private fun addDelegated(
            context: Context,
            store: Store,
            builder: RemoteViews.RemoteCollectionItems.Builder,
            json: JSONObject?,
        ) {
            val todos = json?.optJSONArray("todos")
            val count = todos?.length() ?: 0
            var open = 0
            for (i in 0 until count) if (!Todos.isDone(todos!!.getJSONObject(i))) open++

            val collapsed = store.isCollapsed(KEY_DELEGATED)
            builder.addItem(stableId("h", KEY_DELEGATED), header(context, R.layout.row_header, KEY_DELEGATED, "Delegated", open.toString(), collapsed))
            if (collapsed) return
            if (count == 0) {
                builder.addItem(stableId("m", KEY_DELEGATED), message(context, "Nothing delegated. Propose a to-do with + To-do."))
            }
            for (i in 0 until count) {
                val todo = todos!!.getJSONObject(i)
                val done = Todos.isDone(todo)
                val owner = todo.optJSONObject("owner")
                val meta = listOfNotNull(
                    owner?.str("name")?.substringBefore(' '),
                    if (Todos.isPendingProposal(todo)) "proposed" else null,
                    todo.str("due_date")?.let { shortDate(it) },
                ).joinToString(" · ")
                val bullet = when {
                    done -> "☑"
                    Todos.isPendingProposal(todo) -> "◇"
                    else -> "☐"
                }
                val row = item(context, bullet, todo.str("title") ?: "", meta, done)
                val person = owner?.str("uuid") ?: todo.str("owner_uuid") ?: ""
                row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(EXTRA_TEAM, person))
                builder.addItem(stableId("d", todo.str("uuid") ?: "$i"), row)
            }
            val team = message(context, "View team →")
            team.setOnClickFillInIntent(R.id.text, Intent().putExtra(EXTRA_TEAM, ""))
            builder.addItem(stableId("m", "team"), team)
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
