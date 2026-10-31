package io.github.nwcnwc.askbutton.logic

object ScreenText {
    const val MAX_CHARS = 8000

    fun join(parts: List<String>): String {
        val seen = LinkedHashSet<String>()
        for (part in parts) {
            val line = part.trim()
            if (line.isEmpty()) continue
            seen.add(line)
        }
        val out = StringBuilder()
        for (line in seen) {
            val extra = line.length + if (out.isEmpty()) 0 else 1
            if (out.length + extra > MAX_CHARS) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }
}
