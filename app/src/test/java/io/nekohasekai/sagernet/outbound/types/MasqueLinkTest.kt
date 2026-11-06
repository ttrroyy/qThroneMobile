package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.outbound.`import`.ProfileImport
import app.rawline.mobile.outbound.link.Base64Strict
import org.junit.Assert.*
import org.junit.Test

class MasqueLinkTest {
    private val link="masque://MHcFixture%2Fprivate%2Bkey%3D%3D@192.0.2.1:443?publickey=MFkwFixture%2Fpublic%2Bkey%3D%3D&address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A1234%3A%3A2%2F128&mtu=1280&network=h2&sni=front.example.org#MASQUE%20h2"

    @Test fun linksReachTheSharedImporterWithExactKeysAndHttpMode() {
        for(input in listOf(link,Base64Strict.encode(link))) {
            val result=ProfileImport.parse(input)
            assertEquals(result.messages.toString(),1,result.outbounds.size)
            val profile=result.outbounds.single() as Masque
            assertEquals("MHcFixture/private+key==",profile.private_key)
            assertEquals("MFkwFixture/public+key==",profile.peer_public_key)
            assertEquals(listOf("172.16.0.2/32","2606:4700:110:1234::2/128"),profile.address)
            assertEquals("front.example.org",profile.tls.server_name)
            assertEquals(2,profile.http_version)
            assertEquals("MASQUE h2",profile.name)
            assertTrue(profile.tls.enabled)
            val restored=OutboundFactory.parseLink(profile.exportToLink()) as Masque
            assertEquals(profile.exportToJson().toCompact(),restored.exportToJson().toCompact())
        }
    }

    @Test fun ipv6EndpointsAndVersionFallbackSurviveSharing() {
        val profile=Masque()
        assertTrue(profile.parseFromLink(link.replace("192.0.2.1:443","[2001:db8::1]:8443").replace("network=h2","network=h3")))
        assertEquals("2001:db8::1",profile.server)
        assertTrue(profile.disable_version_fallback)
        profile.disable_version_fallback=false
        val restored=OutboundFactory.parseLink(profile.exportToLink()) as Masque
        assertEquals(3,restored.http_version)
        assertFalse(restored.disable_version_fallback)
        assertEquals(8443,restored.serverPort)
    }

    @Test fun invalidEndpointsAddressesAndParametersAreRejected() {
        for(invalid in listOf(link.replace(":443?",":0?"),link.replace("%2F32%2C","%2F99%2C"),
            link.replace("network=h2","network=unknown"),link.replace("mtu=1280","mtu=bad"),
            link.replace("MHcFixture%2Fprivate%2Bkey%3D%3D",""),link+"&address=1.2.3.4")) {
            // Put duplicate query fields before the fragment rather than inside the name.
            val candidate=if(invalid==link+"&address=1.2.3.4") link.replace("#","&address=1.2.3.4#") else invalid
            assertNull(OutboundFactory.parseLink(candidate))
        }
    }
}
