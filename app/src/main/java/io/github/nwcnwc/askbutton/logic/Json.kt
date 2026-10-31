package io.github.nwcnwc.askbutton.logic

sealed class Jv {
    data class Obj(val map: Map<String, Jv>) : Jv()
    data class Arr(val list: List<Jv>) : Jv()
    data class Str(val value: String) : Jv()
    data class Num(val value: String) : Jv()
    data class Bool(val value: Boolean) : Jv()
    data object Null : Jv()
}

object Json {
    fun literal(value: String?): String {
        if (value == null) return "null"
        return "\"" + escape(value) + "\""
    }

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    fun parse(text: String): Jv = Parser(text).parse()

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Jv {
            val value = value()
            skip()
            if (i != s.length) fail("trailing input")
            return value
        }

        private fun value(): Jv {
            skip()
            if (i >= s.length) fail("unexpected end")
            return when (val ch = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Jv.Str(str())
                't' -> lit("true", Jv.Bool(true))
                'f' -> lit("false", Jv.Bool(false))
                'n' -> lit("null", Jv.Null)
                else -> if (ch == '-' || ch.isDigit()) num() else fail("bad value")
            }
        }

        private fun obj(): Jv.Obj {
            expect('{')
            val map = LinkedHashMap<String, Jv>()
            skip()
            if (peek('}')) {
                i++
                return Jv.Obj(map)
            }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val key = str()
                skip()
                expect(':')
                map[key] = value()
                skip()
                when {
                    peek(',') -> i++
                    peek('}') -> {
                        i++
                        return Jv.Obj(map)
                    }
                    else -> fail("expected , or }")
                }
            }
        }

        private fun arr(): Jv.Arr {
            expect('[')
            val list = ArrayList<Jv>()
            skip()
            if (peek(']')) {
                i++
                return Jv.Arr(list)
            }
            while (true) {
                list.add(value())
                skip()
                when {
                    peek(',') -> i++
                    peek(']') -> {
                        i++
                        return Jv.Arr(list)
                    }
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val out = StringBuilder()
            while (i < s.length) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val esc = s[i++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode")
                                val hex = s.substring(i, i + 4)
                                out.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> if (ch.code < 0x20) fail("raw control") else out.append(ch)
                }
            }
            fail("unterminated string")
        }

        private fun num(): Jv.Num {
            val start = i
            if (peek('-')) i++
            if (i >= s.length || !s[i].isDigit()) fail("bad number")
            if (s[i] == '0') {
                i++
            } else {
                while (i < s.length && s[i].isDigit()) i++
            }
            if (peek('.')) {
                i++
                if (i >= s.length || !s[i].isDigit()) fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            return Jv.Num(s.substring(start, i))
        }

        private fun lit(text: String, value: Jv): Jv {
            if (!s.regionMatches(i, text, 0, text.length)) fail("expected $text")
            i += text.length
            return value
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(ch: Char) {
            if (i >= s.length || s[i] != ch) fail("expected $ch")
            i++
        }

        private fun peek(ch: Char): Boolean = i < s.length && s[i] == ch

        private fun fail(message: String): Nothing {
            throw IllegalArgumentException("$message at $i")
        }
    }
}
