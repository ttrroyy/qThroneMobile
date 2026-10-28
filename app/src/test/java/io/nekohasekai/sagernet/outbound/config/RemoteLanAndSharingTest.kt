package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.json.JsonArray
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.jsonObjectOf
import app.qthrone.mobile.outbound.types.OpenConnect
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.bg.carrier.VpnListenerPolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteLanAndSharingTest {
    @Test fun sstpServerDnsUsesItsAuthenticatedBridgeAndLeavesSavedDnsAlone() {
        val profile=app.qthrone.mobile.outbound.types.Sstp().apply { server="vpn.example.org";username="user";password="secret" }
        val settings=GeneratorSettings(vpnMode=true,remoteDns="https://cloudflare-dns.com/dns-query")
        for(enabled in listOf(false,true)) {
            profile.useServerDns=enabled
            val result=ConfigGenerator(ProfileProvider { if(it==1L) profile else null },settings).build(1)
            assertNull(result.error)
            val remote=JsonInput.parseObject(result.coreConfig).obj("dns").array("servers").filterIsInstance<JsonObject>().first { it.string("tag")=="dns-remote" }
            assertEquals(if(enabled) "tcp" else "https",remote.string("type"))
            assertEquals(if(enabled) "127.0.0.53" else "cloudflare-dns.com",remote.string("server"))
            assertEquals("proxy",remote.string("detour"))
            assertEquals("https://cloudflare-dns.com/dns-query",settings.remoteDns)
        }
    }

    @Test fun openConnectAndOpenVpnDnsModesReachTheirEndpoint() {
        for(mode in listOf("none","prefer","strict")) for(type in listOf("openconnect","openvpn")) {
            val profile=if(type=="openconnect") OpenConnect().apply { server="vpn.example.org";tunnel_dns=mode }
                else app.qthrone.mobile.outbound.types.OpenVpn().apply { server="vpn.example.org";tunnel_dns=mode }
            val result=ConfigGenerator(ProfileProvider { if(it==1L) profile else null },GeneratorSettings(vpnMode=true)).build(1)
            assertNull(result.error)
            val dns=JsonInput.parseObject(result.coreConfig).obj("dns")
            val vpn=dns.array("servers").filterIsInstance<JsonObject>().firstOrNull { it.string("tag")=="dns-vpn-proxy" }
            assertEquals(mode!="none",vpn!=null)
            if(vpn!=null) {
                assertEquals(type,vpn.string("type"));assertEquals("proxy",vpn.string("endpoint"))
                assertEquals(mode=="strict",vpn.bool("accept_default_resolvers"))
                assertTrue(dns.array("rules").filterIsInstance<JsonObject>().any { it.array("preferred_by").contains("dns-vpn-proxy") })
            }
            assertEquals(if(mode=="strict") "dns-vpn-proxy" else "dns-remote",(dns.array("rules").last() as JsonObject).string("server"))
        }
    }
    @Test fun lanControlsRemainIndependentWithServerRouteSelection() {
        for(remote in listOf(false,true)) for(bypass in listOf(false,true))
            for(sharing in listOf(false,true)) for(legacyLan in listOf(false,true)) {
                val profile=if(remote) OpenConnect().apply { server="vpn.example.org" }
                    else Socks().apply { server="192.0.2.1";serverPort=1080 }
                val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(
                    vpnMode=true,bypassLan=bypass,lanSharingEnabled=sharing,
                    inboundAddress=if(legacyLan) "::" else "127.0.0.1",
                    mixedUsername="sharing-user",mixedPassword="01234567890123456789012345678901"
                )).build(1)
                assertNull(result.error)
                VpnListenerPolicy.check(result.coreConfig,sharing)
                val config=JsonInput.parseObject(result.coreConfig)
                val inbounds=config.array("inbounds").filterIsInstance<JsonObject>()
                val tun=inbounds.first { it.string("type")=="tun" }
                assertEquals(bypass && !remote,tun.array("route_exclude_address").contains("192.168.0.0/16"))
                assertEquals(sharing,inbounds.any { it.string("type")=="socks" })
                val rules=config.obj("route").array("rules").filterIsInstance<JsonObject>()
                assertEquals(remote,rules.any { it.bool("ip_is_private") && it.array("preferred_by").contains("proxy") })
                assertEquals(remote && bypass,rules.any { it.bool("ip_is_private") && it.string("outbound")=="direct" })
                assertFalse(rules.any { it.bool("ip_is_private") && it.string("outbound")=="proxy" && it.array("preferred_by").isEmpty() })
            }
    }
    @Test fun remoteLanUsesAdvertisedRoutesForOpenConnectAndOpenVpn() {
        for(profile in listOf(OpenConnect().apply { server="vpn.example.org" },
            app.qthrone.mobile.outbound.types.OpenVpn().apply { server="vpn.example.org" })) {
            val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true)).build(1)
            assertNull(result.error)
            val rules=JsonInput.parseObject(result.coreConfig).obj("route").array("rules").filterIsInstance<JsonObject>()
            val preferred=rules.indexOfFirst { it.array("preferred_by").contains("proxy") }
            val local=rules.indexOfFirst { it.bool("ip_is_private") && it.string("outbound")=="direct" }
            assertTrue("Missing preferred routes for ${profile.type}",preferred>=0)
            assertTrue(local>preferred)
        }
    }
    @Test fun sstpReachesTheServersLanButKeepsThePhonesOwnNetwork() {
        val profile=app.qthrone.mobile.outbound.types.Sstp().apply { server="vpn.example.org";username="user";password="secret" }
        val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true,localLanCidrs=listOf("192.168.31.0/24"))).build(1)
        assertNull(result.error)
        val config=JsonInput.parseObject(result.coreConfig)
        val rules=config.obj("route").array("rules").filterIsInstance<JsonObject>()
        val remote=rules.indexOfFirst { it.bool("ip_is_private") && it.string("outbound")=="proxy" }
        assertTrue(remote>=0)
        assertTrue(remote<rules.indexOfFirst { it.bool("ip_is_private") && it.string("outbound")=="direct" })
        val tun=config.array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="tun" }
        assertFalse(tun.array("route_exclude_address").contains("192.168.0.0/16"))
        assertTrue(tun.array("route_exclude_address").contains("192.168.31.0/24"))
    }
    @Test fun explicitUserLanRoutePrecedesServerPreference() {
        val profile=OpenConnect().apply { server="vpn.example.org" }
        val route=app.qthrone.mobile.route.RouteProfile.defaultProfile().apply {
            rules.add(app.qthrone.mobile.route.RouteRule().apply {
                name="Local printer";ip_cidr.add("192.168.1.25/32")
                action="route";outbound_id=app.qthrone.mobile.route.OutboundIds.DIRECT
            })
        }
        val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true),
            routing=RoutingInput(route,app.qthrone.mobile.route.RuleSetCatalog(emptyList()))).build(1)
        assertNull(result.error)
        val rules=JsonInput.parseObject(result.coreConfig).obj("route").array("rules").filterIsInstance<JsonObject>()
        val userRule=rules.indexOfFirst { it.array("ip_cidr").contains("192.168.1.25/32") && it.string("outbound")=="direct" }
        assertTrue(userRule>=0)
        assertTrue(userRule<rules.indexOfFirst { it.array("preferred_by").contains("proxy") })
    }
    @Test fun sharingIsExplicitAndAlwaysAuthenticated() {
        val profile=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings(vpnMode=true,
            mixedInboundEnabled=false,lanSharingEnabled=true,mixedAuth=false,
            mixedUsername="sharing-user",mixedPassword="01234567890123456789012345678901")).build(1)
        assertNull(result.error)
        VpnListenerPolicy.check(result.coreConfig,true)
        try { VpnListenerPolicy.check(result.coreConfig);fail("LAN opened without explicit permission") } catch(_:IllegalStateException) {}
        val mixed=JsonInput.parseObject(result.coreConfig).array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="socks" }
        assertEquals("0.0.0.0",mixed.string("listen"))
        assertEquals(1,mixed.array("users").size)
    }
    @Test fun qwdttUsesRuntimeIpv4WithoutChangingGlobalPreferences() {
        val original=GeneratorSettings(vpnMode=true,ipv6Enabled=true,remoteDnsDisableIpv6=false)
        assertTrue(TransportIpPolicy.ipv4Only(Qwdtt(),ProfileProvider { null }))
        assertTrue(TransportIpPolicy.ipv4Only(app.qthrone.mobile.outbound.types.Csqtt(),ProfileProvider { null }))
        assertFalse(TransportIpPolicy.ipv4Only(Socks(),ProfileProvider { null }))
        val effective=TransportIpPolicy.settings(original)
        assertFalse(effective.ipv6Enabled)
        assertTrue(effective.remoteDnsDisableIpv6)
        assertEquals("ipv4_only",TransportIpPolicy.context(BuildContext()).directDomainStrategy())
        val proxy=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val generated=ConfigGenerator(ProfileProvider { proxy },effective,TransportIpPolicy.context(BuildContext())).build(1)
        assertNull(generated.error)
        val config=JsonInput.parseObject(generated.coreConfig)
        val tun=config.array("inbounds").filterIsInstance<JsonObject>().first { it.string("type")=="tun" }
        assertEquals(1,tun.array("address").size)
        assertTrue(config.obj("dns").array("rules").filterIsInstance<JsonObject>().any {
            it.string("action")=="predefined" && it.array("query_type").contains("AAAA")
        })
        assertTrue(original.ipv6Enabled)
        assertFalse(original.remoteDnsDisableIpv6)
    }
    @Test fun browsersFallBackFromQuicAndUnusableIpv6() {
        val proxy=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val config=JsonInput.parseObject(ConfigGenerator(ProfileProvider { proxy },GeneratorSettings(vpnMode=true,ipv6Enabled=false)).build(1).coreConfig)
        assertTrue(config.obj("route").array("rules").filterIsInstance<JsonObject>().any {
            it.string("network")=="udp" && it.string("action")=="reject" && it.int("port")==443
        })
        val first=config.obj("dns").array("rules")[0] as JsonObject
        assertEquals("predefined",first.string("action"))
        assertTrue(first.array("query_type").contains("AAAA"))
    }

    @Test fun quicIsRefusedOnlyOnTheWayToAProxy() {
        val rules=JsonArray()
            .add(jsonObjectOf("action" to "sniff"))
            .add(jsonObjectOf("domain_suffix" to JsonArray.of("example.ru"), "action" to "route", "outbound" to Tags.DIRECT))
            .add(jsonObjectOf("domain_suffix" to JsonArray.of("example.com"), "action" to "route", "outbound" to Tags.PROXY))
        val guarded=guardQuic(rules,1,finalIsProxy=true).filterIsInstance<JsonObject>()
        assertEquals(listOf("sniff","route","reject","route","reject"),guarded.map { it.string("action") })
        assertEquals("logical",guarded[2].string("type"))
        assertEquals(jsonObjectOf("network" to "udp","port" to 443,"action" to "reject"),guarded.last())
        assertEquals(4,guardQuic(rules,1,finalIsProxy=false).size)
        val proxy=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val open=JsonInput.parseObject(ConfigGenerator(ProfileProvider { proxy },GeneratorSettings(vpnMode=true,blockQuic=false)).build(1).coreConfig)
        assertTrue(open.obj("route").array("rules").filterIsInstance<JsonObject>().none { it.int("port")==443 || it.string("type")=="logical" })
    }
}
