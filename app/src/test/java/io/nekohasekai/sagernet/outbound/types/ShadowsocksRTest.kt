package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.outbound.config.*
import app.rawline.mobile.outbound.import.ProfileImport
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.Base64Strict
import org.junit.Assert.*
import org.junit.Test

class ShadowsocksRTest {
    private fun profile() = ShadowsocksR().apply {
        server = "2001:db8::1"; serverPort = 8388; password = "пароль:+/"
        name = "Тест SSR"; protocol = "auth_aes128_sha1"; protocolParam = "42:secret"
        obfs = "tls1.2_ticket_auth"; obfsParam = "example.com"
    }

    @Test fun shareAndSubscriptionFormatsKeepEveryParameter() {
        val profile = profile()
        val json = profile.exportToJson().toCompact()
        val legacy = profile.exportToJson().apply { remove("type") }.toCompact()
        val formats = listOf(profile.exportToLink(), json, "[$json]", "{\"outbounds\":[$json]}",
            "{\"configs\":[$legacy]}", Base64Strict.encode(profile.exportToLink()), profile.exportJsonLink())
        for (format in formats) {
            val restored = ProfileImport.parse(format).outbounds.single() as ShadowsocksR
            assertEquals(format, json, restored.exportToJson().toCompact())
        }
        assertTrue(OutboundFactory.fromJson(profile.exportToJson()) is ShadowsocksR)
        val clash = """proxies:
          - {name: SSR, type: ssr, server: 192.0.2.1, port: 8388, cipher: aes-256-cfb, password: secret, protocol: auth_chain_a, protocol-param: '42:secret', obfs: http_simple, obfs-param: example.com}
        """.trimIndent()
        val imported = ProfileImport.parse(clash).outbounds.single() as ShadowsocksR
        assertEquals("42:secret", imported.protocolParam)
        assertEquals("example.com", imported.obfsParam)
        assertFalse(ShadowsocksR().parseFromLink("ssr://invalid!"))
        assertFalse(ShadowsocksR().parseFromJson(profile.exportToJson().apply { this["method"] = "aes-256-gcm" }))
    }

    @Test fun nativeChainsUseSsrAtEitherEnd() {
        for (reverse in listOf(false, true)) {
            val ssr = profile()
            val ss = Shadowsocks().apply { server="192.0.2.2";serverPort=8388;method="aes-256-gcm";password="secret" }
            val chain = Chain().apply { list=if(reverse) arrayListOf(2,1) else arrayListOf(1,2) }
            val profiles = mapOf<Long, Outbound>(1L to ssr, 2L to ss, 3L to chain)
            val result = ConfigGenerator(ProfileProvider { profiles[it] }, GeneratorSettings()).build(3)
            assertNull(result.error)
            val outbounds = JsonInput.parseObject(result.coreConfig).array("outbounds").filterIsInstance<JsonObject>()
            val proxy = outbounds.first { it.string("tag") == "proxy" }
            assertEquals(if(reverse) "shadowsocksr" else "shadowsocks", proxy.string("type"))
            assertTrue(outbounds.any { it.string("tag") == proxy.string("detour") })
        }
    }
}
