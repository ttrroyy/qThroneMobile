package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.bg.carrier.CarrierKind
import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ProfileImport
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** stormdns://, cottendns://, soar:// and phobos:// links, as the desktop reads them (qwdtt_import_test.cpp). */
class ObfuscatedTransportLinksTest {
    private fun read(link: String) = ProfileImport.parse(link).outbounds.singleOrNull()
    private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    private val white = """{"schema":"whitedns.profile","version":1,"profile":{"name":"Iran","server":{"domain":"v.example.com","encryption_key":"k3y","encryption_method":2}}}"""

    @Test fun whiteDnsSchemesPickTheirEngine() {
        val storm = read("stormdns://" + b64(white)) as DnsTunnel
        assertEquals(CarrierKind.MASTERDNS, storm.carrierKind)
        val cotten = read("cottendns://" + b64(white)) as DnsTunnel
        assertEquals("cottendns", cotten.type)
        assertEquals(CarrierKind.COTTENDNS, cotten.carrierKind)
        assertEquals(2, cotten.exportToJson().int("encryption"))
        val again = read(cotten.exportToLink()) as DnsTunnel
        assertEquals(cotten.exportToJson(), again.exportToJson())
    }

    @Test fun soarLinkCarriesTheServerKey() {
        val json = """{"schema":"whitedns.profile","version":1,"import_type":"soar","profile":{"name":"Soar IR","resolvers":["1.1.1.1"],
            "soarSettings":{"transport":"auto","discovery":true,"country":"IR"},"server":{"domain":"s.example.com","public_key":"JFHtVsmq5DauWWsc/PbXYS3xd5jCnstAPYEIEVKUC08="}}}"""
        val soar = read("soar://" + b64(json)) as DnsTunnel
        assertEquals(CarrierKind.SOAR, soar.carrierKind)
        val config = soar.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))
        assertEquals("JFHtVsmq5DauWWsc/PbXYS3xd5jCnstAPYEIEVKUC08=", config.string("server_public_key"))
        assertEquals("IR", config.obj("soar").string("country"))
        assertNull(read("soar://" + b64(json.replace("JFHtVsmq5DauWWsc/PbXYS3xd5jCnstAPYEIEVKUC08=", "short"))))
    }

    @Test fun phobosWireGuardLink() {
        val conf = "[Interface]\nPrivateKey = MBrnZoTdyT/LR4XpB7tElSxyVTQdXFw0tvVJOMSL/GI=\nAddress = 10.8.0.4/32\nMTU = none\nDNS = none\n\n" +
            "[Peer]\nPublicKey = g/G4y2XkTY5mPLMYYXXCarvyxUSHUzM1vpIYRHwwFT4=\nPresharedKey = none\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 0\nEndpoint = 127.0.0.1:13255\n\n" +
            "[instance]\nsource-if = 127.0.0.1\nsource-lport = 13255\ntarget = 130.49.185.136:51824\nkey = XR0NEf8MhGAGcCpc\nmasking = MEDIA\nverbose = INFO\nidle-timeout = 300\nmax-dummy = 45\n"
        val wg = read("phobos://" + b64(conf) + "#Mobil-phone") as WireGuard
        assertEquals("130.49.185.136", wg.peer.address)
        assertEquals(51824, wg.peer.port)
        assertEquals("", wg.peer.pre_shared_key)
        assertEquals("Mobil-phone", wg.name)
        val obfs = wg.obfsToJson()
        assertEquals("wg-obfuscator", obfs.string("type"))
        assertEquals("media", obfs.string("masking"))
        assertEquals(45, obfs.int("max_dummy"))
        val again = read(wg.exportToLink()) as WireGuard
        assertEquals(obfs, again.obfsToJson())
        assertEquals(51824, again.peer.port)
    }

    @Test fun phobosSocksLink() {
        val conf = "[instance]\nmode = socks5\nrole = client\nsource-if = 127.0.0.1\nsource-lport = 1080\ntarget = vpn.example.com:51824\n" +
            "key = XR0NEf8MhGAGcCpc\nmasking = TLS\nverbose = error\nmedia-ssrc = 305419896\n\n[socks5]\nlogin = Ab3Kq\npassword = Zx9Lm\n"
        val socks = read("phobos://" + b64(conf) + "#none") as Socks
        assertEquals("vpn.example.com", socks.server)
        assertEquals(51824, socks.serverPort)
        assertEquals("Ab3Kq", socks.username)
        assertEquals("TLS", socks.phobos_masking)
        assertEquals("305419896", socks.phobos_media_ssrc)
        assertEquals("Phobos", socks.displayType())
        val again = read(socks.exportToLink()) as Socks
        assertEquals(socks.exportToJson(), again.exportToJson())
    }

    @Test fun wgobfsKeyTravelsInTheWgLink() {
        val wg = WireGuard().apply {
            private_key = "MBrnZoTdyT/LR4XpB7tElSxyVTQdXFw0tvVJOMSL/GI="
            peer.public_key = "g/G4y2XkTY5mPLMYYXXCarvyxUSHUzM1vpIYRHwwFT4="
            peer.address = "203.0.113.5"; peer.port = 51820
            address = mutableListOf("10.0.0.2/32")
            obfs_type = "wgobfs"; obfs_key = "mysecretkey"
        }
        val again = read(wg.exportToLink()) as WireGuard
        assertEquals("wgobfs", again.obfsType())
        assertEquals("mysecretkey", again.obfs_key)
        wg.enable_amnezia = true
        assertTrue(wg.build(BuildContext()).error.isNotEmpty())
    }
}
