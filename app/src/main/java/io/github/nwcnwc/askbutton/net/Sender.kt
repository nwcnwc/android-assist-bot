package io.github.nwcnwc.askbutton.net

import android.content.Context
import android.graphics.Bitmap
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.Payload
import io.github.nwcnwc.askbutton.logic.Screenshot
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.store.Settings
import java.io.File

object Sender {
    data class Report(
        val ok: Boolean,
        val httpCode: Int,
        val detail: String,
    )

    data class Shot(
        val file: File?,
        val omitted: String?,
    )

    /** Encode while the session is still alive. A null bitmap means no picture. */
    fun writeJpeg(context: Context, bitmap: Bitmap?): Shot {
        if (!Settings(context).includeScreenshot) return Shot(null, null)
        if (bitmap == null || bitmap.isRecycled) return Shot(null, null)
        val embedded = Screenshot.embed { maxEdge, quality ->
            Bitmaps.jpeg(bitmap, maxEdge, quality)
        }
        val bytes = embedded.jpeg ?: return Shot(null, embedded.omitted)
        val file = File(context.cacheDir, "send-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { stream -> stream.write(bytes) }
        return Shot(file, null)
    }

    fun report(
        context: Context,
        question: String,
        jpegFile: File?,
        omitted: String?,
        screenText: String,
        url: String?,
        urlSource: String?,
    ): Report {
        val settings = Settings(context)
        val webhook = settings.webhook
        if (!WebUrls.isHttpsUrl(webhook)) {
            return Report(false, 0, "Add an https webhook in Ask Button settings.")
        }
        val host = runCatching { java.net.URI(webhook).host }.getOrNull() ?: "webhook"
        val embedded = if (settings.includeScreenshot) {
            embeddedShot(jpegFile, omitted)
        } else {
            Screenshot.Embedded.none
        }
        val shotNote = embedded.omitted ?: if (embedded.base64 == null) "none" else "inline"
        val json = Payload.json(question, url, urlSource, screenText, embedded.base64, embedded.omitted)
        val bodyBytes = json.toByteArray(Charsets.UTF_8).size
        val b64Len = embedded.base64?.trim()?.ifEmpty { null }?.length ?: 0
        Diagnostics.log(
            context,
            "send start host=$host bytes=${jpegFile?.length() ?: 0} shot=$shotNote body_bytes=$bodyBytes b64_len=$b64Len",
        )
        val headers = LinkedHashMap<String, String>()
        val auth = settings.authorization
        if (auth.isNotEmpty()) headers["Authorization"] = auth
        val response = try {
            Http.postJson(webhook, json, headers)
        } catch (error: Exception) {
            Diagnostics.log(context, "webhook post", error)
            val detail = if (error is java.net.SocketTimeoutException) {
                "Webhook timed out."
            } else {
                "Couldn't reach the webhook. ${error.message ?: ""}".trim()
            }
            return Report(false, 0, detail.take(180))
        }
        Diagnostics.log(context, "webhook HTTP ${response.code} host=$host")
        if (response.code !in 200..299) {
            val detail = when (response.code) {
                401, 403 -> "The webhook rejected the Authorization header."
                else -> "Webhook returned ${response.code}."
            }
            return Report(false, response.code, detail)
        }
        val detail = when (embedded.omitted) {
            Screenshot.OMITTED_TOO_LARGE -> "Screenshot was too large and was left out."
            else -> ""
        }
        return Report(true, response.code, detail)
    }

    private fun embeddedShot(jpegFile: File?, omitted: String?): Screenshot.Embedded {
        if (jpegFile != null && jpegFile.isFile) {
            val bytes = jpegFile.readBytes()
            val encoded = Screenshot.base64(bytes)
            if (encoded.isNotEmpty() && encoded.length <= Screenshot.MAX_SCREENSHOT_B64_CHARS) {
                return Screenshot.Embedded.included(bytes, encoded)
            }
            return Screenshot.Embedded.tooLarge
        }
        if (omitted == Screenshot.OMITTED_TOO_LARGE) return Screenshot.Embedded.tooLarge
        return Screenshot.Embedded.none
    }
}
