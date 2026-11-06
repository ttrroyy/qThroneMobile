package app.rawline.mobile.outbound.link

/**
 * QByteArray::fromBase64Encoding with AbortOnBase64DecodingErrors (the desktop's DecodeB64IfValid, Utils.cpp:40-47):
 * any character outside the chosen alphabet fails, `=` is accepted only as one or two trailing characters of an
 * input whose length is a multiple of four, missing padding is fine, and leftover bits are dropped silently.
 */
object Base64Strict {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Decoded bytes, or null when Qt would report an error. An empty input decodes to an empty array. */
    @JvmStatic
    @JvmOverloads
    fun decode(input: String, urlSafe: Boolean = false): ByteArray? {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val size = bytes.size
        val out = ByteArray(size * 3 / 4 + 3)
        var buf = 0
        var nbits = 0
        var offset = 0
        var i = 0
        while (i < size) {
            val ch = bytes[i].toInt() and 0xFF
            val d = when {
                ch >= 'A'.code && ch <= 'Z'.code -> ch - 'A'.code
                ch >= 'a'.code && ch <= 'z'.code -> ch - 'a'.code + 26
                ch >= '0'.code && ch <= '9'.code -> ch - '0'.code + 52
                ch == '+'.code && !urlSafe -> 62
                ch == '-'.code && urlSafe -> 62
                ch == '/'.code && !urlSafe -> 63
                ch == '_'.code && urlSafe -> 63
                ch == '='.code -> {
                    if (size % 4 != 0) return null
                    if (i == size - 1) break
                    if (i == size - 2 && (bytes[i + 1].toInt() and 0xFF) == '='.code) break
                    return null
                }
                else -> return null
            }
            buf = (buf shl 6) or d
            nbits += 6
            if (nbits >= 8) {
                nbits -= 8
                out[offset++] = (buf shr nbits).toByte()
                buf = buf and ((1 shl nbits) - 1)
            }
            i++
        }
        return out.copyOf(offset)
    }

    /** DecodeB64IfValid converted to a QString: "" when invalid (callers treat empty as failure). */
    @JvmStatic
    @JvmOverloads
    fun decodeToString(input: String, urlSafe: Boolean = false): String {
        val bytes = decode(input, urlSafe) ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * QByteArray::fromBase64 WITHOUT AbortOnBase64DecodingErrors (ssh.cpp:23/31/39, trusttunnel.cpp:51-105): every
     * character outside the chosen alphabet, `=` included, is skipped and leftover bits are dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun decodeLenient(input: String, urlSafe: Boolean = false): ByteArray {
        val out = ByteArray(input.length * 3 / 4 + 3)
        var buf = 0
        var nbits = 0
        var offset = 0
        for (c in input) {
            val d = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> if (urlSafe) -1 else 62
                '/' -> if (urlSafe) -1 else 63
                '-' -> if (urlSafe) 62 else -1
                '_' -> if (urlSafe) 63 else -1
                else -> -1
            }
            if (d < 0) continue
            buf = (buf shl 6) or d
            nbits += 6
            if (nbits >= 8) {
                nbits -= 8
                out[offset++] = (buf shr nbits).toByte()
                buf = buf and ((1 shl nbits) - 1)
            }
        }
        return out.copyOf(offset)
    }

    @JvmStatic
    @JvmOverloads
    fun encode(bytes: ByteArray, urlSafe: Boolean = false, padding: Boolean = true): String {
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(alphabet[b0 shr 2])
            sb.append(alphabet[((b0 and 0x3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 < 0) {
                if (padding) sb.append("==")
            } else {
                sb.append(alphabet[((b1 and 0xF) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
                if (b2 < 0) {
                    if (padding) sb.append('=')
                } else {
                    sb.append(alphabet[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun encode(text: String, urlSafe: Boolean = false, padding: Boolean = true): String =
        encode(text.toByteArray(Charsets.UTF_8), urlSafe, padding)
}
