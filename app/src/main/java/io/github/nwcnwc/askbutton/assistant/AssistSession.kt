package io.github.nwcnwc.askbutton.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import io.github.nwcnwc.askbutton.R
import io.github.nwcnwc.askbutton.ask.AskController
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.ImageSize
import io.github.nwcnwc.askbutton.logic.ScreenText
import io.github.nwcnwc.askbutton.net.Sender

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val parts = ArrayList<String>()
    private val foundUrls = ArrayList<String>()
    private var pageUrl: String? = null
    private var screenshot: Bitmap? = null
    private var controller: AskController? = null
    private var uiStarted = false
    private var assistDone = false
    private var shotDone = false

    init {
        setTheme(R.style.Theme_AskButton_Sheet)
    }

    override fun onCreate() {
        super.onCreate()
        guard("session create") {
            window?.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                @Suppress("DEPRECATION")
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    override fun onCreateContentView(): View {
        return try {
            val root = layoutInflater.inflate(R.layout.ask_sheet, null)
            val source = object : AskController.Source {
                override val screenshot: Bitmap? get() = this@AssistSession.screenshot
                override val screenText: String get() = ScreenText.join(parts)
                override val urls: List<String> get() = foundUrls.toList()
                override val pageUrl: String? get() = this@AssistSession.pageUrl
                override fun ready(): Boolean = assistDone && shotDone
            }
            controller = AskController(context, root, source) { finishSafely() }
            root
        } catch (error: Throwable) {
            Diagnostics.log(context, "session content", error)
            View(context)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        guard("session show") {
            if (!uiStarted) {
                uiStarted = true
                controller?.start()
            }
        }
    }

    override fun onHide() {
        guard("session hide") { controller?.stop() }
        super.onHide()
    }

    override fun onDestroy() {
        guard("session destroy") {
            controller?.stop()
            controller = null
            val shot = screenshot
            screenshot = null
            if (shot != null && !shot.isRecycled) shot.recycle()
        }
        super.onDestroy()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onHandleAssist(state: AssistState) {
        guard("assist") {
            if (state.isFocused || pageUrl == null) {
                val found = AssistReaders.pageUrl(state.assistContent)
                if (!found.isNullOrBlank()) pageUrl = found
            }
            val structure = state.assistStructure
            if (structure != null) {
                val dump = AssistReaders.dump(structure)
                parts.addAll(dump.parts)
                for (url in dump.urls) {
                    if (url !in foundUrls) foundUrls.add(url)
                }
            }
            if (state.index >= state.count - 1) assistDone = true
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        guard("screenshot") {
            val copy = copyShot(screenshot)
            val previous = this.screenshot
            this.screenshot = copy
            if (previous != null && previous != copy && !previous.isRecycled) previous.recycle()
        }
        shotDone = true
    }

    /**
     * Never recycle the bitmap the system handed us. Samsung reuses that object
     * on the next assist invocation, and recycling it crashes the second open.
     */
    private fun copyShot(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        val (width, height) = ImageSize.fit(bitmap.width, bitmap.height, Sender.MAX_EDGE)
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return if (scaled == bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    private fun finishSafely() {
        guard("session finish") { finish() }
    }

    private fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            Diagnostics.log(context, where, error)
        }
    }
}
