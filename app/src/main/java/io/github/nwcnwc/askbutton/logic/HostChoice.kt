package io.github.nwcnwc.askbutton.logic

enum class HostKind { IMGBB, CUSTOM, NONE }

object HostChoice {
    const val IMAGE_TTL_SECONDS = 6 * 60 * 60

    fun kind(imgbbKey: String?, uploadUrl: String?): HostKind {
        if (!imgbbKey.isNullOrBlank()) return HostKind.IMGBB
        if (WebUrls.isHttpsUrl(uploadUrl.orEmpty())) return HostKind.CUSTOM
        return HostKind.NONE
    }
}
