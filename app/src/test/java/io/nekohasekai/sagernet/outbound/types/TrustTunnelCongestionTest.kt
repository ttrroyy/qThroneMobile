package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.json.JsonInput
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustTunnelCongestionTest {
    private fun parsed(congestion: String) = TrustTunnel().apply {
        parseFromJson(JsonInput.parseObject(
            """{"type":"trusttunnel","server":"example.org","server_port":443,"username":"u","password":"p","quic":true,"quic_congestion_control":"$congestion"}"""
        ))
    }

    @Test fun menuTextBecomesTheCoreName() {
        assertEquals("cubic", parsed("CUBIC").congestion_control)
        assertEquals("reno", parsed(" Reno ").congestion_control)
        // the core would run an unknown name as BBR under another label
        assertEquals("", parsed("vegas").congestion_control)
    }
}
