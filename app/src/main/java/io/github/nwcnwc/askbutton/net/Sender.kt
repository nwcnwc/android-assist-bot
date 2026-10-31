package io.github.nwcnwc.askbutton.net

import android.content.Context
import android.graphics.Bitmap
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.Payload
import io.github.nwcnwc.askbutton.logic.Screenshot
import io.github.nwcnwc.askbutton.logic.SizeProbe
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.store.Settings
import java.io.File

object Sender {
    data class Report(
        val ok: Boolean,
        val httpCode: Int,
        val detail: String,
    )

    data class ProbePart(val file: File?, val omitted: String?)

    data class Shot(
        val file: File?,
        val omitted: String?,
        val probe: List<ProbePart>? = null,
    )

    fun emptyProbe(): List<ProbePart> = List(SizeProbe.TOTAL) { ProbePart(null, null) }

    /** Encode while the session is still alive. A null bitmap means no picture. */
    fun writeJpeg(context: Context, bitmap: Bitmap?): Shot {
        if (Settings(context).sizeProbe) return writeProbe(context, bitmap)
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
        probeParts: List<ProbePart>? = null,
    ): Report {
        if (probeParts != null) {
            return reportProbe(context, question, screenText, url, urlSource, probeParts)
        }
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

    /**
     * Each cap is encoded from the original bitmap, largest ladder step first,
     * until the base64 fits. The copy keeps the pixels if the session recycles
     * its bitmap while the ladder is still running.
     */
    private fun writeProbe(context: Context, bitmap: Bitmap?): Shot {
        val source = if (Settings(context).includeScreenshot) bitmap?.takeUnless { it.isRecycled } else null
        if (source == null) return Shot(null, null, emptyProbe())
        val owned = source.copy(Bitmap.Config.ARGB_8888, false)
            ?: return Shot(null, null, emptyProbe())
        val created = ArrayList<File>()
        try {
            val encoded = HashMap<Pair<Int, Int>, ByteArray>()
            val stamp = System.currentTimeMillis()
            val parts = SizeProbe.CAPS.mapIndexed { index, cap ->
                val embedded = Screenshot.embed(SizeProbe.STEPS, cap) { edge, quality ->
                    encoded.getOrPut(edge to quality) { Bitmaps.jpeg(owned, edge, quality) }
                }
                val bytes = embedded.jpeg ?: return@mapIndexed ProbePart(null, embedded.omitted)
                val file = File(context.cacheDir, "probe-$stamp-$index.jpg")
                file.outputStream().use { stream -> stream.write(bytes) }
                created.add(file)
                ProbePart(file, null)
            }
            return Shot(null, null, parts)
        } catch (error: Throwable) {
            created.forEach { file ->
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
            throw error
        } finally {
            if (!owned.isRecycled) owned.recycle()
        }
    }

    private fun reportProbe(
        context: Context,
        question: String,
        screenText: String,
        url: String?,
        urlSource: String?,
        probeParts: List<ProbePart>,
    ): Report {
        val settings = Settings(context)
        val webhook = settings.webhook
        if (!WebUrls.isHttpsUrl(webhook)) {
            return Report(false, 0, "Add an https webhook in Ask Button settings.")
        }
        val headers = LinkedHashMap<String, String>()
        val auth = settings.authorization
        if (auth.isNotEmpty()) headers["Authorization"] = auth
        var okPosts = 0
        var lastCode = 0
        var firstProblem = 0
        val total = probeParts.size
        for (step in probeParts.indices) {
            if (step > 0) {
                try {
                    Thread.sleep(SizeProbe.GAP_MS)
                } catch (_: InterruptedException) {
                }
            }
            val part = probeParts[step]
            val cap = SizeProbe.CAPS.getOrElse(step) { SizeProbe.CAPS.last() }
            val embedded = embeddedShot(part.file, part.omitted, cap)
            val json = Payload.json(
                question,
                url,
                urlSource,
                screenText,
                embedded.base64,
                embedded.omitted,
                Payload.Probe(step, SizeProbe.TOTAL),
            )
            val bodyBytes = json.toByteArray(Charsets.UTF_8).size
            val b64Len = embedded.base64?.length ?: 0
            val response = try {
                Http.postJson(webhook, json, headers)
            } catch (error: Exception) {
                if (firstProblem == 0) firstProblem = -1
                Diagnostics.log(
                    context,
                    "probe step=$step b64_len=$b64Len body_bytes=$bodyBytes http=0",
                    error,
                )
                continue
            }
            lastCode = response.code
            if (response.code in 200..299) {
                okPosts++
            } else if (firstProblem <= 0) {
                firstProblem = response.code
            }
            Diagnostics.log(
                context,
                "probe step=$step b64_len=$b64Len body_bytes=$bodyBytes http=${response.code}",
            )
        }
        val ok = total > 0 && okPosts == total
        val httpCode = if (ok) lastCode else if (firstProblem > 0) firstProblem else 0
        val detail = if (ok) {
            "Size probe sent $total sizes."
        } else {
            "Size probe: $okPosts of $total posts succeeded."
        }
        return Report(ok, httpCode, detail)
    }

    private fun embeddedShot(
        jpegFile: File?,
        omitted: String?,
        maxChars: Int = Screenshot.MAX_SCREENSHOT_B64_CHARS,
    ): Screenshot.Embedded {
        if (jpegFile != null && jpegFile.isFile) {
            val bytes = jpegFile.readBytes()
            val encoded = Screenshot.base64(bytes)
            if (encoded.isNotEmpty() && encoded.length <= maxChars) {
                return Screenshot.Embedded.included(bytes, encoded)
            }
            return Screenshot.Embedded.tooLarge
        }
        if (omitted == Screenshot.OMITTED_TOO_LARGE) return Screenshot.Embedded.tooLarge
        return Screenshot.Embedded.none
    }
}
