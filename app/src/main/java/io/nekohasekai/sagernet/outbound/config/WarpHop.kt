package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.types.Masque
import app.qthrone.mobile.outbound.types.WireGuard
import app.qthrone.mobile.outbound.types.WireGuardPeer

/**
 * The built-in WARP hop of generate.cpp: the synthetic profile id (generate.h:98), the WireGuard or MASQUE endpoint
 * built from the warp_* settings (getWarpProfile, :475-524) and the missing-identity check of calculatePrerequisites
 * (:559-571). With enable_warp the main chain becomes [WARP as `proxy`, exit hop as `warp-bypass`, ...].
 */
object WarpHop {
    const val PROFILE_ID = -2408L

    const val MISSING_ERROR =
        "Warp is enabled but its config has not been generated. Please generate the Warp config first in Routing Settings."

    const val TCP_ONLY_ERROR =
        "WireGuard WARP requires UDP, but this transport supports TCP only. Select MASQUE in WARP settings and generate its config."

    /** Runtime copies only: a TCP carrier can carry MASQUE over HTTP/2, but cannot carry WireGuard UDP. */
    internal fun adaptToTransport(warp: Outbound?, tcpOnly: Boolean): String {
        if (!tcpOnly || warp == null) return ""
        if (warp is WireGuard) return TCP_ONLY_ERROR
        if (warp is Masque) { warp.http_version = 2; warp.disable_version_fallback = true }
        return ""
    }

    /** Whether the identity of the active warp_mode is incomplete (:559-567). */
    @JvmStatic
    fun missing(settings: GeneratorSettings): Boolean =
        if (settings.warpMode == "masque") {
            settings.warpMasquePrivateKey.isEmpty() || settings.warpMasquePeerPublicKey.isEmpty() ||
                settings.warpMasqueEp.isEmpty() || settings.warpMasqueIfcAddrs.isEmpty()
        } else {
            settings.warpPrivateKey.isEmpty() || settings.warpPublicKey.isEmpty() ||
                settings.warpEp.isEmpty() || settings.warpIfcAddrs.isEmpty()
        }

    /** getWarpProfile (:475-524): named "warp", MTU 1280, an endpoint either way. */
    @JvmStatic
    fun outbound(settings: GeneratorSettings): Outbound {
        if (settings.warpMode == "masque") {
            val masque = Masque()
            masque.name = "warp"
            val (host, port) = splitEndpoint(settings.warpMasqueEp, 443)
            masque.server = host
            masque.serverPort = port
            masque.private_key = settings.warpMasquePrivateKey
            masque.peer_public_key = settings.warpMasquePeerPublicKey
            masque.address = settings.warpMasqueIfcAddrs.toMutableList()
            masque.mtu = 1280
            if (settings.warpMasqueSni.isNotEmpty()) masque.tls.server_name = settings.warpMasqueSni
            when (settings.warpMasqueHttpMode) {
                1 -> {
                    masque.http_version = 3
                    masque.disable_version_fallback = true
                }

                2 -> masque.http_version = 2
            }
            return masque
        }

        val wireGuard = WireGuard()
        wireGuard.name = "warp"
        val (host, port) = splitEndpoint(settings.warpEp, 2408)
        wireGuard.server = host
        wireGuard.serverPort = port
        wireGuard.private_key = settings.warpPrivateKey
        wireGuard.address = settings.warpIfcAddrs.toMutableList()
        wireGuard.peer = WireGuardPeer().apply {
            public_key = settings.warpPublicKey
            address = host
            this.port = port
            reserved = settings.warpReserved.mapTo(ArrayList()) { QtStrings.toInt(it) }
            persistent_keepalive = "10"
        }
        wireGuard.mtu = 1280
        return wireGuard
    }

    /** splitWarpEndpoint (:457-473): host, host:port, [v6] or [v6]:port; a bare IPv6 address has no port. */
    @JvmStatic
    fun splitEndpoint(endpoint: String, defaultPort: Int): Pair<String, Int> {
        val ep = endpoint.trim()
        var host = ep
        var portText = ""
        if (ep.startsWith('[')) {
            val close = ep.indexOf(']')
            if (close < 0) return host to defaultPort
            host = ep.substring(1, close)
            if (ep.substring(close + 1).startsWith(':')) portText = ep.substring(close + 2)
        } else if (ep.count { it == ':' } == 1) {
            val separator = ep.lastIndexOf(':')
            host = ep.substring(0, separator)
            portText = ep.substring(separator + 1)
        }
        val parsed = QtStrings.toInt(portText)
        return host to if (parsed in 1..65535) parsed else defaultPort
    }
}
