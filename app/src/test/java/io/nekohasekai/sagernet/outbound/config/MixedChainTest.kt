package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Chain
import app.rawline.mobile.outbound.types.Vmess
import app.rawline.mobile.outbound.types.XrayVless
import org.junit.Assert.*
import org.junit.Test

class MixedChainTest {
    @Test fun xrayEntryAndSingBoxExitDoNotBypassTheLastHop() {
        val entry=XrayVless().apply { server="192.0.2.1";serverPort=443;uuid="00000000-0000-0000-0000-000000000001" }
        val exit=Vmess().apply { server="192.0.2.2";serverPort=443;uuid="00000000-0000-0000-0000-000000000002" }
        val chain=Chain().apply { list=arrayListOf(1,2) }
        val profiles=mapOf<Long,Outbound>(1L to entry,2L to exit,3L to chain)
        val result=ConfigGenerator(ProfileProvider { profiles[it] },GeneratorSettings()).build(3)
        assertNull(result.error)
        val core=JsonInput.parseObject(result.coreConfig)
        val proxy=core.array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag")=="proxy" }
        assertEquals("vmess",proxy.string("type"))
        assertEquals(exit.server,proxy.string("server"))
        val bridge=core.array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag")==proxy.string("detour") }
        assertEquals("socks",bridge.string("type"))
        val xray=JsonInput.parseObject(result.xrayConfig!!)
        val xrayEntry=xray.array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag")=="proxy" }
        assertEquals("vless",xrayEntry.string("protocol"))
        assertEquals(entry.server,xrayEntry.obj("settings").string("address"))
        assertEquals(bridge.int("server_port"),(xray.array("inbounds")[0] as JsonObject).int("port"))
    }

    @Test fun singBoxEntryIsReachedByTheXrayExitThroughAnAuthenticatedBridge() {
        val entry=Vmess().apply { server="192.0.2.1";serverPort=443;uuid="00000000-0000-0000-0000-000000000001" }
        val exit=XrayVless().apply { server="192.0.2.2";serverPort=443;uuid="00000000-0000-0000-0000-000000000002" }
        val chain=Chain().apply { list=arrayListOf(1,2) }
        val profiles=mapOf<Long,Outbound>(1L to entry,2L to exit,3L to chain)
        val result=ConfigGenerator(ProfileProvider { profiles[it] },GeneratorSettings()).build(3)
        assertNull(result.error)
        val xray=JsonInput.parseObject(result.xrayConfig!!)
        val proxy=xray.array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag")=="proxy" }
        val bridgeTag=proxy.obj("streamSettings").obj("sockopt").string("dialerProxy")
        assertTrue(bridgeTag.isNotEmpty())
        val core=JsonInput.parseObject(result.coreConfig)
        val rules=core.obj("route").array("rules").filterIsInstance<JsonObject>()
        val entryOb=core.array("outbounds").filterIsInstance<JsonObject>().first { it.string("server")==entry.server }
        assertTrue(rules.any { it.string("outbound")==entryOb.string("tag") })
    }
}
