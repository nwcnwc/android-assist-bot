package io.github.nwcnwc.askbutton.net

import android.app.NotificationManager
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
 * Posts the webhook after the assistant sheet closes.
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
        // A fast failure can drop the notice before startForeground posts it.
        if (!job.isAlive) {
            clearSendingNotification()
            try {
                stopSelf(startId)
            } catch (_: Exception) {
            }
        }
        return START_NOT_STICKY
    }

    private fun finishSend(intent: Intent?, startId: Int) {
        try {
            val question = intent?.getStringExtra(EXTRA_QUESTION).orEmpty()
            val screen = intent?.getStringExtra(EXTRA_SCREEN).orEmpty()
            val url = intent?.getStringExtra(EXTRA_URL)
            val urlSource = intent?.getStringExtra(EXTRA_URL_SOURCE)
            val jpeg = intent?.getStringExtra(EXTRA_JPEG)?.let { File(it) }
            val omitted = intent?.getStringExtra(EXTRA_OMITTED)
            val report = try {
                Sender.report(this, question, jpeg, omitted, screen, url, urlSource)
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
        } finally {
            clearSendingNotification()
            try {
                stopSelf(startId)
            } catch (_: Exception) {
            }
        }
    }

    /** Drop the ongoing "Sending" notice so only the result notification remains. */
    private fun clearSendingNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(Notices.SENDING_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_URL = "url"
        private const val EXTRA_URL_SOURCE = "url_source"
        private const val EXTRA_JPEG = "jpeg"
        private const val EXTRA_OMITTED = "omitted"

        fun intent(
            context: Context,
            question: String,
            screenText: String,
            url: String?,
            urlSource: String?,
            jpeg: File?,
            omitted: String?,
        ): Intent {
            return Intent(context, SendService::class.java)
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_SCREEN, screenText)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_URL_SOURCE, urlSource)
                .putExtra(EXTRA_JPEG, jpeg?.absolutePath)
                .putExtra(EXTRA_OMITTED, omitted)
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
                    val url = intent.getStringExtra(EXTRA_URL)
                    val urlSource = intent.getStringExtra(EXTRA_URL_SOURCE)
                    val jpeg = intent.getStringExtra(EXTRA_JPEG)?.let { File(it) }
                    val omitted = intent.getStringExtra(EXTRA_OMITTED)
                    val report = try {
                        Sender.report(app, question, jpeg, omitted, screen, url, urlSource)
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
