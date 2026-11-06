package app.rawline.mobile.ui.json.engine

enum class JsonType { Null, Bool, Number, String, Array, Object }

class JsonSpan(@JvmField val offset: Int, @JvmField val length: Int) {
    val end: Int get() = offset + length
}

/** One parsed value with its source span; object keys keep their order and their own spans (JsonTree.h). */
class JsonValue(@JvmField val type: JsonType) {
    @JvmField var span = JsonSpan(0, 0)
    @JvmField var bool = false
    @JvmField var number = 0.0
    @JvmField var integral = false
    @JvmField var string = ""
    @JvmField val keys = ArrayList<String>(0)
    @JvmField val keySpans = ArrayList<JsonSpan>(0)
    @JvmField val members = ArrayList<JsonValue>(0)
    @JvmField val elements = ArrayList<JsonValue>(0)

    /** The last occurrence, as the core's decoder keeps the last duplicate. */
    fun indexOfKey(key: String): Int {
        for (i in keys.indices.reversed()) if (keys[i] == key) return i
        return -1
    }

    fun member(key: String): JsonValue? {
        val i = indexOfKey(key)
        return if (i < 0) null else members[i]
    }
}

class JsonParseResult(
    @JvmField val root: JsonValue?,
    @JvmField val error: String,
    @JvmField val errorSpan: JsonSpan,
) {
    val ok: Boolean get() = root != null
}

/** Span-aware parser of JsonTree.cpp: comments count as whitespace, the first error stops the parse. */
object JsonTree {

    private const val MAX_PARSE_DEPTH = 96

    fun parse(text: String): JsonParseResult = Parser(text).run()

    fun typeName(type: JsonType): String = when (type) {
        JsonType.Null -> "null"
        JsonType.Bool -> "boolean"
        JsonType.Number -> "number"
        JsonType.String -> "string"
        JsonType.Array -> "array"
        JsonType.Object -> "object"
    }

    /** 1-based line and column of [offset]. */
    fun lineColumn(text: CharSequence, offset: Int): IntArray {
        var line = 1
        var column = 1
        val end = minOf(offset, text.length)
        for (i in 0 until end) {
            if (text[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return intArrayOf(line, column)
    }

    /** Converts a parsed tree to plain Kotlin values: LinkedHashMap, ArrayList, String, Double, Boolean, null. */
    fun toPlain(value: JsonValue): Any? = when (value.type) {
        JsonType.Null -> null
        JsonType.Bool -> value.bool
        JsonType.Number -> value.number
        JsonType.String -> value.string
        JsonType.Array -> value.elements.mapTo(ArrayList(value.elements.size)) { toPlain(it) }
        JsonType.Object -> LinkedHashMap<String, Any?>(value.keys.size * 2).also { map ->
            for (i in value.keys.indices) map[value.keys[i]] = toPlain(value.members[i])
        }
    }

    private class ParseFailure : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var error = ""
        private var errorSpan = JsonSpan(0, 0)

        fun run(): JsonParseResult {
            skipTrivia()
            if (atEnd()) return JsonParseResult(null, "Empty document", JsonSpan(pos, 0))
            val root = try {
                parseValue(0)
            } catch (_: ParseFailure) {
                return JsonParseResult(null, error, errorSpan)
            }
            skipTrivia()
            if (!atEnd()) {
                return JsonParseResult(null, "Unexpected content after the end of the document", JsonSpan(pos, 1))
            }
            return JsonParseResult(root, "", JsonSpan(0, 0))
        }

        private fun atEnd() = pos >= text.length

        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        private fun fail(message: String, offset: Int, length: Int = 1): Nothing {
            if (error.isEmpty()) {
                error = message
                errorSpan = JsonSpan(offset, length)
            }
            throw ParseFailure()
        }

        private fun skipTrivia() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++
                    continue
                }
                if (c == '/' && pos + 1 < text.length) {
                    val next = text[pos + 1]
                    if (next == '/') {
                        pos += 2
                        while (pos < text.length && text[pos] != '\n') pos++
                        continue
                    }
                    if (next == '*') {
                        pos += 2
                        while (pos + 1 < text.length && !(text[pos] == '*' && text[pos + 1] == '/')) pos++
                        pos = if (pos + 1 < text.length) pos + 2 else text.length
                        continue
                    }
                }
                break
            }
        }

