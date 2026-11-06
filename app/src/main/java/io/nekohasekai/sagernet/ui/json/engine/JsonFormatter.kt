package app.rawline.mobile.ui.json.engine

/**
 * Pretty printer over the span tree: 2-space indent, keys in document order, strings as written, comments dropped.
 * Refuses text with a syntax error, as the desktop's Format does (JsonEditorDialog.cpp:39-43).
 */
object JsonFormatter {

    private val STRICT_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun format(text: String, indent: String = "  "): String? {
        val root = JsonTree.parse(text).root ?: return null
        return format(text, root, indent)
    }

    fun format(text: String, root: JsonValue, indent: String = "  "): String {
        val out = StringBuilder(text.length + 64)
        write(text, root, indent, 0, out)
        return out.toString()
    }

    private fun write(text: String, value: JsonValue, indent: String, depth: Int, out: StringBuilder) {
        when (value.type) {
            JsonType.Object -> {
                if (value.keys.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                for (i in value.keys.indices) {
                    repeat(depth + 1) { out.append(indent) }
                    val key = value.keySpans[i]
                    out.append(text, key.offset, key.end).append(": ")
                    write(text, value.members[i], indent, depth + 1, out)
                    if (i < value.keys.size - 1) out.append(',')
                    out.append('\n')
                }
                repeat(depth) { out.append(indent) }
                out.append('}')
            }

            JsonType.Array -> {
                if (value.elements.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                for (i in value.elements.indices) {
                    repeat(depth + 1) { out.append(indent) }
                    write(text, value.elements[i], indent, depth + 1, out)
                    if (i < value.elements.size - 1) out.append(',')
                    out.append('\n')
                }
                repeat(depth) { out.append(indent) }
                out.append(']')
            }

            JsonType.Number -> {
                val token = text.substring(value.span.offset, value.span.end)
                out.append(
                    when {
                        STRICT_NUMBER.matches(token) -> token
                        value.number.isFinite() -> SchemaDocument.formatNumber(value.number)
                        else -> token.removePrefix("+")
                    }
                )
            }

            JsonType.String -> out.append(text, value.span.offset, value.span.end)
            JsonType.Bool -> out.append(if (value.bool) "true" else "false")
            JsonType.Null -> out.append("null")
        }
    }

    /** A JSON string literal of [value]. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
