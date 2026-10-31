package io.github.nwcnwc.askbutton.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetAndRequestTest {
    @Test
    fun padsWhenTheSheetSitsOnTheScreenBottom() {
        assertEquals(144, SheetPad.extra(144, 2400, 2400))
    }

    @Test
    fun doesNotDoublePadWhenTheWindowAlreadyClearsTheBar() {
        assertEquals(0, SheetPad.extra(144, 2256, 2400))
        assertEquals(0, SheetPad.extra(900, 1500, 2400))
    }

    @Test
    fun keyboardWinsOverTheNavBar() {
        assertEquals(900, SheetPad.needed(144, 900, true, 144))
        assertEquals(144, SheetPad.needed(0, 0, false, 144))
        assertEquals(0, SheetPad.needed(0, 0, true, 144))
    }

    @Test
    fun requestSummaryIncludesTheHttpCodeAndRedactsSecrets() {
        assertEquals("Sent. HTTP 200", RequestText.summary(true, 200, ""))
        assertEquals(
            "Not sent. HTTP 401. The webhook rejected the Authorization header.",
            RequestText.summary(false, 401, "The webhook rejected the Authorization header."),
        )
        assertEquals("Last request: none yet", RequestText.statusLine(false, ""))
        assertEquals(
            "key=redacted Bearer redacted",
            RequestText.redact("key=abc123 Bearer sekret"),
        )
    }
}
