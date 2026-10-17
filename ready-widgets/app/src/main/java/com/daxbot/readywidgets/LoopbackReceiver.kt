package com.daxbot.readywidgets

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Catches the browser's return from Ready's sign-in page on http://127.0.0.1 (Ready only
 * accepts https or loopback redirects), finishes the sign-in, and shows a page with a button
 * back to the app.
 */
object LoopbackReceiver {
    const val PORT = 47613
    private const val WAIT_MILLIS = 10 * 60 * 1000

    @Volatile private var server: ServerSocket? = null

    /** Starts listening; call before opening the browser. [onDone] runs on a background thread. */
    fun start(context: Context, onDone: (name: String?, error: Exception?) -> Unit) {
        stop()
        val app = context.applicationContext
        val socket = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = WAIT_MILLIS
        server = socket
        thread {
            try {
                var finished = false
                while (!finished) {
                    socket.accept().use { client -> finished = handle(app, client, onDone) }
                }
            } catch (e: IOException) {
                // Timed out waiting, or stopped for a newer sign-in.
            } finally {
                socket.close()
                if (server === socket) server = null
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    /** Returns true once the sign-in callback has been handled. */
    private fun handle(context: Context, client: Socket, onDone: (String?, Exception?) -> Unit): Boolean {
        val requestLine = client.getInputStream().bufferedReader().readLine() ?: return false
        val target = requestLine.split(" ").getOrNull(1) ?: ""
        if (!target.startsWith("/oauth")) {
            respond(client, "404 Not Found", "Not found")
            return false
        }
        try {
            val name = Ready.finishSignIn(context, Uri.parse("http://127.0.0.1$target"))
            ReadyWidgetProvider.refresh(context)
            respond(client, "200 OK", page("Signed in", "Signed in to Ready as ${TextUtils.htmlEncode(name)}."))
            onDone(name, null)
        } catch (e: Exception) {
            val message = TextUtils.htmlEncode(e.message ?: e.javaClass.simpleName)
            respond(client, "200 OK", page("Sign-in failed", "Sign-in failed: $message"))
            onDone(null, e)
        }
        return true
    }

    private fun page(title: String, message: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
        <style>
          body { font-family: sans-serif; padding: 32px 20px; text-align: center; color: #1f1f1c; background: #f7f7f5; }
          a { display: inline-block; margin-top: 20px; padding: 14px 22px; border-radius: 10px;
              background: #0f6e56; color: #fff; text-decoration: none; font-weight: 600; }
        </style></head>
        <body><p>$message</p><a href="com.daxbot.readywidgets://done">Back to Ready Widgets</a></body></html>
    """.trimIndent()

    private fun respond(client: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
