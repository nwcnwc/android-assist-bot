package io.github.nwcnwc.askbutton.net

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.HostChoice
import io.github.nwcnwc.askbutton.logic.HostKind
import io.github.nwcnwc.askbutton.logic.ImageLinks
import io.github.nwcnwc.askbutton.logic.Payload
import io.github.nwcnwc.askbutton.logic.RequestText
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.store.Settings
import java.io.File
import java.net.URLEncoder

object Sender {
    const val MAX_EDGE = 1280
    const val JPEG_QUALITY = 72

    data class Report(
        val ok: Boolean,
        val httpCode: Int,
        val detail: String,
    )

    fun peekClipboard(context: Context): String? {
        return try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!manager.hasPrimaryClip()) return null
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val description = clip.description
            if (description != null &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
                description.getMimeType(0)?.startsWith("text/") != true
            ) {
                return null
            }
            clip.getItemAt(0).text?.toString()
        } catch (error: Exception) {
            Diagnostics.log(context, "clipboard", error)
            null
        }
    }

    /** Encode while the session is still alive. Returns null when there is no bitmap. */
    fun writeJpeg(context: Context, bitmap: Bitmap): File? {
        if (bitmap.isRecycled) return null
        val file = File(context.cacheDir, "send-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { stream ->
            val bytes = Bitmaps.jpeg(bitmap, MAX_EDGE, JPEG_QUALITY)
            stream.write(bytes)
        }
        return file
    }

    fun report(
        context: Context,
        question: String,
        jpegFile: File?,
        screenText: String,
        pageUrl: String?,
        extraUrls: List<String>,
        clipboard: String?,
    ): Report {
        val settings = Settings(context)
        val webhook = settings.webhook
        if (!WebUrls.isHttpsUrl(webhook)) {
            return Report(false, 0, "Add an https webhook in Ask Button settings.")
        }
        val host = runCatching { java.net.URI(webhook).host }.getOrNull() ?: "webhook"
        Diagnostics.log(context, "send start host=$host bytes=${jpegFile?.length() ?: 0}")
        var imageNote: String? = null
        val screenshotUrl = when {
            jpegFile == null || !jpegFile.exists() -> null
            else -> try {
                upload(settings, jpegFile.readBytes())
            } catch (error: Exception) {
                imageNote = if (error is java.net.SocketTimeoutException) {
                    "Image host timed out."
                } else {
                    RequestText.redact(error.message ?: "Screenshot upload failed").take(180)
                }
                Diagnostics.log(context, "image upload", error)
                null
            }
        }
        if (jpegFile != null && screenshotUrl == null && imageNote == null) {
            imageNote = "No image host is set, so the screenshot stayed on the phone."
        }
        val url = WebUrls.choose(pageUrl, extraUrls, clipboard)
        val json = Payload.json(question, url, screenText, screenshotUrl)
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
        val detail = when {
            screenshotUrl != null -> "Screenshot uploaded."
            imageNote != null -> imageNote
            else -> ""
        }
        return Report(true, response.code, detail)
    }

    private fun upload(settings: Settings, jpeg: ByteArray): String? {
        val response = when (HostChoice.kind(settings.imgbbKey, settings.uploadUrl)) {
            HostKind.IMGBB -> {
                val key = URLEncoder.encode(settings.imgbbKey, "UTF-8")
                val endpoint =
                    "https://api.imgbb.com/1/upload?expiration=${HostChoice.IMAGE_TTL_SECONDS}&key=$key"
                Http.postMultipart(endpoint, emptyMap(), "image", "screenshot.jpg", jpeg, "image/jpeg")
            }
            HostKind.CUSTOM -> Http.postMultipart(
                settings.uploadUrl,
                emptyMap(),
                "image",
                "screenshot.jpg",
                jpeg,
                "image/jpeg",
            )
            HostKind.NONE -> return null
        }
        if (response.code !in 200..299) {
            val body = RequestText.redact(response.body).take(120)
            error("Image host returned ${response.code}. $body".trim())
        }
        return ImageLinks.parse(response.body) ?: error("Image host didn't return an https URL.")
    }
}
