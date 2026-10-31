package io.github.nwcnwc.askbutton.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object Http {
    data class Response(val code: Int, val body: String)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20_000,
    ): Response {
        val connection = open(url, timeoutMs)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) ->
            if (value.isNotEmpty()) connection.setRequestProperty(name, value)
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        return read(connection)
    }

    fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
        fileType: String,
        timeoutMs: Int = 30_000,
    ): Response {
        val boundary = "AskButton" + UUID.randomUUID().toString().replace("-", "")
        val connection = open(url, timeoutMs)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { out ->
            fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
            for ((name, value) in fields) {
                write("--$boundary\r\n")
                write("Content-Disposition: form-data; name=\"${safe(name)}\"\r\n\r\n")
                write(value)
                write("\r\n")
            }
            write("--$boundary\r\n")
            write(
                "Content-Disposition: form-data; name=\"${safe(fileField)}\"; filename=\"${safe(fileName)}\"\r\n",
            )
            write("Content-Type: $fileType\r\n\r\n")
            out.write(fileBytes)
            write("\r\n--$boundary--\r\n")
        }
        return read(connection)
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "AskButton/0.1")
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
            return out.toString(Charsets.UTF_8)
        }
    }

    private fun safe(value: String): String = value.replace("\"", "").replace("\r", "").replace("\n", "")
}
