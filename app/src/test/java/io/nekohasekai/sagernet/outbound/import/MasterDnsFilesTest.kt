package app.qthrone.mobile.outbound.`import`

import app.qthrone.mobile.outbound.types.DnsTunnel
import org.junit.Assert.*
import org.junit.Test

class MasterDnsFilesTest {
    private fun single(text: String): DnsTunnel = ProfileImport.parse(text).outbounds.single() as DnsTunnel

    @Test fun clientConfigTomlBecomesAMasterDnsProfile() {
        val profile = single("""
            # MasterDnsVPN client
            DOMAINS = ["t.example.com", "t2.example.com"]
            ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"  # shared key
            DATA_ENCRYPTION_METHOD = 2
            LISTEN_PORT = 10800
        """.trimIndent())
        assertEquals("masterdns", profile.type)
        assertEquals("t.example.com,t2.example.com", profile.exportToJson().string("domain"))
        assertEquals("0123456789abcdef0123456789abcdef", profile.exportToJson().string("mkey"))
        assertEquals(2, profile.exportToJson().int("encryption"))
    }

    @Test fun masterDnsJsonAndWhiteDnsProfileFiles() {
        val json = single("""{"DOMAINS":["t.example.com"],"ENCRYPTION_KEY":"secret-key-0001","PROTOCOL_TYPE":"SOCKS5"}""")
        assertEquals("t.example.com", json.exportToJson().string("domain"))
        assertEquals(1, json.exportToJson().int("encryption"))
        val white = single("""{"schema":"whitedns.profile","profile":{"name":"Home","server":{"domain":"w.example.com","encryption_key":"white-key-0001","encryption_method":3}}}""")
        assertEquals("Home", white.name)
        assertEquals("w.example.com", white.exportToJson().string("domain"))
        assertEquals(3, white.exportToJson().int("encryption"))
    }

    @Test fun incompleteConfigsAreNotMasterDns() {
        assertNull(MasterDnsFiles.fromToml("DOMAINS = []\nENCRYPTION_KEY = \"k\""))
        assertNull(MasterDnsFiles.fromToml("DOMAINS = [\"a.example\"]"))
    }

    @Test fun nativeOptionsKeepTypesThroughImportAndSharing() {
        val toml = """
            DOMAINS = [
                'a.example.com', # primary
                "b.example.com",
            ]
            ENCRYPTION_KEY = 'secret#key'
            MTU_TEST_TIMEOUT = 2.5
            PACKET_DUPLICATION_COUNT = 3
            BASE_ENCODE_DATA = false
            SOCKS5_PASS = "local-secret"
            LOCAL_DNS_ENABLED = true
        """.trimIndent()
        val json = """{"DOMAINS":["a.example.com","b.example.com"],"ENCRYPTION_KEY":"secret#key",
            "MTU_TEST_TIMEOUT":2.5,"PACKET_DUPLICATION_COUNT":3,"BASE_ENCODE_DATA":false,
            "SOCKS5_PASS":"local-secret","LOCAL_DNS_ENABLED":true}"""
        for (input in listOf(toml, json)) {
            val profile = single(input)
            val options = profile.exportToJson().obj("options")
            assertEquals("secret#key", profile.exportToJson().string("mkey"))
            assertEquals(2.5, (options["MTU_TEST_TIMEOUT"] as Number).toDouble(), 0.0)
            assertEquals(3, options.int("PACKET_DUPLICATION_COUNT"))
            assertEquals(false, options["BASE_ENCODE_DATA"])
            assertFalse(options.contains("SOCKS5_PASS"))
            assertFalse(options.contains("LOCAL_DNS_ENABLED"))
            assertEquals(profile.exportToJson().toCompact(), single(profile.exportToLink()).exportToJson().toCompact())
        }
    }
}
