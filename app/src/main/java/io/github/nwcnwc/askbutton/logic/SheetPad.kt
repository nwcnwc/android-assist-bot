package io.github.nwcnwc.askbutton.logic

object SheetPad {
    /** How much extra bottom padding the sheet needs so its buttons clear the nav bar or keyboard. */
    fun extra(neededBottom: Int, viewBottomOnScreen: Int, screenBottom: Int): Int {
        val needed = neededBottom.coerceAtLeast(0)
        if (viewBottomOnScreen <= 0 || screenBottom <= 0) return needed
        val gap = (screenBottom - viewBottomOnScreen).coerceAtLeast(0)
        return (needed - gap).coerceAtLeast(0)
    }

    fun needed(navBottom: Int, imeBottom: Int, insetsAvailable: Boolean, fallbackNav: Int): Int {
        val known = maxOf(navBottom, imeBottom)
        if (known > 0) return known
        return if (insetsAvailable) 0 else fallbackNav.coerceAtLeast(0)
    }
}
