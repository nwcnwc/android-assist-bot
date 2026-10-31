package io.github.nwcnwc.askbutton.logic

/** First take() wins until reset(). Used so one listen yields one result. */
class Once {
    private var taken = false

    fun take(): Boolean {
        if (taken) return false
        taken = true
        return true
    }

    fun taken(): Boolean = taken

    fun reset() {
        taken = false
    }
}
