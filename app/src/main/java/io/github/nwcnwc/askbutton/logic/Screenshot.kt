package io.github.nwcnwc.askbutton.logic

import java.util.Base64

/** JPEG screenshot embedded in the webhook JSON. */
object Screenshot {
    /**
     * Only knob for how long a screenshot's base64 may be.
     * Keep it a multiple of 4 so the cap can match a standard base64 length exactly.
     * The send path and the tests read this constant. Do not copy the number anywhere else.
     */
    const val MAX_SCREENSHOT_B64_CHARS = 700_000
    const val MIME = "image/jpeg"
    const val OMITTED_TOO_LARGE = "too_large"

    data class Step(val maxEdge: Int, val quality: Int)

    /**
     * First attempt is the long side at 1280px, JPEG quality 70.
     * Later steps are smaller retries when the base64 is still over the cap.
     */
    val STEPS: List<Step> = listOf(
        Step(1280, 70),
        Step(1024, 60),
        Step(800, 55),
        Step(640, 50),
    )

    class Embedded(
        val jpeg: ByteArray?,
        val base64: String?,
        val mime: String?,
        val omitted: String?,
    ) {
        companion object {
            val none = Embedded(null, null, null, null)
            val tooLarge = Embedded(null, null, null, OMITTED_TOO_LARGE)

            fun included(jpeg: ByteArray, base64: String) = Embedded(jpeg, base64, MIME, null)
        }
    }

    /** Standard base64. No data: prefix and no line breaks. */
    fun base64(jpeg: ByteArray): String = Base64.getEncoder().encodeToString(jpeg)

    /**
     * [encodeJpeg] scales so the long side is at most [Step.maxEdge] and encodes at [Step.quality].
     * The first result whose base64 fits [maxChars] is kept. If none fit, the screenshot is omitted.
     * Defaults are the normal ask: [STEPS] and [MAX_SCREENSHOT_B64_CHARS].
     */
    fun embed(
        steps: List<Step> = STEPS,
        maxChars: Int = MAX_SCREENSHOT_B64_CHARS,
        encodeJpeg: (maxEdge: Int, quality: Int) -> ByteArray,
    ): Embedded {
        for (step in steps) {
            val jpeg = encodeJpeg(step.maxEdge, step.quality)
            if (jpeg.isEmpty()) continue
            val encoded = base64(jpeg)
            if (encoded.length <= maxChars) {
                return Embedded.included(jpeg, encoded)
            }
        }
        return Embedded.tooLarge
    }
}
