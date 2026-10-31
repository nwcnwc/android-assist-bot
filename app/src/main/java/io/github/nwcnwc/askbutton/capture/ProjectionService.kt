package io.github.nwcnwc.askbutton.capture

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import io.github.nwcnwc.askbutton.ask.AskActivity
import io.github.nwcnwc.askbutton.ask.PendingShot
import io.github.nwcnwc.askbutton.logic.ImageSize
import io.github.nwcnwc.askbutton.net.Bitmaps
import io.github.nwcnwc.askbutton.net.Sender
import io.github.nwcnwc.askbutton.notify.Notices
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = Notices.capture(this)
        startForeground(
            Notices.CAPTURE_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        CaptureBridge.signalForeground()
        Thread {
            try {
                if (!CaptureBridge.awaitGrab()) return@Thread
                Thread.sleep(400)
                val code = CaptureBridge.resultCode
                val data = CaptureBridge.resultData
                CaptureBridge.resultData = null
                val bitmap = if (data != null && code == Activity.RESULT_OK) grab(code, data) else null
                PendingShot.offer(bitmap)
            } catch (_: Exception) {
                PendingShot.offer(null)
            } finally {
                CaptureBridge.signalShot()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (CaptureBridge.claim()) {
                        val ask = Intent(this, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(ask)
                        } catch (_: Exception) {
                            Notices.tapToAsk(this)
                        }
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, 600)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun grab(code: Int, data: Intent): Bitmap? {
        val done = CountDownLatch(1)
        val holder = arrayOfNulls<Bitmap>(1)
        val main = Handler(Looper.getMainLooper())
        main.post {
            var reader: ImageReader? = null
            var display: VirtualDisplay? = null
            var projection: MediaProjection? = null
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(code, data)
                if (projection == null) {
                    done.countDown()
                    return@post
                }
                projection.registerCallback(object : MediaProjection.Callback() {}, main)
                val bounds = windowBounds()
                var (width, height) = ImageSize.fit(bounds[0], bounds[1], Sender.MAX_EDGE)
                if (width % 2 != 0) width -= 1
                if (height % 2 != 0) height -= 1
                width = width.coerceAtLeast(2)
                height = height.coerceAtLeast(2)
                val density = resources.displayMetrics.densityDpi
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val latest = arrayOfNulls<Bitmap>(1)
                reader.setOnImageAvailableListener({ imageReader ->
                    val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val fresh = Bitmaps.fromRgba(
                            image.width,
                            image.height,
                            plane.rowStride,
                            plane.pixelStride,
                            plane.buffer,
                        )
                        val previous = latest[0]
                        latest[0] = fresh
                        previous?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }, main)
                display = projection.createVirtualDisplay(
                    "askbutton",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    main,
                )
                val imageReader = reader
                val virtualDisplay = display
                val mediaProjection = projection
                main.postDelayed({
                    holder[0] = latest[0]
                    latest[0] = null
                    release(imageReader, virtualDisplay, mediaProjection)
                    done.countDown()
                }, 700)
            } catch (_: Exception) {
                release(reader, display, projection)
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return holder[0]
    }

    private fun windowBounds(): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return intArrayOf(metrics.width(), metrics.height())
        }
        val display = resources.displayMetrics
        return intArrayOf(display.widthPixels, display.heightPixels)
    }

    private fun release(reader: ImageReader?, display: VirtualDisplay?, projection: MediaProjection?) {
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            display?.release()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
    }
}
