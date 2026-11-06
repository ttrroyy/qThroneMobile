package app.rawline.mobile.bg.proto

import app.rawline.mobile.outbound.config.GeneratedConfig

/** What a core instance or a probe box is started with: the sing-box config and its Xray half. */
data class CoreConfig(
    val coreConfig: String,
    val xrayConfig: String? = null,
    val needXray: Boolean = false,
    val xrayDnsStrategy: String = "",
    val xrayFullConfigs: List<String> = emptyList(),
    val outboundTags: List<String> = emptyList(),
) {
    companion object {
        /** The exit of the main config (the started profile) and the direct outbound after it. */
        const val TAG_PROXY = "proxy"
        const val TAG_DIRECT = "direct"

        @JvmStatic
        @JvmOverloads
        fun from(result: GeneratedConfig, outboundTags: List<String> = result.outboundTags): CoreConfig = CoreConfig(
            coreConfig = result.coreConfig,
            xrayConfig = result.xrayConfig,
            needXray = result.needXray,
            xrayDnsStrategy = result.xrayDnsStrategy,
            xrayFullConfigs = result.xrayFullConfigs,
            outboundTags = outboundTags,
        )
    }
}
