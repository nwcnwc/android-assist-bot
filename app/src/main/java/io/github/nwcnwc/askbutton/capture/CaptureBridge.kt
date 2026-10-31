package io.github.nwcnwc.askbutton.capture

import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Hands the one-shot capture grant from the consent activity to the foreground service. */
object CaptureBridge {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var resultData: Intent? = null

    private val foreground = AtomicReference(CountDownLatch(1))
    private val grab = AtomicReference(CountDownLatch(1))
    private val shot = AtomicReference(CountDownLatch(1))
    private val claimed = AtomicBoolean(false)

    fun arm(code: Int, data: Intent) {
        resultCode = code
        resultData = data
        foreground.set(CountDownLatch(1))
        grab.set(CountDownLatch(1))
        shot.set(CountDownLatch(1))
        claimed.set(false)
    }

    fun signalForeground() = foreground.get().countDown()

    fun awaitForeground(): Boolean = foreground.get().await(3, TimeUnit.SECONDS)

    fun allowGrab() = grab.get().countDown()

    fun awaitGrab(): Boolean = grab.get().await(5, TimeUnit.SECONDS)

    fun signalShot() = shot.get().countDown()

    fun awaitShot(): Boolean = shot.get().await(8, TimeUnit.SECONDS)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
