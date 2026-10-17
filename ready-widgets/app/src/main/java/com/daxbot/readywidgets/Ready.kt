package com.daxbot.readywidgets

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A small client for Ready's API. Signs in with OAuth (authorization code + PKCE, with the
 * app registering itself as a public client), keeps the token fresh, and calls /v1 as the
 * signed-in person. Every call blocks, so run them off the main thread.
 */
object Ready {
    const val REDIRECT_URI = "com.daxbot.readywidgets:/oauth"
    private const val CLIENT_NAME = "Ready Widgets (Android)"
    private const val SCOPE = "write"

    class ApiException(val status: Int, message: String) : IOException(message)

    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    // ---- Sign in ----

    /**
     * Discovers the server's OAuth endpoints, registers this app if needed, and returns the URL
     * of Ready's sign-in page. The browser comes back to [REDIRECT_URI] and [finishSignIn].
     */
    fun beginSignIn(context: Context, serverInput: String): Uri {
        val store = Store(context)
        val server = normalizeServer(serverInput)

        val meta = try {
            JSONObject(request("GET", "$server/.well-known/oauth-authorization-server").second)
        } catch (e: IOException) {
            JSONObject()
        }
        val authorize = meta.str("authorization_endpoint") ?: "$server/oauth/authorize"
        val token = meta.str("token_endpoint") ?: "$server/oauth/token"
        val register = meta.str("registration_endpoint") ?: "$server/oauth/register"

        if (store.clientId == null || store.clientServer != server) {
            val body = JSONObject()
                .put("client_name", CLIENT_NAME)
                .put("redirect_uris", JSONArray().put(REDIRECT_URI))
                .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none")
                .put("scope", SCOPE)
            val (status, text) = request("POST", register, body.toString(), "application/json")
            if (status !in 200..299) {
                throw ApiException(status, "Ready refused to register this app ($status): ${errorText(text)}")
            }
            store.clientId = JSONObject(text).str("client_id")
                ?: throw IOException("Ready's registration answer had no client_id")
            store.clientServer = server
        }

        store.serverUrl = server
        store.authorizationEndpoint = authorize
        store.tokenEndpoint = token

        val verifier = randomToken(48)
        val state = randomToken(16)
        store.pendingVerifier = verifier
        store.pendingState = state

        return Uri.parse(authorize).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", store.clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /** Exchanges the code Ready sent back for tokens. Returns the signed-in person's name. */
    fun finishSignIn(context: Context, callback: Uri): String {
        val store = Store(context)
        callback.getQueryParameter("error")?.let { error ->
            val detail = callback.getQueryParameter("error_description")
            throw IOException(if (detail != null) "$error: $detail" else error)
        }
        val code = callback.getQueryParameter("code") ?: throw IOException("Ready sent no authorization code")
        if (callback.getQueryParameter("state") != store.pendingState) {
            throw IOException("This sign-in did not start here; try again from the app")
        }
        val verifier = store.pendingVerifier ?: throw IOException("Sign-in expired; try again")

        tokenRequest(
            store,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to (store.clientId ?: ""),
            "code_verifier" to verifier,
        )
        store.pendingVerifier = null
        store.pendingState = null

        val name = get(context, "/v1/todos").optJSONObject("owner")?.str("name") ?: "you"
        store.userName = name
        return name
    }

    private fun refresh(store: Store) {
        val refreshToken = store.refreshToken ?: throw ApiException(401, "Signed out; open the app to sign in again")
        tokenRequest(
            store,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to (store.clientId ?: ""),
        )
    }

    private fun tokenRequest(store: Store, vararg params: Pair<String, String>) {
        val endpoint = store.tokenEndpoint ?: throw IOException("Not set up; open the app to sign in")
        val form = params.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val (status, text) = request("POST", endpoint, form, "application/x-www-form-urlencoded")
        if (status !in 200..299) {
            if (status == 400 || status == 401) store.accessToken = null
            throw ApiException(status, "Sign-in failed ($status): ${errorText(text)}")
        }
        val json = JSONObject(text)
        store.accessToken = json.str("access_token") ?: throw IOException("Ready sent no access token")
        json.str("refresh_token")?.let { store.refreshToken = it }
        val expiresIn = json.optLong("expires_in", 0L)
        store.accessTokenExpiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
    }

    // ---- API ----

    fun get(context: Context, path: String): JSONObject = call(context, "GET", path, null)

    fun post(context: Context, path: String, body: JSONObject): JSONObject = call(context, "POST", path, body)

    private fun call(context: Context, method: String, path: String, body: JSONObject?): JSONObject {
        val store = Store(context)
        val server = store.serverUrl ?: throw ApiException(401, "Not signed in; open the app to sign in")
        if (store.accessToken == null) refresh(store)
        val expiresAt = store.accessTokenExpiresAt
        if (expiresAt != 0L && System.currentTimeMillis() > expiresAt - 60_000 && store.refreshToken != null) {
            refresh(store)
        }

        var result = request(method, server + path, body?.toString(), "application/json", store.accessToken)
        if (result.first == 401 && store.refreshToken != null) {
            refresh(store)
            result = request(method, server + path, body?.toString(), "application/json", store.accessToken)
        }
        val (status, text) = result
        if (status !in 200..299) throw ApiException(status, "Ready answered $status: ${errorText(text)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    // ---- Plumbing ----

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        bearer: String? = null,
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(text: String): String {
        val json = try { JSONObject(text) } catch (e: Exception) { null }
        return json?.let {
            it.str("error_description") ?: it.str("message") ?: it.str("error")
        } ?: text.take(200).ifBlank { "no details" }
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

/** optString turns JSON null into the string "null"; this returns Kotlin null instead. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
