package io.github.nwcnwc.askbutton.logic

/**
 * Body posted to a Grok Bot routine webhook.
 * Keys are always present, in this order. Blank values are JSON null.
 */
object Payload {
    fun json(
        question: String?,
        url: String?,
        screenText: String?,
        screenshotUrl: String?,
    ): String {
        return buildString(256) {
            append("{\"question\":")
            append(Json.literal(blankToNull(question)))
            append(",\"url\":")
            append(Json.literal(blankToNull(url)))
            append(",\"screen_text\":")
            append(Json.literal(blankToNull(screenText)))
            append(",\"screenshot_url\":")
            append(Json.literal(blankToNull(screenshotUrl)))
            append('}')
        }
    }

    private fun blankToNull(value: String?): String? = value?.trim()?.ifEmpty { null }
}
