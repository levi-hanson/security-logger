# Ready Widgets (Android)

A home-screen widget for Ready that shows **your to-dos** and **your issues** in two
collapsible sections, with buttons to add a to-do or raise an issue.

- **To-dos**: your active list from `GET /v1/todos`, in your order. Done ones are struck
  through, and each shows its tag code (`P` for Personal, `OM` for Operations Manager, …)
  and due date.
- **Issues**: open issues on the lists you belong to (your departments and meetings) from
  `GET /v1/issues`, grouped by list. Each list collapses too.
- **+ To-do**: `POST /v1/todos`. **Personal** is ticked by default; untick it to leave
  the to-do untagged.
- **+ Issue**: `POST /v1/issues` onto any list you can raise on. The app remembers the
  last list you picked.
- Tap a to-do or an issue to edit it: change the title, read its notes, or add one. For a
  to-do, also change its tag: Personal, one of your roles, or none.
- Tap a section header to collapse or expand it. To-dos start open; Issues and each
  issue list start collapsed. Tap ↻ to refresh. Tap "Ready" to open
  settings. The widget also refreshes on its own every 30 minutes.

Needs Android 12 or newer.

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
