# Ready Widgets (Android)

A home-screen widget for Ready that shows **your to-dos** and **your issues** in two
collapsible sections, with buttons to add a to-do or raise an issue.

- **To-dos**: your active list from `GET /v1/todos`, grouped by Ready's planned day:
  **Today** (open), each coming day, **Later**, then each project's unscheduled to-dos (all
  collapsed). The header counts today's. Move a to-do with the **Day** picker on its edit
  screen (`PATCH planned_date`, or `unscheduled` to send it back to its project), and pick
  **When** while adding one. Within a group they keep your order. Done ones are struck
  through, and each shows its tag code (`P` for Personal, `OM` for Operations Manager, …)
  and due date.
- **Issues**: open issues on the lists you belong to (your departments and meetings) from
  `GET /v1/issues`, grouped by list. Each list collapses too.
- **+ To-do**: for **Me** (`POST /v1/todos`), Personal by default; or pick someone below you
  to **propose** it to them (`POST /v1/todos/propose`, with an optional note).
- **⚠ Escalations** (top, only when there are any): open Escalation Form WIPs, from
  `GET /v1/forms?filter[q]=escalation&filter[status]=wip`.
- **Inbox**: what Ready's home page shows waiting on you (`GET /v1/wips/home_view`): your
  inbox, for your role, returned drafts, shift requests and to-do notifications, plus what's
  in flight (collapsed). Tapping an inbox item or escalation opens its page in Ready
  (`/wip/edit/{uuid}`).
- **Delegated**: to-dos on other people's lists that you watch, including the ones you
  proposed (`GET /v1/todos/watching`), with whose they are. Tap one to open that person's
  list. **View team →** opens your team: search it, open anyone's list
  (`GET /v1/todos?user=`, which never includes their Personal to-dos), and propose to them.
- **+ Issue**: `POST /v1/issues` onto any list you can raise on. The app remembers the
  last list you picked.
- Tap a to-do's box to mark it done; it moves to a collapsed **Done** group (Ready keeps
  done to-dos until they're archived). Untick it there to undo, or tap **Clear done** to
  archive them all in Ready (`POST /v1/todos/archive_done`).
- A to-do a supervisor proposed shows ◇ instead of a box; tap it to accept or decline it,
  since Ready only lets you complete it once accepted.
- Tap an issue's ○ to close it, Ready's way: **resolve** it (the root issue plus at least one
  solution to-do, each on your list or someone's on that list; `POST /v1/issues/{uuid}/resolve`)
  or **discard** it (a duplicate, or with a reason; `POST /v1/issues/{uuid}/discard`). Ready lets
  you undo either for 5 minutes on its Issues page.
- Tap a to-do or an issue to edit it: change the title, read its notes, or add one. For a
  to-do, also change its tag: Personal, one of your roles, or none.
- Tap a section header to collapse or expand it. To-dos start open; Issues and each
  issue list start collapsed. Tap ↻ to refresh. Tap "Ready" to open
  settings. The widget also refreshes on its own every 30 minutes.

Needs Android 12 or newer.

## Captain's log

Voice log entries that land in a Google Sheet, one row each.

- **Record:** tap the **Captain's log** quick-settings tile (pull down from the top of the
  screen; it works on the lock screen without unlocking), the **🎙** button on the widget,
  or long-press the app icon → **Captain's log**. Speak, then tap **Stop & save**. Leaving
  the screen also saves what was heard; **Discard** throws it away.
- Android's speech recognizer pauses after a few seconds of quiet; the app restarts it until
  you tap Stop, so you may hear a short tone between stretches.
- **No signal:** entries wait on the phone and send themselves once there's a connection
  (`LogSyncJob`). The settings screen shows how many are waiting, with **Send waiting entries**.
- Each entry carries an id, and the sheet skips an id it already has, so a resend never
  adds a duplicate row.

### Set up the sheet (once)

1. Make a Google Sheet (for example "Captain's Log").
2. In it, open **Extensions → Apps Script**, replace the contents with
   [`captains-log/Code.gs`](captains-log/Code.gs), and save.
3. **Deploy → New deployment**, type **Web app**, *Execute as* **Me**, *Who has access*
   **Anyone**. Authorize it when asked, then copy the **Web app URL** (ends in `/exec`).
4. In the Ready Widgets app, under **Captain's log**, paste the URL and tap **Save address**.
   Tap **Add the quick-settings tile** to put the tile in quick settings.

The first entry creates a **Log** tab with When · Entry · Source columns. Anyone with the
web app URL can add rows to it, so keep the URL to yourself.

## Install

1. On your phone, open this repo's **Releases** and find the `Ready Widgets (<branch>)`
   pre-release. Download `ready-widgets.apk`. (Each push to `ready-widgets/` rebuilds it;
   the APK is also attached to every run under **Actions → Ready Widgets APK**.)
2. Open the file. If Android asks, allow your browser to install apps.
3. Open **Ready Widgets**, enter the address you use for Ready in a browser, and tap
   **Sign in**. Approve the app on Ready's page, which then sends you back to the app.
4. Long-press the home screen → **Widgets** → **Ready Widgets**, and drag it out.

## How sign-in works

The app uses Ready's OAuth server the same way other connected clients (like the Apps
Script tools) do:

1. It reads `/.well-known/oauth-authorization-server` from your Ready address. If that's
   missing, it falls back to `/oauth/authorize`, `/oauth/token` and `/oauth/register`.
2. It registers itself once as a public client named "Ready Widgets (Android)" with
   redirect URI `http://127.0.0.1:47613/oauth`. Ready only accepts https or loopback
   redirects, so the app listens on that port on the phone itself while you sign in.
3. It opens Ready's sign-in page (authorization code + PKCE). When you approve, the browser
   returns to the app's loopback address. The app exchanges the code for tokens and shows a
   "Back to Ready Widgets" button. It refreshes the access token when it expires.

The session then appears in Ready's list of connected clients, where you can revoke it.
Tokens are stored in the app's private storage on the phone.

## Build locally

Needs JDK 17 and the Android SDK (platform 35).

```sh
cd ready-widgets
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Updates

Every push to `ready-widgets/` publishes a new build to the release. The app checks the
release every few hours. When a newer build is out, the widget's status line says so.
Open the app (tap "Ready" on the widget), tap **Install update**, and confirm Android's
prompt.

Updates only install over a build signed with the same key. CI signs with the keystore in
the `READY_KEYSTORE_BASE64` repository secret: a base64-encoded PKCS12 keystore with alias
`readywidgets` and password `readywidgets`. Without that secret, builds use a throwaway
debug key and must be uninstalled and reinstalled by hand.
