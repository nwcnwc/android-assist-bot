package io.github.nwcnwc.askbutton.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    @Test
    fun payloadUsesExactKeysAndNulls() {
        val json = Payload.json("  what is this? ", "https://example.com/a", "", null)
        assertEquals(
            "{\"question\":\"what is this?\",\"url\":\"https://example.com/a\",\"screen_text\":null,\"screenshot_url\":null}",
            json,
        )
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(
            listOf("question", "url", "screen_text", "screenshot_url"),
            parsed.map.keys.toList(),
        )
    }

    @Test
    fun payloadEscapesText() {
        val json = Payload.json("say \"hi\"\nnext\\", null, "line\tend", "https://i.ibb.co/x.jpg")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("say \"hi\"\nnext\\", (parsed.map["question"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["url"])
        assertEquals("line\tend", (parsed.map["screen_text"] as Jv.Str).value)
        assertEquals("https://i.ibb.co/x.jpg", (parsed.map["screenshot_url"] as Jv.Str).value)
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
    fun imgbbUrlIsPreferredOverDeleteUrl() {
        val body = """
            {"data":{"url":"https://i.ibb.co/abc/shot.jpg","display_url":"https://i.ibb.co/abc/display.jpg","delete_url":"https://ibb.co/delete/secret"},"success":true,"status":200}
        """.trimIndent()
        assertEquals("https://i.ibb.co/abc/shot.jpg", ImageLinks.parse(body))
        assertEquals("https://cdn.example/shot.jpg", ImageLinks.parse("https://cdn.example/shot.jpg"))
        assertEquals(
            "https://i.ibb.co/abc/display.jpg",
            ImageLinks.parse("""{"data":{"display_url":"https://i.ibb.co/abc/display.jpg"},"success":true}"""),
        )
        assertNull(ImageLinks.parse("""{"success":false,"error":{"message":"bad key"}}"""))
        assertNull(ImageLinks.parse("http://insecure.example/a.jpg"))
    }

    @Test
    fun setupLinkRoundTrip() {
        val raw = "androidassistbot://setup?webhook=https%3A%2F%2Fexample.com%2Fhook&auth=Bearer%20sekret&imgbb=key123"
        val params = SetupLink.parse(raw)!!
        assertEquals("https://example.com/hook", params.webhook)
        assertEquals("Bearer sekret", params.authorization)
        assertEquals("key123", params.imgbbKey)
        assertNull(params.uploadUrl)
        val again = SetupLink.parse(SetupLink.build(params))!!
        assertEquals(params, again)
    }

    @Test
    fun setupLinkAliasesAndRejectsOtherSchemes() {
        val params = SetupLink.parse(
            "androidassistbot://setup?authorization=Authorization:%20Bearer%20abc&upload_url=https://up.example/img",
        )!!
        assertEquals("Authorization: Bearer abc", params.authorization)
        assertEquals("https://up.example/img", params.uploadUrl)
        assertNull(SetupLink.parse("https://example.com/setup?webhook=https://x"))
        assertNull(SetupLink.parse("androidassistbot://other?webhook=https://example.com"))
    }

    @Test
    fun hostChoicePrefersImgbb() {
        assertEquals(HostKind.IMGBB, HostChoice.kind("key", "https://up.example"))
        assertEquals(HostKind.CUSTOM, HostChoice.kind("  ", "https://up.example/post"))
        assertEquals(HostKind.NONE, HostChoice.kind("", "http://up.example"))
    }
}
