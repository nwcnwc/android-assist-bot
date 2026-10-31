package io.github.nwcnwc.askbutton

import android.app.Application
import io.github.nwcnwc.askbutton.diag.Diagnostics

class AskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
