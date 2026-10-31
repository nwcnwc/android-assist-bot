package io.github.nwcnwc.askbutton.diag

import android.content.Context
import io.github.nwcnwc.askbutton.logic.RequestText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local crash and send log. Shown from Settings → Advanced. */
object Diagnostics {
    private const val MAX_BYTES = 32_000
    private val lock = Any()

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log(app, "fatal on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        log(app, "process start")
    }

    fun log(context: Context, message: String, error: Throwable? = null) {
        val line = buildString {
            append(stamp())
            append(' ')
            append(RequestText.redact(message).take(500))
            if (error != null) {
                append('\n')
                append(RequestText.redact(trace(error)).take(2000))
            }
            append('\n')
        }
        synchronized(lock) {
            try {
                val file = file(context)
                file.appendText(line)
                val size = file.length()
                if (size > MAX_BYTES) {
                    val tail = file.readText().takeLast(MAX_BYTES / 2)
                    file.writeText(tail)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun read(context: Context): String {
        synchronized(lock) {
            return try {
                val file = file(context)
                if (!file.exists()) "No log yet." else file.readText().takeLast(4000)
            } catch (error: Exception) {
                error.message ?: "Couldn't read the log."
            }
        }
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, "askbutton.log")

    private fun trace(error: Throwable): String {
        if (error is StackOverflowError) {
            return error.stackTrace.take(12).joinToString("\n") { it.toString() }
        }
        return error.stackTraceToString()
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
