package io.github.nwcnwc.askbutton.logic

import java.net.URLDecoder

object SetupLink {
    const val SCHEME = "androidassistbot"
    const val HOST = "setup"

    data class Params(
        val webhook: String?,
        val authorization: String?,
    )

    fun parse(raw: String): Params? {
        val text = raw.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        if (!text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
        if (schemeEnd != SCHEME.length) return null
        val host = text.substring(schemeEnd + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        if (!host.equals(HOST, ignoreCase = true)) return null
        val query = text.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty() || !text.contains('?')) {
            return Params(null, null)
        }
        val params = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val name = decode(part.substringBefore('=')).lowercase()
            val value = decode(part.substringAfter('=', ""))
            if (name.isNotEmpty()) params[name] = value
        }
        return Params(
            webhook = params["webhook"]?.trim()?.ifEmpty { null },
            authorization = first(params, "auth", "authorization", "header"),
        )
    }

    fun build(params: Params): String {
        val parts = ArrayList<String>()
        params.webhook?.let { parts.add("webhook=" + encode(it)) }
        params.authorization?.let { parts.add("auth=" + encode(it)) }
        val query = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        return "$SCHEME://$HOST$query"
    }

    private fun first(params: Map<String, String>, vararg names: String): String? {
        for (name in names) {
            val value = params[name]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
