package io.github.nwcnwc.askbutton.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required by the voice-interaction manifest. Speech in the sheet uses the
 * platform SpeechRecognizer, not this stub.
 */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
