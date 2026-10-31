package app.qthrone.mobile.outbound.import

import app.qthrone.mobile.outbound.types.Custom
import org.junit.Assert.*
import org.junit.Test

class HappHysteriaImportTest {
    @Test fun fullConfigsKeepHysteria2TransportAndAuthentication() {
        val config = """[{"remarks":"Hy2","outbounds":[{"protocol":"hysteria","settings":{"address":"example.org","port":443,"version":2},"streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"test-password"},"security":"tls","tlsSettings":{"serverName":"example.org"}}}]}]"""
        val result = ProfileImport.parse(config)
        assertEquals(result.messages.toString(),1,result.outbounds.size)
        val imported = result.outbounds.single() as Custom
        assertEquals(Custom.CUSTOM_XRAY_FULL_CONFIG,imported.subtype)
        assertEquals("Hysteria 2",imported.displayType())
        val outbound = imported.configObject().array("outbounds")[0] as app.qthrone.mobile.outbound.json.JsonObject
        assertEquals("hysteria",outbound.string("protocol"))
        assertEquals(2,outbound.obj("streamSettings").obj("hysteriaSettings").int("version"))
        assertEquals("test-password",outbound.obj("streamSettings").obj("hysteriaSettings").string("auth"))
    }
}
