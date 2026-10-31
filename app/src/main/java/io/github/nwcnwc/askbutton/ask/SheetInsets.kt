package io.github.nwcnwc.askbutton.ask

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.nwcnwc.askbutton.logic.SheetPad

object SheetInsets {
    fun extraBottom(view: View): Int {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val viewBottom = loc[1] + view.height
        val screen = screenBottom(view.context)
        val fromInsets = needed(view)
        if (view.height > 0 && fromInsets == 0 && screen - viewBottom < 8) {
            return navBarHeight(view.context)
        }
        return SheetPad.extra(fromInsets, viewBottom, screen)
    }

    private fun needed(view: View): Int {
        val insets = view.rootWindowInsets
        val fallback = navBarHeight(view.context)
        if (insets == null) return SheetPad.needed(0, 0, false, fallback)
        return if (Build.VERSION.SDK_INT >= 30) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            SheetPad.needed(nav, ime, true, fallback)
        } else {
            @Suppress("DEPRECATION")
            SheetPad.needed(0, insets.systemWindowInsetBottom, true, fallback)
        }
    }

    private fun screenBottom(context: Context): Int {
        val manager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            manager.currentWindowMetrics.bounds.bottom
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            point.y
        }
    }

    private fun navBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
