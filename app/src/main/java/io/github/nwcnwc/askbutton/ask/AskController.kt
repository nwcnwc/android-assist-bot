package io.github.nwcnwc.askbutton.ask

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.nwcnwc.askbutton.R
import io.github.nwcnwc.askbutton.SettingsActivity
import io.github.nwcnwc.askbutton.net.Sender
import io.github.nwcnwc.askbutton.notify.Notices
import java.util.Locale

class AskController(
    private val context: Context,
    private val root: View,
    private val source: Source,
    private val onClose: () -> Unit,
) {
    interface Source {
        val screenshot: Bitmap?
        val screenText: String
        val urls: List<String>
        val pageUrl: String?
        fun ready(): Boolean
    }

    private val question = root.findViewById<EditText>(R.id.question)
    private val status = root.findViewById<TextView>(R.id.status)
    private val send = root.findViewById<Button>(R.id.send)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var filling = false
    private var userEdited = false
    private var sending = false
    private var queued = false
    private val startedAt = SystemClock.elapsedRealtime()

    fun start() {
        root.setOnClickListener { if (!sending) onClose() }
        root.findViewById<View>(R.id.card).setOnClickListener { }
        root.findViewById<Button>(R.id.cancel).setOnClickListener { if (!sending) onClose() }
        send.setOnClickListener { submit() }
        question.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!filling) userEdited = true
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.mic_needed)
            return
        }
        startListening()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        releaseRecognizer()
    }

    private fun startListening() {
        val created = createRecognizer()
        if (created == null) {
            status.setText(R.string.voice_unavailable)
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        status.setText(R.string.listening)
        try {
            created.startListening(recognizeIntent())
        } catch (_: Exception) {
            status.setText(R.string.voice_unavailable)
            releaseRecognizer()
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (_: Exception) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun recognizeIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    private fun showHeard(text: String) {
        filling = true
        question.setText(text)
        question.setSelection(text.length)
        filling = false
    }

    private fun submit() {
        if (sending) return
        if (!source.ready() && SystemClock.elapsedRealtime() - startedAt < 1600) {
            if (!queued) {
                queued = true
                status.setText(R.string.waiting_screen)
            }
            main.postDelayed({ submit() }, 200)
            return
        }
        queued = false
        main.removeCallbacksAndMessages(null)
        sending = true
        send.isEnabled = false
        status.setText(R.string.sending)
        releaseRecognizer()
        Sender.send(
            context,
            question.text?.toString().orEmpty(),
            source.screenshot,
            source.screenText,
            source.pageUrl,
            source.urls,
        ) { outcome ->
            sending = false
            when (outcome) {
                is Sender.Outcome.Sent -> {
                    val message = if (outcome.imageNote == null) {
                        context.getString(R.string.sent)
                    } else {
                        context.getString(R.string.sent_without_image, outcome.imageNote)
                    }
                    Notices.sent(context, message)
                    onClose()
                }
                is Sender.Outcome.Failed -> {
                    send.isEnabled = true
                    status.text = outcome.message
                    Notices.problem(context, outcome.message)
                    if (outcome.message.contains("settings")) {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.cancel()
        } catch (_: Exception) {
        }
        try {
            current.destroy()
        } catch (_: Exception) {
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            status.setText(R.string.listening)
        }

        override fun onBeginningOfSpeech() {
            status.setText(R.string.hearing)
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            status.setText(R.string.recognizing)
        }

        override fun onError(error: Int) {
            if (sending) return
            status.setText(
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.mic_needed
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_offline
                    else -> R.string.voice_try_typing
                },
            )
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                status.setText(R.string.voice_try_typing)
                return
            }
            if (!userEdited) showHeard(text)
            if (!userEdited) submit()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (userEdited || sending) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) showHeard(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
