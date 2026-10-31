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

    /** When false, no screenshot is captured or sent. Default is on. */
    var includeScreenshot: Boolean
        get() = plain.getBoolean(KEY_INCLUDE_SHOT, true)
        set(value) {
            plain.edit().putBoolean(KEY_INCLUDE_SHOT, value).apply()
        }

    fun dismissedClip(stamp: String) {
        plain.edit().putString(KEY_CLIP, stamp).apply()
    }

    fun isClipDismissed(stamp: String): Boolean = plain.getString(KEY_CLIP, "") == stamp

    private companion object {
        const val PLAIN = "askbutton_plain"
        const val KEY_WEBHOOK = "webhook"
        const val KEY_AUTH = "auth"
        const val KEY_INCLUDE_SHOT = "include_screenshot"
        const val KEY_CLIP = "clip"
    }
}
