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

    @Test fun cottenLegacyPresetReachesTheEngine() {
        val json = """{"schema":"whitedns.profile","version":1,"profile":{"name":"Cotten","server":{"domain":"cot.example.com","encryption_key":"k3y","encryption_method":5},
            "cottenSettings":{"tunnelMode":"legacy","configPreset":"master-storm","transportMode":"preset","deliveryMode":"preset","qnameMode":"preset","ipMode":"preset"}}}"""
        val cotten = read("cottendns://" + b64(json)) as DnsTunnel
        val options = cotten.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass")).obj("options")
        assertEquals(true, options["LEGACY_SESSION_ID"])
        assertEquals(false, options["DNS_EDNS_COOKIE"])
        assertEquals("udp", options.string("RESOLVER_TRANSPORT"))
        assertEquals("default", options.string("CONFIG_PRESET"))
        val again = read(cotten.exportToLink()) as DnsTunnel
        assertEquals(cotten.exportToJson(), again.exportToJson())
        assertEquals("master-storm", again.exportToJson().obj("cotten").string("configPreset"))
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

    @Test fun forkImportLinkRunsTheForkClient() {
        val json = """{"name":"Board","mode":"key","control_url":"https://203.0.113.10","key_token":"key_example0000000000",""" +
            """"doc_url":"https://boards.yandex.ru/whiteboard/?hash=0123456789abcdef0123456789abcdef","transport":"boards","e2e_encryption":true}"""
        val link = "openflux://import?data=" + b64(json)
        val key = read(link) as OpenFluxKey
        assertEquals(CarrierKind.OPENFLUXKEY, key.carrierKind)
        val config = key.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))
        assertEquals("boards", config.string("transport"))
        assertEquals("https://boards.yandex.ru/whiteboard/?hash=0123456789abcdef0123456789abcdef", config.string("doc_url"))
        assertFalse(config.contains("e2e_encryption"))
        val again = read(key.exportToLink()) as OpenFluxKey
        assertEquals(key.exportToJson(), again.exportToJson())
        assertTrue(read("openflux://import?data=" + b64("""{"transport":"boards","doc_url":"http://boards.yandex.ru/x"}""")) == null)
    }

    @Test fun warpAmneziaConfKeepsBothAddresses() {
        val conf = "[Interface]\nAddress = 172.16.0.2/32\nAddress = 2606:4700:110:827c:dcbb:200:a6e7:fbc4/128\n" +
            "PrivateKey = h+tGZJvVA5XBtvBeR9aMZ71ZuN8m6+CNawC+8KnVGbU=\nMTU = 1420\nJc = 4\nJmin = 40\nJmax = 70\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n" +
            "I1 = <b 0xc7000000010809a1ed4edbbe7615>\n\n[Peer]\nPublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=\n" +
            "Endpoint = 8.6.112.9:500\nAllowedIPs = 0.0.0.0/0, ::/0\n"
        val wg = read(conf) as WireGuard
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:827c:dcbb:200:a6e7:fbc4/128"), wg.address)
        assertEquals("<b 0xc7000000010809a1ed4edbbe7615>", wg.i1)
        assertEquals(500, wg.peer.port)
        assertTrue(wg.build(BuildContext()).error.isEmpty())
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
