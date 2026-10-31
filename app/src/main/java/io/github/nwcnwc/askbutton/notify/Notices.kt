package io.github.nwcnwc.askbutton.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import io.github.nwcnwc.askbutton.R
import io.github.nwcnwc.askbutton.ask.AskActivity
import io.github.nwcnwc.askbutton.logic.RequestText
import io.github.nwcnwc.askbutton.net.Sender

object Notices {
    const val CAPTURE_ID = 21
    const val SENDING_ID = 25
    private const val SENT_ID = 22
    private const val PROBLEM_ID = 23
    private const val TAP_ID = 24

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val sent = NotificationChannel(CHANNEL_SENT, "Sent", NotificationManager.IMPORTANCE_LOW)
        sent.setSound(null, null)
        sent.enableVibration(false)
        val capture = NotificationChannel(CHANNEL_CAPTURE, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        capture.setSound(null, null)
        capture.enableVibration(false)
        val problems = NotificationChannel(CHANNEL_PROBLEMS, "Problems", NotificationManager.IMPORTANCE_DEFAULT)
        val results = NotificationChannel(CHANNEL_RESULTS, "Send result", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(sent)
        manager.createNotificationChannel(capture)
        manager.createNotificationChannel(problems)
        manager.createNotificationChannel(results)
    }

    fun sending(context: Context): Notification {
        ensure(context)
        return Notification.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sending")
            .setContentText("Ask Button is sending this screen.")
            .setOngoing(true)
            .build()
    }

    fun result(context: Context, report: Sender.Report) {
        val summary = RequestText.summary(report.ok, report.httpCode, report.detail)
        if (!canNotify(context)) return
        ensure(context)
        val notification = Notification.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (report.ok) "Sent" else "Not sent")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(if (report.ok) SENT_ID else PROBLEM_ID, notification)
    }

    fun capture(context: Context): Notification {
        ensure(context)
        return Notification.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Capturing the screen")
            .setContentText("Ask Button is taking one screenshot.")
            .setOngoing(true)
            .build()
    }

    fun sent(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (!canNotify(context)) return
        ensure(context)
        val notification = Notification.Builder(context, CHANNEL_SENT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sent")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setTimeoutAfter(3500)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(SENT_ID, notification)
    }

    fun problem(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (!canNotify(context)) return
        ensure(context)
        val notification = Notification.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ask Button")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROBLEM_ID, notification)
    }

    fun tapToAsk(context: Context) {
        if (!canNotify(context)) return
        ensure(context)
        val intent = Intent(context, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Tap to ask")
            .setContentText("The screenshot is ready.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAP_ID, notification)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val CHANNEL_SENT = "sent"
    private const val CHANNEL_CAPTURE = "capture"
    private const val CHANNEL_PROBLEMS = "problems"
    private const val CHANNEL_RESULTS = "results"
}
