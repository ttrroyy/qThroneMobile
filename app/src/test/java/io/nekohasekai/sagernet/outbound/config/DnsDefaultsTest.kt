package app.rawline.mobile.outbound.config

import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.outbound.types.XrayVless
import app.rawline.mobile.outbound.types.Custom
import org.junit.Assert.*
import org.junit.Test

class DnsDefaultsTest {
    @Test fun freshAndUnsetUpgradeDefaultsUseCloudflareWithNoDnsBootstrap() {
        assertEquals(DnsDefaults.REMOTE,SettingsRegistry.REMOTE_DNS.default)
        val dns=remote(GeneratorSettings())
        assertEquals("https",dns.string("type"))
        assertEquals("1.1.1.1",dns.string("server"))
        assertEquals("/dns-query",dns.string("path"))
        assertEquals("proxy",dns.string("detour"))
    }

    @Test fun explicitResolversStayIntactIncludingGoogleAndPrivateDoh() {
        for(address in listOf("https://8.8.8.8/dns-query","https://private.example/custom-query","tcp://9.9.9.9:5353")) {
            val settings=GeneratorSettings(remoteDns=SettingsRegistry.REMOTE_DNS.decode(address)!!)
            val expected=DnsServers.buildDnsObj(address)
            val dns=remote(settings)
            assertEquals(expected.string("server"),dns.string("server"))
            assertEquals(expected.string("type"),dns.string("type"))
            assertEquals(expected.string("path"),dns.string("path"))
            assertEquals(expected.int("server_port"),dns.int("server_port"))
        }
    }

    @Test fun xrayUdpUpgradePreservesKnownProvidersAndUsesCloudflareForUnknown() {
        val xray=XrayVless().apply { server="192.0.2.1";serverPort=443;uuid="5a351653-bef1-4baf-a6e9-89cbf1a2329e" }
        val result=ConfigGenerator(ProfileProvider { xray },GeneratorSettings(remoteDns="203.0.113.53")).build(1)
        assertNull(result.error)
        val dns=JsonInput.parseObject(result.coreConfig).obj("dns").array("servers").filterIsInstance<JsonObject>().first { it.string("tag")=="dns-remote" }
        assertEquals("1.1.1.1",dns.string("server"))
        assertEquals("https://8.8.8.8/dns-query",DnsServers.upgradeUdpDnsToDoH("8.8.8.8"))
        assertEquals("https://9.9.9.9/dns-query",DnsServers.upgradeUdpDnsToDoH("9.9.9.9"))
    }

    @Test fun fullSubscriptionJsonRetainsItsOwnDns() {
        val original="""{"dns":{"servers":[{"type":"https","tag":"provider-dns","server":"dns.provider.example","path":"/private-dns"}],"final":"provider-dns"},"outbounds":[{"type":"socks","tag":"proxy","server":"192.0.2.1","server_port":1080}],"route":{"final":"proxy"}}"""
        val profile=Custom().apply { subtype=Custom.CUSTOM_FULL_CONFIG;config=original }
        val result=ConfigGenerator(ProfileProvider { profile },GeneratorSettings()).build(1)
        assertNull(result.error)
        val expected=JsonInput.parseObject(original).obj("dns").toCompact()
        assertEquals(expected,JsonInput.parseObject(result.coreConfig).obj("dns").toCompact())
        assertEquals(original,profile.config)
    }

    private fun remote(settings:GeneratorSettings):JsonObject {
        val profile=Socks().apply { server="192.0.2.1";serverPort=1080 }
        val result=ConfigGenerator(ProfileProvider { profile },settings).build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig).obj("dns").array("servers").filterIsInstance<JsonObject>().first { it.string("tag")=="dns-remote" }
    }
}