        private fun expect(c: Char, message: String) {
            skipTrivia()
            if (peek() != c) fail(message, pos)
            pos++
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_PARSE_DEPTH) fail("The document is nested too deeply", pos)
            skipTrivia()
            if (atEnd()) fail("Unexpected end of document", pos, 0)
            return when (peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseKeyword("true")
                'f' -> parseKeyword("false")
                'n' -> parseKeyword("null")
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonValue {
            val start = pos
            pos++
            val out = JsonValue(JsonType.Object)
            skipTrivia()
            if (peek() == '}') {
                pos++
                out.span = JsonSpan(start, pos - start)
                return out
            }
            while (true) {
                skipTrivia()
                if (peek() != '"') fail("Expected a property name in quotes", pos)
                val key = parseString()
                expect(':', "Expected a colon after the property name")
                val member = parseValue(depth + 1)
                out.keys.add(key.string)
                out.keySpans.add(key.span)
                out.members.add(member)
                skipTrivia()
                if (peek() == ',') {
                    pos++
                    skipTrivia()
                    if (peek() == '}') fail("Trailing comma", pos - 1)
                    continue
                }
                if (peek() == '}') {
                    pos++
                    out.span = JsonSpan(start, pos - start)
                    return out
                }
                fail("Expected a comma or a closing brace", pos)
            }
        }

        private fun parseArray(depth: Int): JsonValue {
            val start = pos
            pos++
            val out = JsonValue(JsonType.Array)
            skipTrivia()
            if (peek() == ']') {
                pos++
                out.span = JsonSpan(start, pos - start)
                return out
            }
            while (true) {
                out.elements.add(parseValue(depth + 1))
                skipTrivia()
                if (peek() == ',') {
                    pos++
                    skipTrivia()
                    if (peek() == ']') fail("Trailing comma", pos - 1)
                    continue
                }
                if (peek() == ']') {
                    pos++
                    out.span = JsonSpan(start, pos - start)
                    return out
                }
                fail("Expected a comma or a closing bracket", pos)
            }
        }

        private fun parseString(): JsonValue {
            val start = pos
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string", start)
                val c = text[pos]
                if (c == '"') {
                    pos++
                    return JsonValue(JsonType.String).also {
                        it.string = sb.toString()
                        it.span = JsonSpan(start, pos - start)
                    }
                }
                if (c == '\\') {
                    if (pos + 1 >= text.length) fail("Unterminated escape sequence", pos)
                    val esc = text[pos + 1]
                    pos += 2
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Incomplete unicode escape sequence", pos - 2, 2)
                            var code = 0
                            for (i in pos until pos + 4) {
                                val digit = Character.digit(text[i], 16)
                                if (digit < 0) fail("Invalid unicode escape sequence", pos - 2, 6)
                                code = code * 16 + digit
                            }
                            sb.append(code.toChar())
                            pos += 4
                        }

                        else -> fail("Invalid escape sequence", pos - 2, 2)
                    }
                    continue
                }
                if (c < ' ') fail("Control character in string", pos)
                sb.append(c)
                pos++
            }
        }

        private fun parseKeyword(keyword: String): JsonValue {
            val start = pos
            if (!text.startsWith(keyword, pos)) fail("Invalid value", pos)
            pos += keyword.length
            val out = if (keyword == "null") {
                JsonValue(JsonType.Null)
            } else {
                JsonValue(JsonType.Bool).also { it.bool = keyword == "true" }
            }
            out.span = JsonSpan(start, pos - start)
            return out
        }

        private fun parseNumber(): JsonValue {
            val start = pos
            if (peek() == '-' || peek() == '+') pos++
            var fractional = false
            while (!atEnd()) {
                val c = text[pos]
                if (c in '0'..'9') {
                    pos++
                    continue
                }
                if (c == '.' || c == 'e' || c == 'E') {
                    fractional = true
                    pos++
                    if (!atEnd() && (peek() == '-' || peek() == '+')) pos++
                    continue
                }
                break
            }
            if (pos == start) fail("Invalid value", pos)
            val number = text.substring(start, pos).toDoubleOrNull()
                ?: fail("Invalid number", start, pos - start)
            return JsonValue(JsonType.Number).also {
                it.number = number
                it.integral = !fractional
                it.span = JsonSpan(start, pos - start)
            }
        }
    }
}
