package io.github.nwcnwc.askbutton.net

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.github.nwcnwc.askbutton.logic.HostChoice
import io.github.nwcnwc.askbutton.logic.HostKind
import io.github.nwcnwc.askbutton.logic.ImageLinks
import io.github.nwcnwc.askbutton.logic.Payload
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.store.Settings
import java.net.URLEncoder

object Sender {
    const val MAX_EDGE = 1280
    const val JPEG_QUALITY = 72

    sealed class Outcome {
        data class Sent(val imageNote: String?) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun send(
        context: Context,
        question: String,
        screenshot: Bitmap?,
        screenText: String,
        pageUrl: String?,
        extraUrls: List<String>,
        callback: (Outcome) -> Unit,
    ) {
        val app = context.applicationContext
        val clipboard = readClipboard(app)
        val main = Handler(Looper.getMainLooper())
        Thread {
            val outcome = try {
                deliver(app, question, screenshot, screenText, pageUrl, extraUrls, clipboard)
            } catch (e: Exception) {
                Outcome.Failed(e.message?.take(180) ?: "Couldn't send")
            }
            main.post { callback(outcome) }
        }.start()
    }

    private fun deliver(
        context: Context,
        question: String,
        screenshot: Bitmap?,
        screenText: String,
        pageUrl: String?,
        extraUrls: List<String>,
        clipboard: String?,
    ): Outcome {
        val settings = Settings(context)
        val webhook = settings.webhook
        if (!WebUrls.isHttpsUrl(webhook)) {
            return Outcome.Failed("Add an https webhook in Ask Button settings.")
        }
        var imageNote: String? = null
        val screenshotUrl = when {
            screenshot == null -> null
            else -> try {
                upload(settings, Bitmaps.jpeg(screenshot, MAX_EDGE, JPEG_QUALITY))
            } catch (e: Exception) {
                imageNote = e.message?.take(140) ?: "Screenshot upload failed"
                null
            }
        }
        if (screenshot != null && screenshotUrl == null && imageNote == null) {
            imageNote = "No image host is set, so the screenshot stayed on the phone."
        }
        val url = WebUrls.choose(pageUrl, extraUrls, clipboard)
        val json = Payload.json(question, url, screenText, screenshotUrl)
        val headers = LinkedHashMap<String, String>()
        val auth = settings.authorization
        if (auth.isNotEmpty()) headers["Authorization"] = auth
        val response = try {
            Http.postJson(webhook, json, headers)
        } catch (e: Exception) {
            return Outcome.Failed("Couldn't reach the webhook.")
        }
        if (response.code !in 200..299) {
            val detail = when (response.code) {
                401, 403 -> "The webhook rejected the Authorization header ($response.code)."
                else -> "Webhook returned ${response.code}."
            }
            return Outcome.Failed(detail)
        }
        return Outcome.Sent(imageNote)
    }

    private fun upload(settings: Settings, jpeg: ByteArray): String? {
        val response = when (HostChoice.kind(settings.imgbbKey, settings.uploadUrl)) {
            HostKind.IMGBB -> {
                val key = URLEncoder.encode(settings.imgbbKey, "UTF-8")
                val endpoint =
                    "https://api.imgbb.com/1/upload?expiration=${HostChoice.IMAGE_TTL_SECONDS}&key=$key"
                Http.postMultipart(
                    endpoint,
                    emptyMap(),
                    "image",
                    "screenshot.jpg",
                    jpeg,
                    "image/jpeg",
                )
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
            error("Image host returned ${response.code}.")
        }
        return ImageLinks.parse(response.body) ?: error("Image host didn't return an https URL.")
    }

    private fun readClipboard(context: Context): String? {
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
        } catch (_: Exception) {
            null
        }
    }
}
