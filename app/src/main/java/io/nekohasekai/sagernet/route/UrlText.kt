package app.rawline.mobile.route

import java.io.ByteArrayOutputStream

/** The few QUrl behaviours the route formats rely on, without android.net or java.net.URI's strictness. */
internal object UrlText {
    private const val OLD_SCHEME = "throne://"
    /** Links shared before the rename name the old scheme; they still open. */
    fun currentScheme(text: String) = if (text.startsWith(OLD_SCHEME, ignoreCase = true)) "rawline://" + text.substring(OLD_SCHEME.length) else text
    fun isAddLink(text: String) = currentScheme(text).startsWith("rawline://add/", ignoreCase = true)

    private val URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)")

    class Parts(val scheme: String, val host: String, val path: String)

    fun parse(url: String): Parts? {
        val m = URL.find(url) ?: return null
        val authority = m.groupValues[2].substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return Parts(m.groupValues[1].lowercase(), host.lowercase(), m.groupValues[3])
    }

    /** QUrl::fileName(): the last path segment, fully decoded. */
    fun fileName(url: String): String = parse(url)?.path?.substringAfterLast('/')?.let { percentDecode(it) } ?: ""

    /** The path of a `scheme://host/<payload>` deep link after the host's slash (QUrl::path().mid(1)), fully decoded. */
    fun deepLinkPayload(text: String, prefixLength: Int): String {
        var end = text.length
        for (i in prefixLength until text.length) {
            if (text[i] == '?' || text[i] == '#') {
                end = i
                break
            }
        }
        return percentDecode(text.substring(prefixLength, end))
    }

    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
                continue
            }
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
