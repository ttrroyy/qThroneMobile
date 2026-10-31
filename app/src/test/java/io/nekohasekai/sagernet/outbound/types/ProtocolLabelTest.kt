package app.qthrone.mobile.outbound.types

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolLabelTest {
    @Test fun streamProtocolsNameTransportAndSecurity() {
        assertEquals("VLESS / TCP / Reality", Vless().apply { tls.enabled = true; tls.reality.enabled = true }.displayProtocol())
        assertEquals("VLESS / TCP", Vless().displayProtocol())
        assertEquals("Trojan / gRPC / TLS", Trojan().apply { tls.enabled = true; transport.type = "grpc" }.displayProtocol())
        assertEquals("VMess / WebSocket / TLS", Vmess().apply { tls.enabled = true; transport.type = "ws" }.displayProtocol())
    }

    @Test fun otherProtocolsShowTheirOwnName() {
        assertEquals("Hysteria 2", Hysteria().apply { protocol_version = "2" }.displayProtocol())
        assertEquals("Hysteria", Hysteria().apply { protocol_version = "1" }.displayProtocol())
        assertEquals("WireGuard", WireGuard().displayProtocol())
    }

    @Test fun amneziaWgShowsItsGeneration() {
        fun awg(block: WireGuard.() -> Unit) = WireGuard().apply { enable_amnezia = true; jc = 4; s1 = 15; s2 = 68; h1 = "5"; block() }.displayType()
        assertEquals("AWG 1.0", awg {})
        assertEquals("AWG 1.5", awg { i1 = "<b 0x01>" })
        assertEquals("AWG 2.0", awg { h1 = "100-200" })
        assertEquals("AWG 2.0", awg { s3 = 20 })
        assertEquals("AWG 3.0", awg { header_protection_key = "MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM=" })
        assertEquals("AWG 3.1", awg { header_protection_key = "MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM="; random_trailers = true })
    }
}
