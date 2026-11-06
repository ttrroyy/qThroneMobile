package app.rawline.mobile.outbound.config

/** An IP endpoint avoids depending on another resolver to bootstrap encrypted remote DNS. */
object DnsDefaults {
    const val REMOTE = "https://1.1.1.1/dns-query"
}
