package io.github.nwcnwc.askbutton.ask

import android.graphics.Bitmap

object PendingShot {
    @Volatile
    private var bitmap: Bitmap? = null

    fun offer(value: Bitmap?) {
        val previous = bitmap
        bitmap = value
        if (previous != null && previous != value) previous.recycle()
    }

    fun consume(): Bitmap? {
        val value = bitmap
        bitmap = null
        return value
    }
}
