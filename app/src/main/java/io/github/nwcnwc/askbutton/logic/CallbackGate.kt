package io.github.nwcnwc.askbutton.logic

/** Runs speech callbacks one at a time. A callback that re-enters is ignored. */
class CallbackGate {
    private var depth = 0

    fun dispatch(block: () -> Unit) {
        if (depth > 0) return
        depth++
        try {
            block()
        } finally {
            depth--
        }
    }
}
