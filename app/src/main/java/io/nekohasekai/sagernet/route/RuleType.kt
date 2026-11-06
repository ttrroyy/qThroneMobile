package app.rawline.mobile.route

/** RouteRule::type (RouteRule.h:29-88): persisted as the raw int and shared as the token; append-only. */
enum class RuleType(val id: Int, val token: String) {
    CUSTOM(0, "custom"),
    SIMPLE_ADDRESS_PROXY(1, "simple_address_proxy"),
    SIMPLE_ADDRESS_BYPASS(2, "simple_address_bypass"),
    SIMPLE_ADDRESS_BLOCK(3, "simple_address_block"),
    SIMPLE_PROCESS_NAME_PROXY(4, "simple_process_name_proxy"),
    SIMPLE_PROCESS_NAME_BYPASS(5, "simple_process_name_bypass"),
    SIMPLE_PROCESS_NAME_BLOCK(6, "simple_process_name_block"),
    SIMPLE_PROCESS_PATH_PROXY(7, "simple_process_path_proxy"),
    SIMPLE_PROCESS_PATH_BYPASS(8, "simple_process_path_bypass"),
    SIMPLE_PROCESS_PATH_BLOCK(9, "simple_process_path_block"),
    SIMPLE_ADDRESS_WARP_BYPASS(10, "simple_address_warp_bypass"),
    SIMPLE_PROCESS_NAME_WARP_BYPASS(11, "simple_process_name_warp_bypass"),
    SIMPLE_PROCESS_PATH_WARP_BYPASS(12, "simple_process_path_warp_bypass"),
    ENDPOINT_PREFERRED_BY(13, "endpoint_preferred_by");

    val isSimpleAddress: Boolean
        get() = this == SIMPLE_ADDRESS_PROXY || this == SIMPLE_ADDRESS_BYPASS ||
            this == SIMPLE_ADDRESS_BLOCK || this == SIMPLE_ADDRESS_WARP_BYPASS

    /** The fixed target of a simple rule (RouteProfile.cpp:119-155): PROXY, DIRECT, BLOCK (a reject) or WARP_BYPASS. */
    val simpleOutbound: Long?
        get() = when (this) {
            SIMPLE_ADDRESS_PROXY, SIMPLE_PROCESS_NAME_PROXY, SIMPLE_PROCESS_PATH_PROXY -> OutboundIds.PROXY
            SIMPLE_ADDRESS_BYPASS, SIMPLE_PROCESS_NAME_BYPASS, SIMPLE_PROCESS_PATH_BYPASS -> OutboundIds.DIRECT
            SIMPLE_ADDRESS_BLOCK, SIMPLE_PROCESS_NAME_BLOCK, SIMPLE_PROCESS_PATH_BLOCK -> OutboundIds.BLOCK
            SIMPLE_ADDRESS_WARP_BYPASS, SIMPLE_PROCESS_NAME_WARP_BYPASS, SIMPLE_PROCESS_PATH_WARP_BYPASS -> OutboundIds.WARP_BYPASS
            else -> null
        }

    companion object {
        fun ofId(id: Int): RuleType = values().firstOrNull { it.id == id } ?: CUSTOM

        fun ofToken(t: String?): RuleType = values().firstOrNull { it.token == t } ?: CUSTOM
    }
}
