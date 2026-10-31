package io.github.nwcnwc.askbutton.logic

object RequestText {
    fun summary(ok: Boolean, httpCode: Int, detail: String): String {
        val head = if (ok) "Sent" else "Not sent"
        val code = if (httpCode > 0) "HTTP $httpCode" else ""
        val tail = detail.trim()
        return listOf(head, code, tail).filter { it.isNotEmpty() }.joinToString(". ")
    }

    fun statusLine(hasResult: Boolean, summary: String): String {
        if (!hasResult) return "Last request: none yet"
        return "Last request: $summary"
    }

    fun redact(text: String): String {
        return text.replace(Regex("""(key=)[^&\s]+"""), "$1redacted")
            .replace(Regex("""(Bearer\s+)\S+"""), "$1redacted")
    }
}
