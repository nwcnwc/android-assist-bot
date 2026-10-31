package io.github.nwcnwc.askbutton.logic

import java.net.URI

object WebUrls {
    private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
    private val xHost = Regex("""^(?:(?:www|mobile)\.)?(?:x|twitter)\.com$""", RegexOption.IGNORE_CASE)
    private val statusId = Regex("""/status/(\d+)""", RegexOption.IGNORE_CASE)

    /** Where [url] came from. [source] is [Choice.PAGE] or [Choice.SCREEN]. */
    data class Choice(val url: String, val source: String) {
        companion object {
            const val PAGE = "page"
            const val SCREEN = "screen"
        }
    }

    fun isHttpsUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length !in 12..2048) return false
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        return try {
            val uri = URI(trimmed)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun firstHttpUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = url.find(text) ?: return null
        return clean(match.value)
    }

    fun urlsIn(parts: List<String>): List<String> {
        val found = LinkedHashSet<String>()
        for (part in parts) {
            url.findAll(part).forEach { match ->
                clean(match.value)?.let { found.add(it) }
            }
        }
        return found.toList()
    }

    /**
     * Assistant page URL, otherwise the first on-screen URL.
     * A stale x.com or twitter.com /status/ link (www. and mobile. included)
     * yields to a different status link on the screen. Null when neither has a link.
     * The clipboard is not read.
     */
    fun choose(webUri: String?, textUrls: List<String>): Choice? {
        val page = firstHttpUrl(webUri)
        val screen = ArrayList<String>(textUrls.size)
        for (candidate in textUrls) {
            firstHttpUrl(candidate)?.let { screen.add(it) }
        }
        val pageStatus = page?.let { xStatusId(it) }
        if (pageStatus != null && !mentionsId(textUrls, pageStatus)) {
            val replacement = screen.firstOrNull { candidate ->
                val id = xStatusId(candidate)
                id != null && id != pageStatus
            }
            if (replacement != null) return Choice(replacement, Choice.SCREEN)
        }
        if (page != null) return Choice(page, Choice.PAGE)
        val first = screen.firstOrNull() ?: return null
        return Choice(first, Choice.SCREEN)
    }

    private fun xStatusId(value: String): String? {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        if (!xHost.matches(host)) return null
        val path = uri.path ?: return null
        return statusId.find(path)?.groupValues?.get(1)
    }

    /** True when [id] appears in a screen URL as its own number, not as a prefix of a longer id. */
    private fun mentionsId(urls: List<String>, id: String): Boolean {
        val pattern = Regex("(?<!\\d)${Regex.escape(id)}(?!\\d)")
        for (candidate in urls) {
            if (pattern.containsMatchIn(candidate)) return true
        }
        return false
    }

    private fun clean(raw: String): String? {
        var value = raw.trim()
        while (value.isNotEmpty() && value.last() in trailing) {
            value = value.dropLast(1)
        }
        if (!(value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true))) {
            return null
        }
        if (value.any { it.isWhitespace() }) return null
        return value
    }
}
