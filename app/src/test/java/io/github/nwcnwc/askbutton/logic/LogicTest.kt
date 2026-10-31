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
        val json = Payload.json("  what is this? ", "https://example.com/a", "page", "", null, null)
        assertEquals(
            "{\"question\":\"what is this?\",\"url\":\"https://example.com/a\",\"url_source\":\"page\",\"screen_text\":null,\"screenshot_mime\":null,\"screenshot_omitted\":null,\"screenshot_b64_len\":0,\"body_bytes\":211,\"screenshot_base64\":null}",
            json,
        )
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(
            listOf(
                "question",
                "url",
                "url_source",
                "screen_text",
                "screenshot_mime",
                "screenshot_omitted",
                "screenshot_b64_len",
                "body_bytes",
                "screenshot_base64",
            ),
            parsed.map.keys.toList(),
        )
        assertEquals(0, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
    }

    @Test
    fun payloadEscapesTextAndMarksTheJpeg() {
        val json = Payload.json("say \"hi\"\nnext\\", null, "  ", "line\tend", "abc+/=", null)
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("say \"hi\"\nnext\\", (parsed.map["question"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["url"])
        assertEquals(Jv.Null, parsed.map["url_source"])
        assertEquals("line\tend", (parsed.map["screen_text"] as Jv.Str).value)
        assertEquals("abc+/=", (parsed.map["screenshot_base64"] as Jv.Str).value)
        assertEquals("image/jpeg", (parsed.map["screenshot_mime"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["screenshot_omitted"])
        assertEquals(6, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
    }

    @Test
    fun payloadBodyBytesMatchUtf8IncludingMultibyteChars() {
        val shot = "YQ=="
        val json = Payload.json("café 问 👍", "https://example.com/ä", "page", "スクリーン", shot, "too_large")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(shot, (parsed.map["screenshot_base64"] as Jv.Str).value)
        assertEquals(shot.length, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(Jv.Null, parsed.map["screenshot_omitted"])
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertEquals(bytes, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
        assertTrue("utf8=$bytes chars=${json.length}", bytes > json.length)

        val wideShot = "A".repeat(800)
        val wide = Payload.json("é".repeat(30), null, null, null, wideShot, null)
        val wideParsed = Json.parse(wide) as Jv.Obj
        assertEquals(wideShot.length, (wideParsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(wide.toByteArray(Charsets.UTF_8).size, (wideParsed.map["body_bytes"] as Jv.Num).value.toInt())
        assertTrue(wide.toByteArray(Charsets.UTF_8).size > wide.length)
    }

    @Test
    fun payloadNullShotUsesZeroB64Len() {
        val missing = Payload.json(null, null, null, null, null, null)
        val blank = Payload.json("q", null, null, null, "  \n", null)
        for (json in listOf(missing, blank)) {
            val parsed = Json.parse(json) as Jv.Obj
            assertEquals(Jv.Null, parsed.map["screenshot_base64"])
            assertEquals(Jv.Null, parsed.map["screenshot_mime"])
            assertEquals(0, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
            assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
        }
    }

    @Test
    fun payloadOmitsAScreenshotThatIsTooLarge() {
        val json = Payload.json("q", null, null, null, "   ", "too_large")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(Jv.Null, parsed.map["screenshot_base64"])
        assertEquals(Jv.Null, parsed.map["screenshot_mime"])
        assertEquals("too_large", (parsed.map["screenshot_omitted"] as Jv.Str).value)
        assertEquals(0, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
    }

    @Test
    fun payloadKeepsThePictureWhenBothFieldsAreSet() {
        val json = Payload.json("q", null, null, null, "YQ==", "too_large")
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("YQ==", (parsed.map["screenshot_base64"] as Jv.Str).value)
        assertEquals("image/jpeg", (parsed.map["screenshot_mime"] as Jv.Str).value)
        assertEquals(Jv.Null, parsed.map["screenshot_omitted"])
        assertEquals(4, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
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
    fun urlChoicePrefersPageThenScreen() {
        val page = WebUrls.choose("https://example.com/reel", listOf("https://ads.example/x"))
        assertEquals("https://example.com/reel", page?.url)
        assertEquals("page", page?.source)
        val screen = WebUrls.choose(null, listOf("https://ads.example/x."))
        assertEquals("https://ads.example/x", screen?.url)
        assertEquals("screen", screen?.source)
        val first = WebUrls.choose("not a url", listOf("https://a.example/1", "https://b.example/2"))
        assertEquals("https://a.example/1", first?.url)
        assertEquals("screen", first?.source)
        assertNull(WebUrls.choose(null, emptyList()))
        assertNull(WebUrls.choose("notes only", listOf("still notes")))
    }

    @Test
    fun staleXStatusYieldsToADifferentStatusOnScreen() {
        val stale = WebUrls.choose(
            "https://x.com/alice/status/111",
            listOf("https://ads.example/x", "https://twitter.com/bob/status/222"),
        )
        assertEquals("https://twitter.com/bob/status/222", stale?.url)
        assertEquals("screen", stale?.source)

        val mobile = WebUrls.choose(
            "https://mobile.twitter.com/alice/status/111?s=20",
            listOf("https://www.x.com/bob/status/333"),
        )
        assertEquals("https://www.x.com/bob/status/333", mobile?.url)
        assertEquals("screen", mobile?.source)

        val webPath = WebUrls.choose(
            "https://www.twitter.com/i/web/status/111",
            listOf("https://mobile.x.com/bob/status/444/photo/1"),
        )
        assertEquals("https://mobile.x.com/bob/status/444/photo/1", webPath?.url)
        assertEquals("screen", webPath?.source)

        val same = WebUrls.choose(
            "https://twitter.com/alice/status/111",
            listOf("https://mobile.x.com/alice/status/111"),
        )
        assertEquals("https://twitter.com/alice/status/111", same?.url)
        assertEquals("page", same?.source)

        val idOnScreen = WebUrls.choose(
            "https://x.com/alice/status/111",
            listOf("https://example.com/q?ref=111", "https://x.com/bob/status/222"),
        )
        assertEquals("https://x.com/alice/status/111", idOnScreen?.url)
        assertEquals("page", idOnScreen?.source)

        val noOtherStatus = WebUrls.choose(
            "https://x.com/alice/status/111",
            listOf("https://example.com/other"),
        )
        assertEquals("https://x.com/alice/status/111", noOtherStatus?.url)
        assertEquals("page", noOtherStatus?.source)

        val notStatus = WebUrls.choose(
            "https://example.com/reel",
            listOf("https://x.com/bob/status/222"),
        )
        assertEquals("https://example.com/reel", notStatus?.url)
        assertEquals("page", notStatus?.source)

        val longerId = WebUrls.choose(
            "https://x.com/alice/status/111",
            listOf("https://x.com/bob/status/1110"),
        )
        assertEquals("https://x.com/bob/status/1110", longerId?.url)
        assertEquals("screen", longerId?.source)

        val chosen = WebUrls.choose(
            "https://x.com/old/status/111",
            listOf("https://x.com/new/status/222"),
        )!!
        val json = Payload.json("q", chosen.url, chosen.source, null, null, null)
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals("https://x.com/new/status/222", (parsed.map["url"] as Jv.Str).value)
        assertEquals("screen", (parsed.map["url_source"] as Jv.Str).value)
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
        assertTrue(Screenshot.MAX_SCREENSHOT_B64_CHARS > 0)
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

    @Test
    fun normalPayloadHasNoProbeFields() {
        val shot = "abcdefghijklmnopqrstuvwxyz0123456789+/"
        val json = Payload.json("what is this?", "https://example.com/a", "page", "caption", shot, null)
        assertFalse(json.contains("\"probe\""))
        assertFalse(json.contains("probe_step"))
        assertFalse(json.contains("probe_total"))
        assertFalse(json.contains("probe_tail"))
        assertFalse(json.contains("screenshot_b64_sha256"))
        val keys = (Json.parse(json) as Jv.Obj).map.keys
        assertEquals(
            listOf(
                "question",
                "url",
                "url_source",
                "screen_text",
                "screenshot_mime",
                "screenshot_omitted",
                "screenshot_b64_len",
                "body_bytes",
                "screenshot_base64",
            ),
            keys.toList(),
        )
        assertEquals(shot, ((Json.parse(json) as Jv.Obj).map["screenshot_base64"] as Jv.Str).value)
    }

    @Test
    fun probePayloadOrdersTailImmediatelyBeforeTheScreenshot() {
        val shot = "abcdefghijklmnopqrstuvwxyz0123456789+/"
        val tail = "wxyz0123456789+/"
        val sha = "cefbf7319da97900336e9ec6cf79724e01d305e70b6672407aeaf57a69a3e184"
        assertEquals(tail, SizeProbe.tail(shot))
        assertEquals(sha, SizeProbe.sha256Hex(shot))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SizeProbe.sha256Hex("abc"),
        )
        val json = Payload.json(
            "what is this?",
            "https://example.com/a",
            "page",
            "caption",
            shot,
            null,
            Payload.Probe(step = 3, total = SizeProbe.TOTAL),
        )
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(
            listOf(
                "question",
                "url",
                "url_source",
                "screen_text",
                "screenshot_mime",
                "screenshot_omitted",
                "probe",
                "probe_step",
                "probe_total",
                "screenshot_b64_len",
                "body_bytes",
                "screenshot_b64_sha256",
                "probe_tail",
                "screenshot_base64",
            ),
            parsed.map.keys.toList(),
        )
        assertEquals("screenshot_base64", parsed.map.keys.last())
        assertEquals("probe_tail", parsed.map.keys.toList()[parsed.map.size - 2])
        assertTrue(json.endsWith(",\"probe_tail\":\"$tail\",\"screenshot_base64\":\"$shot\"}"))
        assertEquals(true, (parsed.map["probe"] as Jv.Bool).value)
        assertEquals(3, (parsed.map["probe_step"] as Jv.Num).value.toInt())
        assertEquals(6, (parsed.map["probe_total"] as Jv.Num).value.toInt())
        assertEquals(shot.length, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(tail, (parsed.map["probe_tail"] as Jv.Str).value)
        assertEquals(sha, (parsed.map["screenshot_b64_sha256"] as Jv.Str).value)
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())

        val shortShot = "YQ=="
        val short = Payload.json("q", null, null, null, shortShot, null, Payload.Probe(0, 6))
        val shortParsed = Json.parse(short) as Jv.Obj
        assertEquals(shortShot, (shortParsed.map["probe_tail"] as Jv.Str).value)
        assertEquals(
            "ff6c0e5a7b16bb6159c1a6a4e86c55fb088a5b00f8fe9c54defd72e3027786f8",
            (shortParsed.map["screenshot_b64_sha256"] as Jv.Str).value,
        )
        assertTrue(short.endsWith(",\"probe_tail\":\"YQ==\",\"screenshot_base64\":\"YQ==\"}"))
        assertEquals(short.toByteArray(Charsets.UTF_8).size, (shortParsed.map["body_bytes"] as Jv.Num).value.toInt())

        val wide = Payload.json("café 问 👍", null, null, "スクリーン", shot, null, Payload.Probe(5, 6))
        val wideParsed = Json.parse(wide) as Jv.Obj
        assertEquals(wide.toByteArray(Charsets.UTF_8).size, (wideParsed.map["body_bytes"] as Jv.Num).value.toInt())
        assertTrue(wide.toByteArray(Charsets.UTF_8).size > wide.length)
        assertEquals(sha, (wideParsed.map["screenshot_b64_sha256"] as Jv.Str).value)
        assertEquals(tail, (wideParsed.map["probe_tail"] as Jv.Str).value)
    }

    @Test
    fun probeWithoutAPictureStillKeepsTailBeforeTheScreenshot() {
        val json = Payload.json(null, null, null, null, "  ", "too_large", Payload.Probe(0, 6))
        val parsed = Json.parse(json) as Jv.Obj
        assertEquals(Jv.Null, parsed.map["screenshot_base64"])
        assertEquals(Jv.Null, parsed.map["probe_tail"])
        assertEquals(Jv.Null, parsed.map["screenshot_b64_sha256"])
        assertEquals("too_large", (parsed.map["screenshot_omitted"] as Jv.Str).value)
        assertEquals(0, (parsed.map["screenshot_b64_len"] as Jv.Num).value.toInt())
        assertEquals(true, (parsed.map["probe"] as Jv.Bool).value)
        assertTrue(json.endsWith(",\"probe_tail\":null,\"screenshot_base64\":null}"))
        assertEquals(json.toByteArray(Charsets.UTF_8).size, (parsed.map["body_bytes"] as Jv.Num).value.toInt())
        assertEquals("screenshot_base64", parsed.map.keys.last())
        assertEquals("probe_tail", parsed.map.keys.toList()[parsed.map.size - 2])
    }

    @Test
    fun sizeProbeCapsAndLadder() {
        assertEquals(listOf(2_000, 4_000, 8_000, 16_000, 32_000, 64_000), SizeProbe.CAPS)
        assertEquals(6, SizeProbe.TOTAL)
        assertEquals(SizeProbe.CAPS.size, SizeProbe.TOTAL)
        assertEquals(1_000L, SizeProbe.GAP_MS)
        assertEquals(16, SizeProbe.TAIL_CHARS)
        assertEquals(Screenshot.STEPS.first(), SizeProbe.STEPS.first())
        assertEquals(32, SizeProbe.STEPS.last().maxEdge)
        assertTrue(SizeProbe.STEPS.all { it.maxEdge >= 32 && it.quality in 1..100 })
        assertTrue(SizeProbe.STEPS.zipWithNext().all { (earlier, later) -> earlier.maxEdge >= later.maxEdge })
        assertTrue(SizeProbe.CAPS.all { it > 0 && it % 4 == 0 })
    }

    @Test
    fun sizeProbeEmbedRestartsFromTheOriginalLadderForEachCap() {
        fun jpegFor(edge: Int) = ByteArray(edge * 40)
        fun base64Len(edge: Int): Int {
            val bytes = edge * 40
            return ((bytes + 2) / 3) * 4
        }
        assertEquals(base64Len(32), Screenshot.base64(jpegFor(32)).length)
        for (cap in SizeProbe.CAPS) {
            val seen = ArrayList<Pair<Int, Int>>()
            val result = Screenshot.embed(SizeProbe.STEPS, cap) { edge, quality ->
                seen.add(edge to quality)
                jpegFor(edge)
            }
            val expected = SizeProbe.STEPS.takeWhile { base64Len(it.maxEdge) > cap } +
                SizeProbe.STEPS.first { base64Len(it.maxEdge) <= cap }
            assertEquals(expected.map { it.maxEdge to it.quality }, seen)
            assertEquals(SizeProbe.STEPS.first().maxEdge to SizeProbe.STEPS.first().quality, seen.first())
            assertTrue(result.base64!!.length <= cap)
            assertTrue(seen.all { it.first >= 32 })
            assertNull(result.omitted)
        }
        val seen = ArrayList<Int>()
        val tooBig = Screenshot.embed(SizeProbe.STEPS, SizeProbe.CAPS.last()) { edge, _ ->
            seen.add(edge)
            ByteArray(60_000)
        }
        assertEquals(SizeProbe.STEPS.map { it.maxEdge }, seen)
        assertTrue(seen.all { it >= 32 })
        assertNull(tooBig.base64)
        assertEquals("too_large", tooBig.omitted)
    }
}
