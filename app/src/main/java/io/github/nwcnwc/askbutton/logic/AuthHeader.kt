package io.github.nwcnwc.askbutton.logic

object AuthHeader {
    /** Value placed in the Authorization header. Strips a leading "Authorization:" label. */
    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.length >= 14 && value.regionMatches(0, "authorization:", 0, 14, ignoreCase = true)) {
            value = value.substring(14).trim()
        }
        return value
    }
}
