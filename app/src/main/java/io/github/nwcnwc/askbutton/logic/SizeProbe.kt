package io.github.nwcnwc.askbutton.logic

import java.security.MessageDigest

/**
 * Diagnostic: one ask posts the same screenshot six times, about a second apart.
 * Each post uses a tighter base64 cap. The ladder is pure data so tests can
 * walk it without a bitmap. Encoding still starts from the original bitmap.
 */
object SizeProbe {
    val CAPS: List<Int> = listOf(2_000, 4_000, 8_000, 16_000, 32_000, 64_000)
    val TOTAL: Int = CAPS.size
    const val GAP_MS = 1_000L
    const val TAIL_CHARS = 16

    /**
     * Largest first. Later steps shrink the long side and lower JPEG quality.
     * The smallest width is about 32px, which is enough for the 2000-character cap.
     */
    val STEPS: List<Screenshot.Step> = listOf(
        Screenshot.Step(1280, 70),
        Screenshot.Step(1024, 60),
        Screenshot.Step(800, 55),
        Screenshot.Step(640, 50),
        Screenshot.Step(480, 45),
        Screenshot.Step(360, 40),
        Screenshot.Step(256, 35),
        Screenshot.Step(192, 30),
        Screenshot.Step(128, 25),
        Screenshot.Step(96, 20),
        Screenshot.Step(64, 15),
        Screenshot.Step(48, 12),
        Screenshot.Step(32, 10),
    )

    /** Last [TAIL_CHARS] characters of the base64 text stored in the payload. */
    fun tail(base64: String?): String? {
        if (base64 == null) return null
        return if (base64.length <= TAIL_CHARS) base64 else base64.substring(base64.length - TAIL_CHARS)
    }

    /** Lowercase hex SHA-256 of the base64 text, not of the decoded JPEG. */
    fun sha256Hex(base64: String?): String? {
        if (base64 == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(base64.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        val hex = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(hex)
    }
}
