package io.github.nwcnwc.askbutton.ask

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import io.github.nwcnwc.askbutton.R

class AskActivity : Activity() {
    private var controller: AskController? = null
    private var screenshot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenshot = PendingShot.consume()
        setContentView(R.layout.ask_sheet)
        val source = object : AskController.Source {
            override val screenshot: Bitmap? get() = this@AskActivity.screenshot
            override val screenText: String get() = ""
            override val urls: List<String> get() = emptyList()
            override val pageUrl: String? get() = null
            override fun ready(): Boolean = true
        }
        controller = AskController(this, findViewById(R.id.scrim), source) { finish() }.also { it.start() }
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }
}
