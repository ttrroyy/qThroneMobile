package app.rawline.mobile.bg.proto

import app.rawline.mobile.outbound.config.GeneratorSettings
import java.net.URI

/** Only fixed labels enter logs: never a resolver's URL, credentials, query token or private hostname. */
internal object DnsDiagnostics {
    fun summary(settings:GeneratorSettings,providerRouting:Boolean):String =
        "Effective DNS: source=${if(providerRouting) "subscription" else "user"}; " +
            "direct=${describe(settings.directDns)}; remote=${describe(settings.remoteDns)}; " +
            "underlying=${describe(settings.underlyingDns)}"

    fun describe(address:String):String {
        if(address.isBlank() || address.startsWith("local")) return "system"
        val scheme=if("://" in address) address.substringBefore("://").lowercase() else "udp"
        val type=scheme.takeIf { it in setOf("udp","tcp","tls","https","h3","quic","dhcp") } ?: "custom"
        val host=runCatching { URI(if("://" in address) address else "udp://$address").host?.lowercase() }.getOrNull()
        val provider=when(host) {
            "8.8.8.8","8.8.4.4","dns.google" -> "google"
            "1.1.1.1","1.0.0.1","cloudflare-dns.com" -> "cloudflare"
            else -> "custom"
        }
        return "$type/$provider"
    }
}
