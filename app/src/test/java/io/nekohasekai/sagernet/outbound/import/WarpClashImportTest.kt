package app.rawline.mobile.outbound.`import`

import app.rawline.mobile.outbound.types.Masque
import app.rawline.mobile.outbound.types.WireGuard
import org.junit.Assert.*
import org.junit.Test

class WarpClashImportTest {
    @Test fun wireGuardClashKeepsNumericHeadersKeepaliveReservedAndBooleanOptions() {
        val yaml="""
            proxies:
              - name: Options
                type: wireguard
                server: 192.0.2.1
                port: 443
                private-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                pre-shared-key: BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
                ip: 172.16.0.2
                persistent-keepalive: 25
                reserved: [1, 2, 255]
                amnezia-wg-option:
                  h1: 12345
                  random-trailers: 'true'
        """.trimIndent()
        val profile=ProfileImport.parse(yaml).outbounds.single() as WireGuard
        assertEquals("12345",profile.h1)
        assertTrue(profile.random_trailers)
        assertEquals("25",profile.peer.persistent_keepalive)
        assertEquals(listOf(1,2,255),profile.peer.reserved)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",profile.peer.pre_shared_key)
    }

    @Test fun fullTemplateFileImportsBothMasqueAndEveryWireGuardProfile() {
        val document=javaClass.getResourceAsStream("/fixtures/clash-warp-templates.yaml")!!.bufferedReader().use { it.readText() }
        val result=ProfileImport.parse(document)
        assertEquals(result.messages.toString(),15,result.outbounds.size)
        val masque=result.outbounds.filterIsInstance<Masque>()
        val wireguard=result.outbounds.filterIsInstance<WireGuard>()
        assertEquals(2,masque.size)
        assertEquals(13,wireguard.size)
        assertTrue(result.outbounds.all { it.server=="192.0.2.1" && it.serverPort>0 })
        assertEquals(listOf(2,3),masque.map { it.http_version }.sorted())
        assertTrue(masque.all { it.address==listOf("172.16.0.2/32","2606:4700:110:1234::2/128") })
        assertTrue(wireguard.any { it.enable_amnezia && it.content_padding_addition=="30-84" && it.random_trailers && it.disable_cookies })
        for(profile in wireguard) {
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",profile.private_key)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",profile.peer.public_key)
            assertEquals(listOf("172.16.0.2/32","2606:4700:110:1234::2/128"),profile.address)
            val restored=WireGuard()
            assertTrue(restored.parseFromJson(profile.exportToJson()))
            assertEquals(profile.exportToJson().toCompact(),restored.exportToJson().toCompact())
        }
    }
}
