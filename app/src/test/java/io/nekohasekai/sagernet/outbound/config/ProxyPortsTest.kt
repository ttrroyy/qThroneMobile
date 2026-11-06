package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.bg.carrier.VpnListenerPolicy
import org.junit.Assert.*
import org.junit.Test

class ProxyPortsTest {
    @Test fun lanSharesTwoAuthenticatedListenersWithoutChangingOutboundPort() {
        val profile=Socks().apply { server="vpn.example";serverPort=34567 }
        val generated=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(
            vpnMode=true,lanSharingEnabled=true,mixedPort=10808,httpPort=10809,
            mixedUsername="user",mixedPassword="01234567890123456789012345678901"
        )).build(1)
        assertNull(generated.error)
        VpnListenerPolicy.check(generated.coreConfig,true)
        val config=JsonInput.parseObject(generated.coreConfig)
        val listeners=config.array("inbounds").filterIsInstance<JsonObject>().filter { it.string("type")!="tun" }
        assertEquals(listOf("socks","http"),listeners.map { it.string("type") })
        assertEquals(listOf(10808,10809),listeners.map { it.int("listen_port") })
        assertTrue(listeners.all { it.array("users").size==1 })
        val outbound=config.array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag")=="proxy" }
        assertEquals(34567,outbound.int("server_port"))
    }
}
