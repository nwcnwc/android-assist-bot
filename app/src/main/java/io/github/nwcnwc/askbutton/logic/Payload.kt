package io.github.nwcnwc.askbutton.logic

/**
 * Body posted to a Grok Bot routine webhook.
 * Keys are always present, in this order. Blank values are JSON null.
 * screenshot_base64 is last so screenshot_b64_len and body_bytes stay in the
 * body when the end is cut off.
 * screenshot_b64_len is the length of the base64 string sent, or 0 when there is no picture.
 * body_bytes is the UTF-8 byte length of this JSON, including the digits of body_bytes.
 * A picture sets screenshot_base64 and screenshot_mime. screenshot_omitted is
 * "too_large" only when every compressed size was still over the cap.
 *
 * A size-probe post adds probe, probe_step, probe_total, screenshot_b64_sha256,
 * and probe_tail. probe_tail is the field immediately before screenshot_base64.
 * A normal ask passes a null probe and those keys are absent.
 */
object Payload {
    data class Probe(val step: Int, val total: Int)

    fun json(
        question: String?,
        url: String?,
        urlSource: String?,
        screenText: String?,
        screenshotBase64: String?,
        screenshotOmitted: String?,
        probe: Probe? = null,
    ): String {
        val shot = blankToNull(screenshotBase64)
        val omitted = if (shot == null && blankToNull(screenshotOmitted) == Screenshot.OMITTED_TOO_LARGE) {
            Screenshot.OMITTED_TOO_LARGE
        } else {
            null
        }
        val mime = if (shot == null) null else Screenshot.MIME
        val b64Len = shot?.length ?: 0
        val prefix = buildString(256) {
            append("{\"question\":")
            append(Json.literal(blankToNull(question)))
            append(",\"url\":")
            append(Json.literal(blankToNull(url)))
            append(",\"url_source\":")
            append(Json.literal(blankToNull(urlSource)))
            append(",\"screen_text\":")
            append(Json.literal(blankToNull(screenText)))
            append(",\"screenshot_mime\":")
            append(Json.literal(mime))
            append(",\"screenshot_omitted\":")
            append(Json.literal(omitted))
            if (probe != null) {
                append(",\"probe\":true")
                append(",\"probe_step\":")
                append(probe.step)
                append(",\"probe_total\":")
                append(probe.total)
            }
            append(",\"screenshot_b64_len\":")
            append(b64Len)
            append(",\"body_bytes\":")
        }
        val suffix = buildString((shot?.length ?: 0) + 128) {
            if (probe != null) {
                append(",\"screenshot_b64_sha256\":")
                append(Json.literal(SizeProbe.sha256Hex(shot)))
                append(",\"probe_tail\":")
                append(Json.literal(SizeProbe.tail(shot)))
            }
            append(",\"screenshot_base64\":")
            append(Json.literal(shot))
            append('}')
        }
        // The digits of body_bytes are part of the body, so a longer number makes a longer body.
        // Each pass writes the length measured on the previous pass. Digit count only grows, then stops.
        var bodyBytes = 0
        repeat(12) {
            val built = prefix + bodyBytes.toString() + suffix
            val actual = built.toByteArray(Charsets.UTF_8).size
            if (actual == bodyBytes) return built
            bodyBytes = actual
        }
        error("body_bytes did not converge")
    }

    private fun blankToNull(value: String?): String? = value?.trim()?.ifEmpty { null }
}
