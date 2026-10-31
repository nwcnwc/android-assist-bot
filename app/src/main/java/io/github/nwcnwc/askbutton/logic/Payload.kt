package io.github.nwcnwc.askbutton.logic

/**
 * Body posted to a Grok Bot routine webhook.
 * Keys are always present, in this order. Blank values are JSON null.
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
        return buildString((shot?.length ?: 0) + 256) {
            append("{\"question\":")
            append(Json.literal(blankToNull(question)))
            append(",\"url\":")
            append(Json.literal(blankToNull(url)))
            append(",\"url_source\":")
            append(Json.literal(blankToNull(urlSource)))
            append(",\"screen_text\":")
            append(Json.literal(blankToNull(screenText)))
            append(",\"screenshot_base64\":")
            append(Json.literal(shot))
            append(",\"screenshot_mime\":")
            append(Json.literal(mime))
            append(",\"screenshot_omitted\":")
            append(Json.literal(omitted))
            append('}')
        }
    }

    private fun blankToNull(value: String?): String? = value?.trim()?.ifEmpty { null }
}
