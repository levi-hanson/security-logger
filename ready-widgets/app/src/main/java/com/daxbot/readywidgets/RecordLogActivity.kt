package com.daxbot.readywidgets

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Record a captain's log entry: opens listening, shows the words as they come, and saves on
 * Stop. Opened from the quick-settings tile (also over the lock screen), the widget's Log
 * button, or the app icon's long-press menu. Android's recognizer stops after a pause, so
 * it is restarted until Stop is pressed, and each stretch is added to the entry.
 */
class RecordLogActivity : Activity() {
    private lateinit var text: EditText
    private lateinit var status: TextView
    private lateinit var stop: Button

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var committed = ""
    private var partial = ""
    private var listening = false
    private var stopping = false
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTitle("Captain's log")
        setContentView(R.layout.activity_record_log)

        text = findViewById(R.id.entry)
        status = findViewById(R.id.status)
        stop = findViewById(R.id.stop)
        findViewById<TextView>(R.id.when_label).text =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
        findViewById<Button>(R.id.cancel).setOnClickListener { cancel() }
        stop.setOnClickListener { onStopPressed() }

        when {
            !SpeechRecognizer.isRecognitionAvailable(this) -> typeInstead("Voice typing isn't available on this phone. Type the entry, or use your keyboard's mic.")
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else typeInstead("Microphone access is off. Type the entry, or allow the microphone in the app's settings.")
    }

    private fun startListening() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        listening = true
        text.isFocusable = false
        stop.text = "Stop & save"
        status.text = "Listening…"
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                // Hints only; many phones still end a stretch after a couple of seconds of quiet.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L),
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!stopping) status.text = "Listening… tap Stop & save when you're done."
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partial = best(partialResults)
            show()
        }

        override fun onResults(results: Bundle?) {
            val heard = best(results).ifEmpty { partial }
            if (heard.isNotEmpty()) committed = join(committed, heard)
            partial = ""
            show()
            if (stopping) save() else if (listening) restartSoon()
        }

        override fun onError(error: Int) {
            if (partial.isNotEmpty()) committed = join(committed, partial)
            partial = ""
            show()
            if (stopping) {
                save()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (listening) restartSoon()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) restartSoon(600)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    typeInstead("Microphone access is off. Type the entry, or allow the microphone in the app's settings.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    typeInstead("Voice typing needs a connection right now. Type the rest, or use your keyboard's mic, then Save.")
                else -> typeInstead("Listening stopped (error $error). Finish by typing, then Save.")
            }
        }
    }

    private fun restartSoon(delay: Long = 150) {
        handler.postDelayed({ if (listening && !stopping && !isFinishing) startListening() }, delay)
    }

    private fun onStopPressed() {
        if (!listening) {
            committed = text.text.toString()
            save()
            return
        }
        stopping = true
        listening = false
        stop.isEnabled = false
        status.text = "Saving…"
        recognizer?.stopListening()
        // If the recognizer never reports back, save what was heard.
        handler.postDelayed({ save() }, 2500)
    }

    /** Leaves listening and lets the person finish by typing. Keeps what was heard. */
    private fun typeInstead(message: String) {
        listening = false
        recognizer?.cancel()
        status.text = message
        stop.text = "Save"
        stop.isEnabled = true
        text.isFocusableInTouchMode = true
        text.isFocusable = true
        text.setText(committed)
        text.setSelection(text.text.length)
        text.requestFocus()
    }

    private fun save() {
        if (saved) return
        saved = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        val entry = join(committed, partial).trim()
        if (entry.isEmpty()) {
            Toast.makeText(this, "Nothing heard, so nothing was logged.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        CaptainsLog.add(this, entry, if (text.isFocusable) "typed" else "voice")
        val note = if (CaptainsLog.endpoint(this) == null) {
            "Saved on the phone. Add your sheet's address in Ready Widgets to send it."
        } else {
            "Logged."
        }
        Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun cancel() {
        saved = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        finish()
    }

    /** Leaving mid-entry (screen off, another app, Back) keeps what was said. */
    override fun onStop() {
        super.onStop()
        if (!saved && !isChangingConfigurations) {
            listening = false
            stopping = true
            if (text.isFocusable) committed = text.text.toString()
            save()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun show() {
        if (text.isFocusable) return
        text.setText(join(committed, partial))
        text.setSelection(text.text.length)
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun join(a: String, b: String): String = when {
        a.isBlank() -> b.trim()
        b.isBlank() -> a.trim()
        else -> a.trim() + " " + b.trim()
    }

    companion object {
        private const val REQUEST_MIC = 1
    }
}
