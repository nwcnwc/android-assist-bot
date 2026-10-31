package io.github.nwcnwc.askbutton.net

import io.github.nwcnwc.askbutton.logic.Payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class HttpTest {
    @Test
    fun postsJsonWithAuthorization() {
        val seen = AtomicReference<Request>()
        val server = accept { request ->
            seen.set(request)
            200 to "{\"ok\":true}"
        }
        try {
            val payload = Payload.json("hello", null, null, "on screen", "YQ==", null)
            val response = Http.postJson(
                "http://127.0.0.1:${server.port}/hook",
                payload,
                mapOf("Authorization" to "Bearer test-key"),
            )
            assertEquals(200, response.code)
            val request = seen.get()
            assertEquals("POST", request.method)
            assertEquals("/hook", request.path)
            assertEquals("Bearer test-key", request.headers["authorization"])
            assertEquals(Http.USER_AGENT, request.headers["user-agent"])
            assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
            assertEquals(payload, String(request.body, Charsets.UTF_8))
            assertTrue(payload.contains("\"screenshot_base64\":\"YQ==\""))
            assertTrue(payload.contains("\"screenshot_mime\":\"image/jpeg\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun postJsonStopsWhenTheServerNeverAnswers() {
        val server = ServerSocket(0)
        val accepted = Thread {
            try {
                server.accept().use {
                    Thread.sleep(15_000)
                }
            } catch (_: Exception) {
            }
        }
        accepted.isDaemon = true
        accepted.start()
        val started = System.nanoTime()
        try {
            Http.postJson(
                "http://127.0.0.1:${server.localPort}/hook",
                """{"question":"hi"}""",
                timeoutMs = 800,
            )
            fail("expected a timeout")
        } catch (error: Exception) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("hung for ${elapsedMs}ms: $error", elapsedMs < 4_000)
        } finally {
            server.close()
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class Server(val port: Int, private val socket: ServerSocket, private val thread: Thread) : AutoCloseable {
        override fun close() {
            socket.close()
            thread.join(2000)
        }
    }

    private fun accept(handler: (Request) -> Pair<Int, String>): Server {
        val socket = ServerSocket(0)
        val thread = Thread {
            try {
                socket.accept().use { client ->
                    val request = readRequest(client)
                    val (code, body) = handler(request)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $code OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().write(bytes)
                    client.getOutputStream().flush()
                }
            } catch (_: Exception) {
            }
        }
        thread.start()
        return Server(socket.localPort, socket, thread)
    }

    private fun readRequest(client: Socket): Request {
        val input = client.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) break
            headerBytes.write(next)
            val end = headerBytes.toByteArray()
            val n = end.size
            if (n >= 4 && end[n - 4] == '\r'.code.toByte() && end[n - 3] == '\n'.code.toByte() &&
                end[n - 2] == '\r'.code.toByte() && end[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val start = lines.first().split(" ")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val name = line.substringBefore(":").trim().lowercase()
            val value = line.substringAfter(":", "").trim()
            headers[name] = value
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(start[0], start.getOrElse(1) { "/" }, headers, body)
    }
}
