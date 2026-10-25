package app.qthrone.mobile.outbound.common

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayMuxTest {
    @Test fun poolCapAndUdp443RoundTrip() {
        val mux = XrayMultiplex()
        mux.parseFromLink("vless://id@example.org:443?mux=1&mux_max_connections=4&mux_xudp_proxy_udp443=Skip")
        assertEquals(4, mux.maxConnections)
        assertEquals("skip", mux.xudpProxyUDP443)
        val built = mux.build(BuildContext())
        assertEquals(4, built["maxConnections"])
        assertEquals("skip", built["xudpProxyUDP443"])

        val restored = XrayMultiplex()
        restored.parseFromJson(mux.exportToJson())
        assertEquals(4, restored.maxConnections)
        // an unknown mode would fail the Xray start, so it is dropped; the cap is an int16 in Xray
        restored.parseFromJson(JsonObject().also { it["enabled"] = true; it["xudpProxyUDP443"] = "bogus"; it["maxConnections"] = 99999 })
        assertEquals("", restored.xudpProxyUDP443)
        assertEquals(Short.MAX_VALUE.toInt(), restored.maxConnections)
    }

    @Test fun systemCaOnlyWhenEnabled() {
        val tls = XrayTls().apply { serverName = "example.org" }
        assertNull(tls.build(BuildContext())["useSystemCA"])
        assertTrue(tls.build(BuildContext(xrayUseSystemCA = true))["useSystemCA"] == true)
    }
}
