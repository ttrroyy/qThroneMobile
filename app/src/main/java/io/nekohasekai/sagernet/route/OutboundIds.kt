package app.rawline.mobile.route

/** The desktop's predefined outbound ids (RouteRule.h:10-27); positive ids are server profiles. */
object OutboundIds {
    const val PROXY = -1L
    const val DIRECT = -2L
    const val BLOCK = -3L
    const val HIJACK_DNS = -4L
    const val WARP_BYPASS = -5L

    fun toName(id: Long): String = when (id) {
        PROXY -> "proxy"
        DIRECT -> "direct"
        BLOCK -> "block"
        WARP_BYPASS -> "warp-bypass"
        else -> "unknown"
    }

    fun fromName(name: String): Long? = when (name) {
        "proxy" -> PROXY
        "direct" -> DIRECT
        "block" -> BLOCK
        "warp-bypass" -> WARP_BYPASS
        else -> null
    }
}
