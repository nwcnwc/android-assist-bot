package io.github.nwcnwc.askbutton.ask

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.nwcnwc.askbutton.diag.Diagnostics
import java.util.Locale

/**
 * One recognizer for the process. Destroying a recognizer from inside its own
 * callback crashes Samsung's speech service, and a second create() while the
 * first is still alive does the same. Callbacks never destroy inline.
 */
object SpeechHub {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pendingDestroy: SpeechRecognizer? = null
    private var generation = 0
    private var inCallback = false
    private var onDeviceBroken = false
    private var app: Context? = null

    fun start(context: Context, listener: RecognitionListener): Boolean {
        checkMain()
        app = context.applicationContext
        flushPending()
        closeNow()
        val created = create(context) ?: return false
        val token = ++generation
        recognizer = created
        created.setRecognitionListener(Guard(token, listener))
        return try {
            created.startListening(intent(context))
            true
        } catch (error: Throwable) {
            Diagnostics.log(context, "speech start", error)
            closeNow()
            false
        }
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        generation++
        val current = recognizer ?: return
        recognizer = null
        if (inCallback) {
            pendingDestroy = current
            main.post { flushPending() }
        } else {
            destroy(current)
        }
    }

    private fun flushPending() {
        val pending = pendingDestroy ?: return
        pendingDestroy = null
        destroy(pending)
    }

    private fun closeNow() {
        generation++
        val current = recognizer ?: return
        recognizer = null
        destroy(current)
    }

    private fun destroy(recognizer: SpeechRecognizer) {
        try {
            recognizer.cancel()
        } catch (error: Throwable) {
            note("speech cancel", error)
        }
        try {
            recognizer.destroy()
        } catch (error: Throwable) {
            note("speech destroy", error)
        }
    }

    private fun note(message: String, error: Throwable) {
        val context = app ?: return
        Diagnostics.log(context, message, error)
    }

    private fun create(context: Context): SpeechRecognizer? {
        return try {
            if (!onDeviceBroken &&
                Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (error: Throwable) {
                    onDeviceBroken = true
                    Diagnostics.log(context, "on-device speech unavailable", error)
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (error: Throwable) {
            Diagnostics.log(context, "speech create", error)
            null
        }
    }

    private fun intent(context: Context): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechHub runs on the main thread" }
    }

    private class Guard(
        private val token: Int,
        private val listener: RecognitionListener,
    ) : RecognitionListener {
        private fun run(block: () -> Unit) {
            if (token != generation || recognizer == null) return
            inCallback = true
            try {
                block()
            } catch (error: Throwable) {
                app?.let { Diagnostics.log(it, "speech callback", error) }
            } finally {
                inCallback = false
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = run { listener.onReadyForSpeech(params) }
        override fun onBeginningOfSpeech() = run { listener.onBeginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = run { listener.onRmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = run { listener.onBufferReceived(buffer) }
        override fun onEndOfSpeech() = run { listener.onEndOfSpeech() }
        override fun onError(error: Int) = run { listener.onError(error) }
        override fun onResults(results: Bundle?) = run { listener.onResults(results) }
        override fun onPartialResults(partialResults: Bundle?) = run { listener.onPartialResults(partialResults) }
        override fun onEvent(eventType: Int, params: Bundle?) = run { listener.onEvent(eventType, params) }
    }
}
