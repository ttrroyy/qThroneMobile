package app.rawline.mobile.route

import java.util.zip.CRC32

object RuleSets {
    const val ADBLOCK_TAG = "throne-adblocksingbox"
    const val ADBLOCK_URL = "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblocksingbox.srs"

    /** ruleset_mirror values (Const.hpp:51-61). */
    const val MIRROR_GITHUB = 0
    const val MIRROR_CLOUDFLARE = 1
    const val MIRROR_GCORE = 2
    const val MIRROR_QUANTIL = 3
    const val MIRROR_FASTLY = 4
    const val MIRROR_CDN = 5

    /** An http(s) URL whose file name contains ".srs" (get_rule_set_name, RouteRule.h:90-97). */
    fun isUrl(entry: String): Boolean {
        val parts = UrlText.parse(entry.trim()) ?: return false
        if (parts.scheme != "http" && parts.scheme != "https") return false
        return UrlText.fileName(entry.trim()).contains(".srs")
    }

    /**
     * The rule-set tag of a rule_set entry: names unchanged; URLs become `<file with .srs→-srs>-<crc32 of the URL>`.
     * The desktop hashes with qHash, but the tag only has to be stable and shared by the rule and its definition.
     */
    fun tagFor(entry: String): String {
        val e = entry.trim()
        if (!isUrl(e)) return e
        val crc = CRC32().apply { update(e.toByteArray(Charsets.UTF_8)) }.value
        return UrlText.fileName(e).replace(".srs", "-srs") + "-" + java.lang.Long.toHexString(crc).padStart(8, '0')
    }

    /** get_jsdelivr_link (generate.h:65-96): raw.githubusercontent.com URLs through the chosen jsDelivr mirror. */
    fun mirrorLink(url: String, mirror: Int): String {
        if (mirror == MIRROR_GITHUB) return url
        val parts = UrlText.parse(url) ?: return url
        if (parts.host != "raw.githubusercontent.com") return url
        val sb = StringBuilder(
            when (mirror) {
                MIRROR_GCORE -> "https://gcore.jsdelivr.net/gh"
                MIRROR_QUANTIL -> "https://quantil.jsdelivr.net/gh"
                MIRROR_FASTLY -> "https://fastly.jsdelivr.net/gh"
                MIRROR_CDN -> "https://cdn.jsdelivr.net/gh"
                else -> "https://testingcf.jsdelivr.net/gh"
            }
        )
        var index = 0
        for (segment in parts.path.split('/')) {
            if (segment.isEmpty()) continue
            sb.append(if (index == 2) '@' else '/').append(segment)
            index++
        }
        return sb.toString()
    }
}
