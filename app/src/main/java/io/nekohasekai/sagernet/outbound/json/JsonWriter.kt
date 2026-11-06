package app.rawline.mobile.outbound.json

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Serialises to the byte-exact output of Qt's QJsonDocument::toJson(QJsonDocument::Compact):
 * keys sorted by UTF-16 code unit, no whitespace, only `"` `\` and control characters escaped
 * (`/` and non-ASCII are emitted literally), integral numbers without a decimal point.
 */
object JsonWriter {
    private const val HEX = "0123456789abcdef"
    private const val MAX_EXACT_DOUBLE = 9007199254740992.0 // 2^53

    @JvmStatic
    fun write(value: Any?): String {
        val sb = StringBuilder()
        append(sb, value)
        return sb.toString()
    }

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null, JsonNull -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> appendString(sb, value)
            is Long -> sb.append(value)
            is Int -> sb.append(value)
            is Double -> sb.append(formatDouble(value))
            is Number -> sb.append(formatDouble(value.toDouble()))
            is JsonObject -> {
                sb.append('{')
                var first = true
                for (key in value.sortedKeys()) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, value[key])
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, item)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value type ${value::class.java.name}")
        }
    }

    // qjsonwriter.cpp escapedString(): named escapes for \b \t \n \f \r, \u00xx (lowercase hex) for the other
    // control characters, \uxxxx for a lone surrogate, everything else (including DEL, '/' and non-ASCII) verbatim.
    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> when (c) {
                    '\b' -> sb.append("\\b")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\u000C' -> sb.append("\\f")
                    '\r' -> sb.append("\\r")
                    else -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                }
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> {
                    val u = c.code
                    sb.append("\\u").append(HEX[(u shr 12) and 0xF]).append(HEX[(u shr 8) and 0xF])
                        .append(HEX[(u shr 4) and 0xF]).append(HEX[u and 0xF])
                }
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    /**
     * QByteArray::number(d, 'g', QLocale::FloatingPointShortest) as used by the JSON writer and QVariant::toString():
     * shortest round-trip digits, laid out in whichever of the decimal or exponent forms is shorter (ties go to
     * decimal), exponents with a sign and at least two digits; integral values within +-2^53 print as integers.
     */
    @JvmStatic
    fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == Math.rint(d) && Math.abs(d) <= MAX_EXACT_DOUBLE) return d.toLong().toString()
        val negative = d < 0
        val bd = shortestDecimal(Math.abs(d))
        val digits = bd.unscaledValue().toString()
        val decpt = digits.length - bd.scale()
        val decimal = when {
            decpt <= 0 -> "0." + "0".repeat(-decpt) + digits
            decpt < digits.length -> digits.substring(0, decpt) + "." + digits.substring(decpt)
            else -> digits + "0".repeat(decpt - digits.length)
        }
        val exp = decpt - 1
        val mantissa = if (digits.length > 1) digits.substring(0, 1) + "." + digits.substring(1) else digits
        val exponent = mantissa + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        val body = if (exponent.length < decimal.length) exponent else decimal
        return if (negative) "-$body" else body
    }

    // The fewest significant digits that still round-trip to the same double (dtoa mode 0, which Qt uses);
    // Double.toString is not shortest on every JDK/ART, e.g. 1.2345e21 prints as 1.2344999999999999E21 on JDK 17.
    private fun shortestDecimal(d: Double): BigDecimal {
        val exact = BigDecimal(d)
        for (precision in 1..17) {
            val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (rounded.toDouble() == d) return rounded.stripTrailingZeros()
        }
        return exact.stripTrailingZeros()
    }

    /**
     * QJsonDocument::toJson(QJsonDocument::Indented), the form the desktop stores imported Xray configs in
     * (QJsonObject2QString(obj, false)): keys sorted, four-space indent, `"key": value`, one element per line,
     * empty containers as an opening line and a closing bracket, and a newline after the top-level object.
     */
    @JvmStatic
    fun writeIndented(obj: JsonObject): String {
        val sb = StringBuilder()
        sb.append("{\n")
        appendObjectContent(obj, sb, 1)
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendObjectContent(obj: JsonObject, sb: StringBuilder, indent: Int) {
        val indentString = " ".repeat(4 * indent)
        val keys = obj.sortedKeys()
        for ((i, key) in keys.withIndex()) {
            sb.append(indentString).append(write(key)).append(": ")
            appendValueContent(obj[key], sb, indent)
            if (i < keys.size - 1) sb.append(',')
            sb.append('\n')
        }
    }

    private fun appendArrayContent(arr: JsonArray, sb: StringBuilder, indent: Int) {
        if (arr.isEmpty()) return
        val indentString = " ".repeat(4 * indent)
        var i = 0
        while (true) {
            sb.append(indentString)
            appendValueContent(arr[i], sb, indent)
            if (++i == arr.size) {
                sb.append('\n')
                break
            }
            sb.append(",\n")
        }
    }

    private fun appendValueContent(value: Any?, sb: StringBuilder, indent: Int) {
        when (value) {
            is JsonObject -> {
                sb.append("{\n")
                appendObjectContent(value, sb, indent + 1)
                sb.append(" ".repeat(4 * indent)).append('}')
            }
            is JsonArray -> {
                sb.append("[\n")
                appendArrayContent(value, sb, indent + 1)
                sb.append(" ".repeat(4 * indent)).append(']')
            }
            else -> sb.append(write(value))
        }
    }
}
