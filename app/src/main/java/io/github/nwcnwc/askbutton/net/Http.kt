package io.github.nwcnwc.askbutton.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object Http {
    const val TIMEOUT_MS = 20_000
    const val USER_AGENT = "AskButton/0.1.7"

    data class Response(val code: Int, val body: String)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = TIMEOUT_MS,
    ): Response {
        val connection = open(url, timeoutMs)
        return call(connection, timeoutMs) {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) ->
                if (value.isNotEmpty()) connection.setRequestProperty(name, value)
            }
            val bytes = json.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            read(connection)
        }
    }

    /**
     * HttpURLConnection's read timeout does not cover a write that blocks.
     * Disconnect the socket when the deadline passes so a stalled webhook cannot hang the send.
     */
    private fun call(connection: HttpURLConnection, timeoutMs: Int, block: () -> Response): Response {
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread({
            try {
                Thread.sleep(timeoutMs.toLong())
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (timedOut.compareAndSet(false, true)) {
                try {
                    connection.disconnect()
                } catch (_: Exception) {
                }
            }
        }, "askbutton-http-timeout")
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            val response = block()
            timedOut.set(true)
            response
        } catch (error: Exception) {
            if (timedOut.get()) {
                throw SocketTimeoutException("Timed out after ${timeoutMs}ms")
            }
            throw error
        } finally {
            watchdog.interrupt()
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun read(connection: HttpURLConnection): Response {
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Response(code, stream?.let { readLimited(it) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, max: Int = 1_000_000): String {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

}
