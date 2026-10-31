package io.github.nwcnwc.askbutton.net

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.RequestText
import io.github.nwcnwc.askbutton.notify.Notices
import io.github.nwcnwc.askbutton.store.LastRequest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Uploads the screenshot and posts the webhook after the assistant sheet closes.
 * A plain thread was dying with the voice session, so the test from Settings
 * arrived and a real ask did not.
 */
class SendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notices.sending(this)
        startForeground(
            Notices.SENDING_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        Handoff.signal()
        val question = intent?.getStringExtra(EXTRA_QUESTION).orEmpty()
        val screen = intent?.getStringExtra(EXTRA_SCREEN).orEmpty()
        val page = intent?.getStringExtra(EXTRA_PAGE)
        val urls = intent?.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val clip = intent?.getStringExtra(EXTRA_CLIP)
        val jpeg = intent?.getStringExtra(EXTRA_JPEG)?.let { File(it) }
        Thread {
            val report = try {
                Sender.report(this, question, jpeg, screen, page, urls, clip)
            } catch (error: Throwable) {
                Diagnostics.log(this, "send failed", error)
                Sender.Report(false, 0, RequestText.redact(error.message ?: "Couldn't send").take(180))
            } finally {
                try {
                    jpeg?.delete()
                } catch (_: Exception) {
                }
            }
            LastRequest.save(this, report.ok, report.httpCode, report.detail)
            Notices.result(this, report)
            sendBroadcast(Intent(LastRequest.ACTION).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_CLIP = "clip"
        private const val EXTRA_JPEG = "jpeg"

        fun intent(
            context: Context,
            question: String,
            screenText: String,
            pageUrl: String?,
            urls: ArrayList<String>,
            clipboard: String?,
            jpeg: File?,
        ): Intent {
            return Intent(context, SendService::class.java)
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_SCREEN, screenText)
                .putExtra(EXTRA_PAGE, pageUrl)
                .putStringArrayListExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_CLIP, clipboard)
                .putExtra(EXTRA_JPEG, jpeg?.absolutePath)
        }
    }

    object Handoff {
        private val latch = AtomicReference(CountDownLatch(1))

        fun arm(): CountDownLatch {
            val next = CountDownLatch(1)
            latch.set(next)
            return next
        }

        fun signal() {
            latch.get().countDown()
        }
    }
}
