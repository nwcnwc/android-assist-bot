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
 */
object Payload {
    fun json(
        question: String?,
        url: String?,
        urlSource: String?,
        screenText: String?,
        screenshotBase64: String?,
        screenshotOmitted: String?,
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
            append(",\"screenshot_b64_len\":")
            append(b64Len)
            append(",\"body_bytes\":")
        }
        val suffix = buildString((shot?.length ?: 0) + 32) {
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
