package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.*
import org.junit.Assert.*
import org.junit.Test

class VpnAppGuardTest {
    @Test fun sharedUidCannotBypassExcludedApp() {
        val packages=mapOf(10001 to listOf("browser"),10002 to listOf("allowed","excluded"))
        assertEquals(listOf(10001),VpnAppGuard.allowedUids(packages,setOf("excluded"),true))
        assertEquals(listOf(10001),VpnAppGuard.allowedUids(packages,setOf("browser"),false))
    }
    @Test fun guardRunsBeforeDnsAndUserRulesAndRejectsUnknownOwner() {
        val config=JsonInput.parseObject("""{"inbounds":[{"type":"tun","tag":"tun-in"}],"route":{"rules":[{"action":"hijack-dns"},{"action":"route","outbound":"proxy"}]}}""")
        VpnAppGuard.apply(config,listOf(10001))
        val route=config.obj("route")
        val first=route.array("rules")[0] as JsonObject
        assertEquals("reject",first.string("action"))
        val uid=first.array("rules")[1] as JsonObject
        assertTrue(uid.bool("invert"))
        assertEquals(10001,uid.array("user_id")[0].toString().toInt())
        assertTrue(route.bool("find_process"))
        assertEquals(3,route.array("rules").size)
    }
    @Test fun emptyAllowedListDoesNotBecomeAnUnrestrictedVpn() {
        val config=JsonInput.parseObject("""{"inbounds":[{"type":"tun","tag":"custom-tun"}]}""")
        VpnAppGuard.apply(config,emptyList())
        val guard=config.obj("route").array("rules")[0] as JsonObject
        assertEquals("custom-tun",guard.string("inbound"))
        assertEquals("reject",guard.string("action"))
    }
}
