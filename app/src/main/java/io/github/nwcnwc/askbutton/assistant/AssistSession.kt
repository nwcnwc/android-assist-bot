package io.github.nwcnwc.askbutton.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.WindowManager
import io.github.nwcnwc.askbutton.R
import io.github.nwcnwc.askbutton.ask.AskController
import io.github.nwcnwc.askbutton.logic.ImageSize
import io.github.nwcnwc.askbutton.logic.ScreenText
import io.github.nwcnwc.askbutton.net.Sender

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val parts = ArrayList<String>()
    private val urls = ArrayList<String>()
    private var pageUrl: String? = null
    private var screenshot: Bitmap? = null
    private var assistDone = false
    private var shotDone = false
    private var controller: AskController? = null
    private var uiStarted = false

    init {
        setTheme(R.style.Theme_AskButton_Sheet)
    }

    override fun onCreate() {
        super.onCreate()
        window?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateContentView(): android.view.View {
        val root = layoutInflater.inflate(R.layout.ask_sheet, null)
        val source = object : AskController.Source {
            override val screenshot: Bitmap? get() = this@AssistSession.screenshot
            override val screenText: String get() = ScreenText.join(parts)
            override val urls: List<String> get() = urls
            override val pageUrl: String? get() = this@AssistSession.pageUrl
            override fun ready(): Boolean = (assistDone && shotDone)
        }
        controller = AskController(context, root, source) { finish() }
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!uiStarted) {
            uiStarted = true
            controller?.start()
        }
    }

    override fun onHide() {
        controller?.stop()
        super.onHide()
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentInsets.setEmpty()
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.isFocused || pageUrl == null) {
            val found = AssistReaders.pageUrl(state.assistContent)
            if (!found.isNullOrBlank()) pageUrl = found
        }
        val structure = state.assistStructure
        if (structure != null) {
            val dump = AssistReaders.dump(structure)
            parts.addAll(dump.parts)
            for (url in dump.urls) {
                if (url !in urls) urls.add(url)
            }
        }
        if (state.index >= state.count - 1) assistDone = true
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        this.screenshot = scale(screenshot)
        shotDone = true
    }

    private fun scale(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val (width, height) = ImageSize.fit(bitmap.width, bitmap.height, Sender.MAX_EDGE)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        bitmap.recycle()
        return scaled
    }
}
