package io.github.nwcnwc.askbutton.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CallbackGateTest {
    @Test
    fun repeatedSpeechCallbacksEachRunOnceAndReentryIsIgnored() {
        val gate = CallbackGate()
        val seen = ArrayList<String>()
        fun event(name: String, nested: (() -> Unit)? = null) {
            gate.dispatch {
                seen.add(name)
                nested?.invoke()
            }
        }
        event("onPartial")
        event("onEndOfSpeech")
        event("onResults") {
            event("onError-from-teardown")
            event("onResults-again")
        }
        event("onError")
        assertEquals(
            listOf("onPartial", "onEndOfSpeech", "onResults", "onError"),
            seen,
        )
    }
}
