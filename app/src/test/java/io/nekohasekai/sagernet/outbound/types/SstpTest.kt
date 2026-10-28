package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.bg.carrier.CarrierKind
import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.`import`.ProfileImport
import app.qthrone.mobile.outbound.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SstpTest {
    @Test fun serverDnsIsOptInAndSurvivesLinksJsonAndPrivateConfig() {
        val profile = Sstp().apply { assertTrue(parseFromLink("sstp://user:pass@vpn.example.org")) }
        assertFalse(profile.useServerDns)
        profile.useServerDns = true
        val restored = Sstp().apply { assertTrue(parseFromLink(profile.exportToLink())) }
        assertTrue(restored.useServerDns)
        assertTrue(Sstp().apply { assertTrue(parseFromJson(restored.exportToJson())) }.useServerDns)
        assertTrue(restored.privateConfig(19001,19002,CarrierBridgeCredentials("user","pass")).bool("use_server_dns"))
        assertTrue(restored.exportIdentity().bool("use_server_dns"))
    }
    @Test fun ipv6AndEmbeddedPortAreUnambiguousAndInvalidInputsAreRejected() {
        assertEquals("2001:db8::7" to null,Sstp.cleanServerAddress("2001:db8::7"))
        assertEquals("2001:db8::7" to 8443,Sstp.cleanServerAddress("https://[2001:db8::7]:8443/"))
        assertEquals("vpn.example.com" to 8443,Sstp.cleanServerAddress("https://vpn.example.com:8443/"))
        for(link in listOf("sstp://u:p@host:70000","sstp://u:p@host:0","sstp://u@host","sstp://:p@host"))
            assertTrue(link,ProfileImport.parse(link).outbounds.isEmpty())
        val links="sstp://u:p@host#first\nsstp://a:b@[2001:db8::7]:8443#second"
        assertEquals(2,ProfileImport.parse(links).outbounds.size)
        val encoded=java.util.Base64.getEncoder().encodeToString(links.toByteArray())
        assertEquals(2,ProfileImport.parse(encoded).outbounds.size)
    }
    @Test
    fun testParseFromLinkAndExport() {
        val link = "sstp://alice:Secret123@vpn.example.com:443?sni=vpn.example.com&insecure=1#Office"
        val outbounds = ProfileImport.parse(link).outbounds
        assertEquals(1, outbounds.size)
        val sstp = outbounds.single() as Sstp

        assertEquals("sstp", sstp.type)
        assertEquals(CarrierKind.SSTP, sstp.carrierKind)
        assertEquals("Office", sstp.name)
        assertEquals("vpn.example.com", sstp.server)
        assertEquals(443, sstp.serverPort)
        assertEquals("alice", sstp.username)
        assertEquals("Secret123", sstp.password)
        assertEquals("vpn.example.com", sstp.sni)
        assertTrue(sstp.allowInsecure)

        val exportedLink = sstp.exportToLink()
        assertTrue(exportedLink.startsWith("sstp://"))
        assertTrue(exportedLink.contains("alice:Secret123@vpn.example.com"))
        assertTrue(exportedLink.contains("sni=vpn.example.com"))
        assertTrue(exportedLink.contains("insecure=1"))
        assertTrue(exportedLink.endsWith("#Office"))

        // Re-read exported link
        val reread = ProfileImport.parse(exportedLink).outbounds.single() as Sstp
        assertEquals("Office", reread.name)
        assertEquals("vpn.example.com", reread.server)
        assertEquals(443, reread.serverPort)
        assertEquals("alice", reread.username)
        assertEquals("Secret123", reread.password)
        assertTrue(reread.allowInsecure)
    }

    @Test
    fun testJsonRoundTripAndPrivateConfig() {
        val sstp = Sstp().apply {
            name = "Home SSTP"
            server = "192.0.2.1"
            serverPort = 8443
            username = "bob"
            password = "password123"
            sni = "secure.local"
            allowInsecure = false
        }

        val json = sstp.exportToJson()
        assertEquals("sstp", json.string("type"))
        assertEquals("Home SSTP", json.string("name"))
        assertEquals("192.0.2.1", json.string("server"))
        assertEquals(8443, json.int("server_port"))
        assertEquals("bob", json.string("username"))
        assertEquals("password123", json.string("password"))
        assertEquals("secure.local", json.string("sni"))
        assertFalse(json.contains("allow_insecure"))

        val parsed = OutboundFactory.newByType("sstp") as Sstp
        assertTrue(parsed.parseFromJson(json))
        assertEquals("Home SSTP", parsed.name)
        assertEquals("192.0.2.1", parsed.server)
        assertEquals(8443, parsed.serverPort)
        assertEquals("bob", parsed.username)
        assertEquals("password123", parsed.password)

        val creds = CarrierBridgeCredentials("bridge_user", "bridge_pass")
        val privateConfig = parsed.privateConfig(12345, 12346, creds)
        assertEquals("sstp", privateConfig.string("backend"))
        assertEquals("127.0.0.1:12345", privateConfig.string("socks"))
        assertEquals("bridge_user", privateConfig.string("socks_user"))
        assertEquals("bridge_pass", privateConfig.string("socks_pass"))
    }

    @Test
    fun testValidation() {
        val invalidNoServer = Sstp().apply {
            server = ""
            username = "user"
        }
        assertFalse(invalidNoServer.parseFromJson(JsonObject().also {
            it["type"] = "sstp"
            it["server"] = ""
            it["username"] = "user"
        }))

        val invalidNoUser = Sstp().apply {
            server = "vpn.example.com"
            username = ""
        }
        assertFalse(invalidNoUser.parseFromJson(JsonObject().also {
            it["type"] = "sstp"
            it["server"] = "vpn.example.com"
            it["username"] = ""
        }))
    }
}
