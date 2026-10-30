package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.`import`.ProfileImport
import org.junit.Assert.*
import org.junit.Test

class WdttPlusLinkTest {
    private val hash = "abcDEF123_-xyz789"

    @Test fun connectLinksBecomeWdttPlusProfiles() {
        val result = ProfileImport.parse("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p%40ss&hashes=$hash&name=Home&max_workers=36")
        val profile = result.outbounds.single() as WdttPlus
        assertEquals("WDTT Plus", profile.displayType())
        assertEquals("192.0.2.5", profile.server)
        assertEquals(56000, profile.serverPort)
        assertEquals("p@ss", profile.password)
        assertEquals(listOf(hash), profile.hashes)
        assertEquals("Home", profile.name)
        assertEquals(36, profile.workers)
        val again = WdttPlus().apply { assertTrue(parseFromLink(profile.exportToLink())) }
        // The device id is per install and never travels in a link.
        assertEquals(profile.exportToJson().also { it.remove("device_id") }.toCompact(), again.exportToJson().also { it.remove("device_id") }.toCompact())
    }

    @Test fun plainWdttLinksRunOnWdttPlus() {
        val profile = ProfileImport.parse("wdtt://31.76.50.112:56000:56001:9000:secret:$hash").outbounds.single() as WdttPlus
        assertEquals("WDTT", profile.displayType())
        assertEquals("wdtt://31.76.50.112:56000:56001:9000:secret:$hash", profile.exportToLink())
        assertTrue(WdttPlus().apply { assertTrue(parseFromJson(profile.exportToJson())) }.legacy)
        assertEquals("31.76.50.112", profile.server)
        assertEquals(56000, profile.serverPort)
        assertEquals("secret", profile.password)
        assertEquals(listOf(hash), profile.hashes)
        val panel = ProfileImport.parse("wdtt://31.76.50.112:56000:56001:0:secret:$hash#NL").outbounds.single() as WdttPlus
        assertEquals(9000, panel.localPort)
        assertEquals("NL", panel.name)
    }

    @Test fun panelJsonSubscriptionsImportAsWdtt() {
        val payload = """{"vpn":"VPN","name":"NL","ip":"192.0.2.7","dtls":56010,"raw":56013,"pass":"tg_1","hash":"$hash","did":"dev-1"}"""
        val link = "wdtt://" + java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        val profile = ProfileImport.parse(java.util.Base64.getEncoder().encodeToString(link.toByteArray())).outbounds.single() as WdttPlus
        assertTrue(profile.legacy)
        assertEquals("192.0.2.7", profile.server)
        assertEquals(56010, profile.serverPort)
        assertEquals(56011, profile.wgPort)
        assertEquals(9000, profile.localPort)
        assertEquals("tg_1", profile.password)
        assertEquals(listOf(hash), profile.hashes)
        assertEquals("NL", profile.name)
        assertEquals("dev-1", profile.deviceId)
    }

    @Test fun startsLikeTheOriginalApp() {
        val profile = WdttPlus()
        assertTrue(profile.parseFromLink("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p&hashes=$hash"))
        val config = profile.privateConfig(1080, 1081, app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials("u", "pw"))
        val args = config.array("args").strings()
        for (flag in listOf("-startup-config-stdin=true", "-socks-auth=true", "-vkcalls-preflight=true", "-config-first-start=true"))
            assertTrue(flag, flag in args)
        assertEquals("18", args[args.indexOf("-n") + 1])
        assertEquals("socks5", args[args.indexOf("-mode") + 1])
        assertEquals("ya.ru", args[args.indexOf("-turn-sni") + 1])
        assertEquals("pw", config.obj("start").string("socks_password"))
    }

    @Test fun connectionSettingsSurviveStorageAndUseOriginalNativeFlags() {
        val profile = WdttPlus().apply {
            assertTrue(parseFromLink("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p&hashes=$hash"))
            tcpOnly = true; turnSni = "turn.example.org"; rtMasque = true; rtMasqueServerBootstrap = true; workers = 36
        }
        val restored = WdttPlus().apply { assertTrue(parseFromJson(profile.exportToJson())) }
        val args = restored.privateConfig(1080,1081,app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials("u","pw")).array("args").strings()
        assertTrue("-turn-stream-first=true" in args)
        assertTrue("-rt-masque=true" in args)
        assertEquals("turn.example.org",args[args.indexOf("-turn-sni")+1])
        assertEquals("auto",args[args.indexOf("-transport-experiment")+1])
        assertFalse(args.any { it == "p" || it == hash || it == "pw" })
        assertTrue(restored.privateConfig(1080,1081,app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials("u","pw")).bool("masque_server_bootstrap"))
        assertEquals(profile.exportToJson().toCompact(),restored.exportToJson().toCompact())
    }

    @Test fun defaultTurnSniMatchesOriginalAndExplicitDisableSurvivesStorage() {
        assertEquals("ya.ru", WdttPlus().turnSni)
        val profile = WdttPlus().apply {
            assertTrue(parseFromLink("wdtt://connect?v=1&host=192.0.2.5&dtls=56000&wg=56001&local=9000&password=p&hashes=$hash"))
        }
        val stored = profile.exportToJson()
        stored.remove("turn_sni")
        assertEquals("ya.ru", WdttPlus().apply { assertTrue(parseFromJson(stored)) }.turnSni)
        stored["turn_sni"] = ""
        val disabled = WdttPlus().apply { assertTrue(parseFromJson(stored)) }
        assertEquals("", disabled.turnSni)
        assertFalse("-turn-sni" in disabled.privateConfig(1080,1081,app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials("u","pw")).array("args").strings())
    }

    @Test fun invalidExternalSniIsRejectedBeforeNativeLaunch() {
        assertEquals("",WdttPlus.normalizeTurnSni(""))
        assertEquals("turn.example.org",WdttPlus.normalizeTurnSni(" TURN.EXAMPLE.ORG "))
        for (value in listOf("127.0.0.1","localhost","https://example.org","-a.example.org","a..example.org","example.org:443","example.org\n-secret"))
            assertNull(value,WdttPlus.normalizeTurnSni(value))
        assertEquals(108,WdttPlus.normalizeWorkers(Int.MAX_VALUE,0))
        assertEquals(36,WdttPlus.normalizeWorkers(108,36))
    }
}
