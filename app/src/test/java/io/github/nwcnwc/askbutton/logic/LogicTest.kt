package io.github.nwcnwc.askbutton.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LogicTest {
    @Test
    fun payloadUsesExactKeysAndNulls() {
        val json = Payload.json("  what is this? ", "https://example.com/a", "", null, null)
        assertEquals(
            "{\"question\":\"what is this?\",\"url\":\"https://example.com/a\",\"screen_text\":null,\"screenshot_base64\":null,\"screenshot_mime\":null,\"screenshot_omitted\":null}",
            json,
        )
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(
            listOf("question", "url", "screen_text", "screenshot_base64", "screenshot_mime", "screenshot_omitted"),
            parsed.map.keys.toList(),
        )
    }

    @Test
    fun payloadEscapesTextAndMarksTheJpeg() {
        val json = Payload.json("say \"hi\"\nnext\\", null, "line\tend", "abc+/=", null)
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("say \"hi\"\nnext\\", (parsed.map["question"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["url"])
        assertEquals("line\tend", (parsed.map["screen_text"] as Jv.Str).value)
        assertEquals("abc+/=", (parsed.map["screenshot_base64"] as Jv.Str).value)
        assertEquals("image/jpeg", (parsed.map["screenshot_mime"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["screenshot_omitted"])
    }

    @Test
    fun payloadOmitsAScreenshotThatIsTooLarge() {
        val json = Payload.json("q", null, null, "   ", "too_large")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(Jv.Null, parsed.map["screenshot_base64"])
        assertEquals(Jv.Null, parsed.map["screenshot_mime"])
        assertEquals("too_large", (parsed.map["screenshot_omitted"] as Jv.Str).value)
    }

    @Test
    fun payloadKeepsThePictureWhenBothFieldsAreSet() {
        val json = Payload.json("q", null, null, "YQ==", "too_large")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("YQ==", (parsed.map["screenshot_base64"] as Jv.Str).value)
        assertEquals("image/jpeg", (parsed.map["screenshot_mime"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["screenshot_omitted"])
    }

    @Test
    fun authHeaderStripsLabel() {
        assertEquals("Bearer secret", AuthHeader.normalize("Authorization: Bearer secret"))
        assertEquals("bearer secret", AuthHeader.normalize("  bearer secret  "))
        assertEquals("Bearer secret", AuthHeader.normalize("authorization:Bearer secret"))
    }

    @Test
    fun httpsOnly() {
        assertEquals(true, WebUrls.isHttpsUrl("https://api2.cursor.sh/automations/webhook/abc"))
        assertEquals(false, WebUrls.isHttpsUrl("http://example.com/hook"))
        assertEquals(false, WebUrls.isHttpsUrl("https://"))
        assertEquals(false, WebUrls.isHttpsUrl("javascript:alert(1)"))
    }

    @Test
    fun urlChoicePrefersPageThenTextThenWholeClipboard() {
        assertEquals(
            "https://example.com/reel",
            WebUrls.choose("https://example.com/reel", listOf("https://ads.example/x"), "https://stale.example/old"),
        )
        assertEquals(
            "https://ads.example/x",
            WebUrls.choose(null, listOf("https://ads.example/x."), null),
        )
        assertEquals(
            "https://copied.example/p",
            WebUrls.choose(null, emptyList(), "https://copied.example/p"),
        )
        assertNull(WebUrls.choose(null, emptyList(), "notes https://copied.example/p and more"))
    }

    @Test
    fun screenTextDedupesAndCaps() {
        val joined = ScreenText.join(listOf(" Hello ", "Hello", "", "World", "World"))
        assertEquals("Hello\nWorld", joined)
        val longLine = "x".repeat(100)
        val parts = List(200) { "$it-" + longLine }
        val capped = ScreenText.join(parts)
        assertEquals(true, capped.length <= ScreenText.MAX_CHARS)
        assertEquals(true, capped.startsWith("0-"))
    }

    @Test
    fun imageFitScalesTheLongEdge() {
        assertEquals(1280 to 720, ImageSize.fit(1920, 1080, 1280))
        assertEquals(640 to 480, ImageSize.fit(640, 480, 1280))
        assertEquals(720 to 1280, ImageSize.fit(1080, 1920, 1280))
    }

    @Test
    fun screenshotLadderStopsWhenTheBase64Fits() {
        assertEquals(300_000, Screenshot.MAX_SCREENSHOT_B64_CHARS)
        assertEquals(0, Screenshot.MAX_SCREENSHOT_B64_CHARS % 4)
        assertEquals(
            listOf(1280 to 70, 1024 to 60, 800 to 55, 640 to 50),
            Screenshot.STEPS.map { it.maxEdge to it.quality },
        )
        val seen = ArrayList<Pair<Int, Int>>()
        val jpeg = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 1, 2, 3)
        val result = Screenshot.embed { edge, quality ->
            seen.add(edge to quality)
            jpeg
        }
        assertEquals(listOf(1280 to 70), seen)
        assertTrue(result.jpeg!!.contentEquals(jpeg))
        assertEquals(Screenshot.base64(jpeg), result.base64)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), result.base64)
        assertEquals("image/jpeg", result.mime)
        assertNull(result.omitted)
        assertFalse(result.base64!!.contains('\n'))
        assertFalse(result.base64!!.contains('\r'))
        assertFalse(result.base64!!.startsWith("data:"))
    }

    @Test
    fun screenshotBase64IsStandardAndHasNoBreaks() {
        val raw = ByteArray(90) { (it * 17).toByte() }
        val encoded = Screenshot.base64(raw)
        assertEquals(Base64.getEncoder().encodeToString(raw), encoded)
        assertEquals("+/8=", Screenshot.base64(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        assertFalse(encoded.startsWith("data:"))
        assertTrue(encoded.length > 76)
    }

    @Test
    fun screenshotCapIsInclusiveAndLaterStepsAreRetries() {
        val exact = ByteArray(Screenshot.MAX_SCREENSHOT_B64_CHARS / 4 * 3)
        assertEquals(Screenshot.MAX_SCREENSHOT_B64_CHARS, Screenshot.base64(exact).length)
        val fitted = Screenshot.embed { _, _ -> exact }
        assertEquals(Screenshot.MAX_SCREENSHOT_B64_CHARS, fitted.base64!!.length)
        assertNull(fitted.omitted)

        val seen = ArrayList<Pair<Int, Int>>()
        val over = ByteArray(exact.size + 1)
        val result = Screenshot.embed { edge, quality ->
            seen.add(edge to quality)
            if (seen.size < 3) over else byteArrayOf(9)
        }
        assertEquals(listOf(1280 to 70, 1024 to 60, 800 to 55), seen)
        assertEquals(Screenshot.base64(byteArrayOf(9)), result.base64)
        assertNull(result.omitted)
    }

    @Test
    fun screenshotThatNeverFitsIsOmitted() {
        val over = ByteArray(Screenshot.MAX_SCREENSHOT_B64_CHARS / 4 * 3 + 1)
        val seen = ArrayList<Pair<Int, Int>>()
        val result = Screenshot.embed { edge, quality ->
            seen.add(edge to quality)
            over
        }
        assertEquals(Screenshot.STEPS.map { it.maxEdge to it.quality }, seen)
        assertNull(result.jpeg)
        assertNull(result.base64)
        assertNull(result.mime)
        assertEquals("too_large", result.omitted)
    }

    @Test
    fun setupLinkRoundTrip() {
        val raw = "androidassistbot://setup?webhook=https%3A%2F%2Fexample.com%2Fhook&auth=Bearer%20sekret"
        val params = SetupLink.parse(raw)!!
        assertEquals("https://example.com/hook", params.webhook)
        assertEquals("Bearer sekret", params.authorization)
        val again = SetupLink.parse(SetupLink.build(params))!!
        assertEquals(params, again)
    }

    @Test
    fun unknownSetupParamIsIgnored() {
        val raw = "androidassistbot://setup?webhook=https%3A%2F%2Fexample.com%2Fhook&unused=anything&auth=Bearer%20sekret"
        val params = SetupLink.parse(raw)!!
        assertEquals("https://example.com/hook", params.webhook)
        assertEquals("Bearer sekret", params.authorization)
        val built = SetupLink.build(params)
        assertFalse(built.contains("unused"))
        assertFalse(built.contains("anything"))
        assertEquals(params, SetupLink.parse(built))
    }

    @Test
    fun setupLinkAliasesAndRejectsOtherSchemes() {
        val params = SetupLink.parse(
            "androidassistbot://setup?authorization=Authorization:%20Bearer%20abc",
        )!!
        assertEquals("Authorization: Bearer abc", params.authorization)
        assertNull(params.webhook)
        assertNull(SetupLink.parse("https://example.com/setup?webhook=https://x"))
        assertNull(SetupLink.parse("androidassistbot://other?webhook=https://example.com"))
    }
}
