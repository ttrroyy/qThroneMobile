package app.rawline.mobile.route

import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.config.GeneratorSettings
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutingTest {
    @Test fun malformedRoutingDoesNotAbortValidServerProfiles() {
        val server="vless://id@example.org:443"
        val payload=ProviderRouting.subscription("$server\nhapp://routing/onadd/broken","{}")
        assertEquals(server,payload.body)
        assertTrue(payload.commands.isEmpty()) // No off command, so the stored route survives.
        assertEquals(2,payload.warnings.size)
    }

    @Test fun validRoutingSurvivesAnotherDamagedDocument() {
        val good="happ://routing/onadd/"+Base64Strict.encode(jsonObjectOf("Name" to "Good","GlobalProxy" to true).toCompact())
        val payload=ProviderRouting.subscription("vless://id@example.org:443\n$good\nhapp://routing/onadd/broken")
        assertEquals("Good",payload.commands.single().document!!.string("Name"))
        assertEquals(1,payload.warnings.size)
    }
    @Test fun browserLauncherImportsTheEmbeddedRouteInsteadOfItsHtml() {
        val direct="happ://routing/onadd/"+Base64Strict.encode(jsonObjectOf("Name" to "DigneZzz (lite)","GlobalProxy" to "true","DirectSites" to JsonArray.of("geosite:private")).toCompact())
        val wrapped="https://r.far.ovh/?url="+java.net.URLEncoder.encode(direct,"UTF-8")
        assertTrue(ProviderRouting.isLink(wrapped))
        val command=ProviderRouting.parse(wrapped)
        assertEquals("DigneZzz (lite)",command.document!!.string("Name"))
        assertTrue(command.activate)
        assertTrue(ProviderRouting.subscription("vless://id@example.org:443\n$wrapped").commands.single().activate)
        assertFalse(ProviderRouting.isLink("https://example.org/?url=https%3A%2F%2Fexample.org%2Fsubscription"))
    }

    @Test fun truncatedUserLinkFailsWithCopyWholeLinkMessage() {
        val incomplete="https://r.far.ovh/?url=happ%3A%2F%2Frouting%2Fonadd%2FeyJOYW1lIjoiRGlnbmVaelogKGxpdGUpIiwiR2xvYmFsUHJveHkiOiJ0cnVlIiwiVXNlQ2h1bmtGaWxlcyI6ImZhbHNlIiwiUmVtb3RlRG5zI"
        assertTrue(ProviderRouting.isLink(incomplete))
        val result=runCatching { ProviderRouting.parse(incomplete) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Скопируйте ссылку целиком"))
    }
    @Test fun ignoreImportDoesNotDiscardServersOnMalformedRoute() {
        val payload = ProviderRouting.subscription("vless://id@example.org:443\nhapp://routing/onadd/broken","not-valid-json",ignoreRouting = true)
        assertEquals("vless://id@example.org:443",payload.body)
        assertTrue(payload.commands.isEmpty())
    }
    @Test fun routingOrderChangesRulePriority() {
        val doc = jsonObjectOf("Name" to "Ordering","RouteOrder" to "direct-proxy-block", "DirectSites" to JsonArray.of("full:example.org"),"ProxySites" to JsonArray.of("full:example.org"),"BlockSites" to JsonArray.of("full:example.org"))
        val rules = ProviderRouting.profile(doc,"Happ").rules.drop(1)
        assertEquals(listOf(OutboundIds.DIRECT,OutboundIds.PROXY,OutboundIds.BLOCK),rules.map { it.outbound_id })
        doc["RouteOrder"] = "invalid"
        assertEquals(OutboundIds.BLOCK,ProviderRouting.profile(doc,"Happ").rules[1].outbound_id)
    }
    @Test fun incyLegacyAndHeaderLinksKeepProvider() {
        val encoded = Base64Strict.encode(jsonObjectOf("Name" to "RU", "GlobalProxy" to "false").toCompact())
        assertTrue(ProviderRouting.isLink("://routing/onadd/$encoded"))
        assertEquals("Incy",ProviderRouting.parse("://routing/onadd/$encoded","Incy").provider)
        assertEquals("RU",ProviderRouting.parse("other://routing/onadd/$encoded","Incy").document!!.string("Name"))
    }
    @Test fun dnsBootstrapAddressIsPreserved() {
        val settings = ProviderRouting.settings(jsonObjectOf("RemoteDNSType" to "DoH","RemoteDNSDomain" to "https://resolver.example/dns-query","RemoteDNSIP" to "192.0.2.53"),GeneratorSettings())
        assertTrue(settings.dnsPredefinedRules.contains("192.0.2.53 resolver.example"))
    }
    @Test fun embeddedRoutesAreSeparatedFromServers() {
        val document = jsonObjectOf("Name" to "Provider", "GlobalProxy" to "false", "DirectSites" to JsonArray.of("geosite:ru", "full:example.org"))
        val route = "incy://routing/onadd/" + Base64Strict.encode(document.toCompact())
        val body = "vless://id@example.org:443\n$route"
        val payload = ProviderRouting.subscription(Base64Strict.encode(body))
        assertEquals("vless://id@example.org:443",payload.body)
        assertEquals("Incy",payload.commands.single().provider)
        assertTrue(payload.commands.single().activate)
        val profile = ProviderRouting.profile(payload.commands.single().document!!,"Incy")
        assertEquals(OutboundIds.DIRECT,profile.default_outbound_id)
        assertTrue(profile.rules.last().domain.contains("example.org"))
        assertTrue(profile.rules.last().rule_set.contains("geosite:ru"))
    }
    @Test fun dnsAndBlockedAddressesArePreserved() {
        val document = jsonObjectOf("GlobalProxy" to true,"RemoteDNSType" to "DoH","RemoteDNSDomain" to "https://dns.example/dns-query","BlockIp" to JsonArray.of("192.0.2.1"),"FakeDNS" to "true")
        val settings = ProviderRouting.settings(document,GeneratorSettings())
        assertEquals("https://dns.example/dns-query",settings.remoteDns)
        assertTrue(settings.fakeDns)
        val blocked = ProviderRouting.profile(document,"Happ").rules.last()
        assertEquals(OutboundIds.BLOCK,blocked.outbound_id)
        assertEquals(listOf("192.0.2.1/32"),blocked.ip_cidr)
    }
    @Test fun headerOffDoesNotRemoveServers() {
        val payload = ProviderRouting.subscription("vless://id@example.org:443","off","Incy")
        assertTrue(payload.commands.single().off)
        assertTrue(payload.body.startsWith("vless://"))
    }
}
