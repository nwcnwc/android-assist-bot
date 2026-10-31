package io.github.nwcnwc.askbutton.ask

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.nwcnwc.askbutton.R
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.net.SendService
import io.github.nwcnwc.askbutton.net.Sender
import java.util.concurrent.atomic.AtomicBoolean

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
    private val card = root.findViewById<View>(R.id.card)
    private val main = Handler(Looper.getMainLooper())
    private var filling = false
    private var userEdited = false
    private var sending = false
    private var queued = false
    private var baseBottom = -1
    private val startedAt = SystemClock.elapsedRealtime()
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyPad() }

    fun start() {
        baseBottom = card.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyPad()
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.requestApplyInsets()
        applyPad()
        root.setOnClickListener { if (!sending) onClose() }
        card.setOnClickListener { }
        root.findViewById<Button>(R.id.cancel).setOnClickListener { if (!sending) onClose() }
        send.setOnClickListener { submit() }
        question.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!filling) userEdited = true
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.mic_needed)
            return
        }
        val started = SpeechHub.start(context, listener)
        status.setText(if (started) R.string.listening else R.string.voice_unavailable)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
        SpeechHub.stop()
    }

    private fun applyPad() {
        if (baseBottom < 0) return
        val extra = SheetInsets.extraBottom(card)
        val target = baseBottom + extra
        if (card.paddingBottom != target) {
            card.setPadding(card.paddingLeft, card.paddingTop, card.paddingRight, target)
        }
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
        val questionText = question.text?.toString().orEmpty()
        val screenText = source.screenText
        val pageUrl = source.pageUrl
        val urls = ArrayList(source.urls)
        val shot = source.screenshot?.takeUnless { it.isRecycled }
        val clipboard = Sender.peekClipboard(context)
        SpeechHub.stop()
        val app = context.applicationContext
        val closed = AtomicBoolean(false)
        fun closeSheet() {
            if (closed.compareAndSet(false, true)) onClose()
        }
        // If encoding or service start stalls, leave the sheet anyway.
        main.postDelayed({ closeSheet() }, 1500)
        Thread {
            val jpeg = try {
                if (shot == null) null else Sender.writeJpeg(app, shot)
            } catch (error: Throwable) {
                Diagnostics.log(app, "jpeg", error)
                null
            }
            SendService.enqueue(
                app,
                SendService.intent(app, questionText, screenText, pageUrl, urls, clipboard, jpeg),
            )
            main.post { closeSheet() }
        }.start()
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
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.mic_needed
                    android.speech.SpeechRecognizer.ERROR_NETWORK,
                    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    -> R.string.voice_offline
                    else -> R.string.voice_try_typing
                },
            )
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                status.setText(R.string.voice_try_typing)
                return
            }
            if (!userEdited) showHeard(text)
            if (!userEdited) submit()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (userEdited || sending) return
            val text = partialResults
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) showHeard(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun showHeard(text: String) {
        filling = true
        question.setText(text)
        question.setSelection(text.length)
        filling = false
    }
}
