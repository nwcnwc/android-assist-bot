package io.github.nwcnwc.askbutton.store

import android.content.Context
import io.github.nwcnwc.askbutton.logic.AuthHeader

class Settings(context: Context) {
    private val store = SecureStore(context.applicationContext)
    private val plain = context.applicationContext.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)

    var webhook: String
        get() = store.get(KEY_WEBHOOK).orEmpty()
        set(value) = store.put(KEY_WEBHOOK, value.trim())

    var authorization: String
        get() = store.get(KEY_AUTH).orEmpty()
        set(value) = store.put(KEY_AUTH, AuthHeader.normalize(value))

    var imgbbKey: String
        get() = store.get(KEY_IMGBB).orEmpty()
        set(value) = store.put(KEY_IMGBB, value.trim())

    var uploadUrl: String
        get() = store.get(KEY_UPLOAD).orEmpty()
        set(value) = store.put(KEY_UPLOAD, value.trim())

    fun dismissedClip(stamp: String) {
        plain.edit().putString(KEY_CLIP, stamp).apply()
    }

    fun isClipDismissed(stamp: String): Boolean = plain.getString(KEY_CLIP, "") == stamp

    private companion object {
        const val PLAIN = "askbutton_plain"
        const val KEY_WEBHOOK = "webhook"
        const val KEY_AUTH = "auth"
        const val KEY_IMGBB = "imgbb"
        const val KEY_UPLOAD = "upload"
        const val KEY_CLIP = "clip"
    }
}
