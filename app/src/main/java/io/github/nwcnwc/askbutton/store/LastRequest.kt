package io.github.nwcnwc.askbutton.store

import android.content.Context
import io.github.nwcnwc.askbutton.logic.RequestText

object LastRequest {
    const val ACTION = "io.github.nwcnwc.askbutton.REQUEST_DONE"

    fun save(context: Context, ok: Boolean, httpCode: Int, detail: String) {
        val summary = RequestText.summary(ok, httpCode, detail)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SET, true)
            .putString(KEY_SUMMARY, summary)
            .apply()
    }

    fun line(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getBoolean(KEY_SET, false)
        return RequestText.statusLine(set, prefs.getString(KEY_SUMMARY, "").orEmpty())
    }

    private const val PREFS = "askbutton_status"
    private const val KEY_SET = "set"
    private const val KEY_SUMMARY = "summary"
}
