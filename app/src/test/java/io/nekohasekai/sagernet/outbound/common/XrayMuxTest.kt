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
        assertEquals(4, (built["maxConnections"] as Number).toInt())
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
        val stream = XrayStreamSetting().apply {
            security = "tls"
            tls.serverName = "example.org"
        }
        assertNull(stream.build(BuildContext()).obj("tlsSettings")["useSystemCA"])
        assertTrue(stream.build(BuildContext(xrayUseSystemCA = true)).obj("tlsSettings")["useSystemCA"] == true)
    }

    @Test fun xdriveSurvivesJson() {
        val stream = XrayStreamSetting()
        stream.parseFromJson(app.qthrone.mobile.outbound.json.JsonInput.parseObject(
            """{"network":"xdrive","security":"none","address":"front.example","port":443,
            "xdriveSettings":{"service":"Google Drive","remoteFolder":"q","secrets":["a","b","c"],"pollIntervalMs":250}}"""
        ))
        assertEquals("google-drive", stream.xdrive.service)
        assertEquals(250L, stream.xdrive.pollIntervalMs)
        val json = stream.exportToJson()
        assertEquals("front.example", json["address"])
        assertEquals(3, json.obj("xdriveSettings").array("secrets").size)
    }
}
