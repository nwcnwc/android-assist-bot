package io.github.nwcnwc.askbutton.logic

import java.net.URI

object WebUrls {
    private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')

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
     * Page URL from the assistant, then the first on-screen URL,
     * then the clipboard when the whole clip is a single link.
     */
    fun choose(webUri: String?, textUrls: List<String>, clipboard: String?): String? {
        firstHttpUrl(webUri)?.let { return it }
        for (candidate in textUrls) {
            firstHttpUrl(candidate)?.let { return it }
        }
        val clip = clipboard?.trim() ?: return null
        if (clip.length > 2048) return null
        val clipUrl = firstHttpUrl(clip) ?: return null
        return if (clip == clipUrl) clipUrl else null
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
