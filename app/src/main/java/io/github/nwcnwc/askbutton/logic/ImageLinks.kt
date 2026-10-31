package io.github.nwcnwc.askbutton.logic

object ImageLinks {
    /** Pull a public https image URL out of an ImgBB-style JSON body or a plain-text URL. */
    fun parse(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("https://") && !trimmed.contains('\n') && !trimmed.contains(' ')) {
            return trimmed
        }
        if (!trimmed.startsWith("{")) return null
        val root = try {
            Json.parse(trimmed) as? Jv.Obj
        } catch (_: Exception) {
            return null
        } ?: return null
        val success = root.map["success"]
        if (success is Jv.Bool && !success.value) return null
        val data = root.map["data"] as? Jv.Obj
        if (data != null) {
            httpsString(data, "url")?.let { return it }
            httpsString(data, "display_url")?.let { return it }
            val image = data.map["image"] as? Jv.Obj
            httpsString(image, "url")?.let { return it }
        }
        return httpsString(root, "url")
    }

    private fun httpsString(obj: Jv.Obj?, key: String): String? {
        val value = (obj?.map?.get(key) as? Jv.Str)?.value?.trim() ?: return null
        return if (value.startsWith("https://")) value else null
    }
}
