package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.outbound.types.Shadowsocks
import org.junit.Assert.*
import org.junit.Test

class SelectorCountriesTest {
    private val now = 1000L
    private fun member(id: Long, country: String, name: String = "Server") = SelectorMember(id,
        Shadowsocks().apply { this.name=name; server="192.0.2.1"; serverPort=8388; method="aes-256-gcm"; password="secret" },
        20, now, country)

    @Test fun excludedCountriesAndNameRegexStayIndependent() {
        val members = listOf(member(1,"RU"),member(2,"DE"),member(3,""),member(4,"NL","Blocked"))
        val planner = AutoSelectorPlanner(SelectorStore { SelectorGroup(5,"Test",-1,-1,null,null,members) }, { now })
        val selector = AutoSelector().apply { gids=mutableListOf(5); countryFilter="!ru"; nameExclude="Blocked" }
        assertEquals(listOf(2L), planner.plan(10,selector).pool)
        selector.countryFilter="DE,NL,!DE"; selector.nameExclude=""
        assertEquals(listOf(4L), planner.plan(10,selector).pool)
        assertTrue(AutoSelectorPlanner.startQuorum(selector) > 0)
        assertEquals(1, planner.unmeasuredCandidates(10,selector).second)
    }

    @Test fun amneziaWarpKeepsStandardHeadersAndQuicPackets() {
        val settings = GeneratorSettings(warpMode="amnezia",warpEp="192.0.2.1:2408",warpPrivateKey="key",
            warpPublicKey="peer",warpIfcAddrs=listOf("10.0.0.2/32"),warpAmneziaPackets=listOf("<b 0x010203>"))
        assertFalse(WarpHop.missing(settings))
        assertTrue(WarpHop.missing(settings.copy(warpAmneziaPackets=emptyList())))
        val outbound = WarpHop.outbound(settings) as app.qthrone.mobile.outbound.types.WireGuard
        assertTrue(outbound.enable_amnezia)
        assertEquals("1",outbound.h1)
        assertEquals("<b 0x010203>",outbound.i1)
    }
}
