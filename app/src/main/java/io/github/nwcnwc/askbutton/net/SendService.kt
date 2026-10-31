package io.github.nwcnwc.askbutton.net

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.RequestText
import io.github.nwcnwc.askbutton.notify.Notices
import io.github.nwcnwc.askbutton.store.LastRequest
import java.io.File

/**
 * Uploads the screenshot and posts the webhook after the assistant sheet closes.
 * A plain thread was dying with the voice session, so the test from Settings
 * arrived and a real ask did not.
 */
class SendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the network work before startForeground. On some phones
        // startForeground never returns when the type is rejected, and the
        // sheet used to wait on that call forever.
        val job = Thread {
            finishSend(intent, startId)
        }
        job.start()
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(Notices.SENDING_ID, Notices.sending(this), type)
        } catch (error: Throwable) {
            Diagnostics.log(this, "foreground", error)
        }
        return START_NOT_STICKY
    }

    private fun finishSend(intent: Intent?, startId: Int) {
        val question = intent?.getStringExtra(EXTRA_QUESTION).orEmpty()
        val screen = intent?.getStringExtra(EXTRA_SCREEN).orEmpty()
        val page = intent?.getStringExtra(EXTRA_PAGE)
        val urls = intent?.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val clip = intent?.getStringExtra(EXTRA_CLIP)
        val jpeg = intent?.getStringExtra(EXTRA_JPEG)?.let { File(it) }
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
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {
        }
        stopSelf(startId)
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

        /** Start the service from a background thread so it cannot deadlock the UI. */
        fun enqueue(context: Context, intent: Intent) {
            val app = context.applicationContext
            try {
                app.startForegroundService(intent)
            } catch (error: Throwable) {
                Diagnostics.log(app, "foreground start failed", error)
                Thread {
                    val question = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
                    val screen = intent.getStringExtra(EXTRA_SCREEN).orEmpty()
                    val page = intent.getStringExtra(EXTRA_PAGE)
                    val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
                    val clip = intent.getStringExtra(EXTRA_CLIP)
                    val jpeg = intent.getStringExtra(EXTRA_JPEG)?.let { File(it) }
                    val report = try {
                        Sender.report(app, question, jpeg, screen, page, urls, clip)
                    } catch (sendError: Throwable) {
                        Sender.Report(false, 0, RequestText.redact(sendError.message ?: "Couldn't send").take(180))
                    } finally {
                        try {
                            jpeg?.delete()
                        } catch (_: Exception) {
                        }
                    }
                    LastRequest.save(app, report.ok, report.httpCode, report.detail)
                    Notices.result(app, report)
                    app.sendBroadcast(Intent(LastRequest.ACTION).setPackage(app.packageName))
                }.start()
            }
        }
    }
}
