package app.rawline.mobile.outbound.types

import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.bg.carrier.CarrierKind
import app.rawline.mobile.outbound.import.ProfileImport
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Links of the dnst:// specification (net2share/dnst-url-spec EXAMPLES.md) and of the apps that predate it. */
class DnsTunnelLinkTest {
    private val key = "a5d214d305ad581b00b53d1887f99d8d3093ff5a680762c6ca8e81aaaa69ad08"
    private fun read(link: String) = ProfileImport.parse(link).outbounds.singleOrNull() as DnsTunnel?
    private fun config(profile: DnsTunnel) = profile.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))

    @Test fun readableDnstLinksOfEveryTransport() {
        val dnstt = read("dnst://t.example.com/dnstt/ssh?pubkey=$key&user=tunnel&password=secret#my-tunnel")!!
        assertEquals("dnstt", dnstt.type)
        assertEquals(CarrierKind.DNSTT, dnstt.carrierKind)
        assertEquals("my-tunnel", dnstt.name)
        assertEquals("ssh", config(dnstt).obj("backend").string("type"))
        assertEquals("tunnel", config(dnstt).obj("backend").string("user"))

        val vaydns = read("dnst://t.example.com/vaydns/socks?pubkey=$key&record-type=txt&clientid-size=2&keepalive=10s&idle-timeout=60s#my-tunnel")!!
        assertEquals(CarrierKind.VAYDNS, vaydns.carrierKind)
        assertEquals(2, config(vaydns).int("clientid_size"))
        assertEquals("60s", config(vaydns).string("idle_timeout"))
        assertTrue(read("dnst://t.example.com/vaydns/socks?pubkey=$key&dnstt-compat=true")!!.let { config(it).bool("dnstt_compat") })

        val slipstream = read("dnst://a.example.com/slipstream/shadowsocks?cert=MIIBkTCB&method=aes-256-gcm&password=secret#my-tunnel")!!
        assertEquals(CarrierKind.SLIPSTREAM, slipstream.carrierKind)
        assertEquals("MIIBkTCB", config(slipstream).string("cert"))

        val master = read("dnst://v.example.com/masterdns/socks?mkey=abcdef1234&encryption=5#my-tunnel")!!
        assertEquals(CarrierKind.MASTERDNS, master.carrierKind)
        assertEquals(5, config(master).int("encryption"))
        assertEquals("abcdef1234", config(master).string("mkey"))
    }

    @Test fun base64JsonFormFromTheSpecification() {
        val link = "dnst://eyJ0YWciOiJteS10dW5uZWwiLCJ0cmFuc3BvcnQiOnsidHlwZSI6InNsaXBzdHJlYW0iLCJkb21haW4iOiJhLmV4YW1wbGUuY29tIiwiY2VydCI6Ii0tLS0tQkVHSU4gQ0VSVElGSUNBVEUtLS0tLVxuTUlJQmtUQ0IuLi5cbi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0ifSwiYmFja2VuZCI6eyJ0eXBlIjoic2hhZG93c29ja3MiLCJtZXRob2QiOiJhZXMtMjU2LWdjbSIsInBhc3N3b3JkIjoic2VjcmV0In19"
        val profile = read(link)!!
        assertEquals("slipstream", profile.type)
        assertEquals("my-tunnel", profile.name)
        assertTrue(config(profile).string("cert").startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals("aes-256-gcm", config(profile).obj("backend").string("method"))
    }

    @Test fun invalidProfilesAreRejected() {
        assertNull(read("dnst://t.example.com/dnstt/socks?pubkey=ab3f...c8"))
        assertNull(read("dnst://t.example.com/dnstt/ssh?pubkey=$key"))
        assertNull(read("dnst://t.example.com/smoke-signals/socks?pubkey=$key"))
    }

    @Test fun slipNetExportsOfDnsTunnels() {
        fun slipnet(vararg fields: String) = "slipnet://" + Base64.getEncoder().encodeToString(fields.joinToString("|").toByteArray())
        val dnstt = read(slipnet("28", "dnstt", "Home", "t.example.com", "8.8.8.8:53:0,1.1.1.1:53:0", "0", "5000", "bbr", "1080",
            "127.0.0.1", "0", key, "proxyuser", "proxypass"))!!
        assertEquals("dnstt", dnstt.type)
        assertEquals("Home", dnstt.name)
        assertEquals(listOf("8.8.8.8:53", "1.1.1.1:53"), config(dnstt).array("resolvers").strings())
        assertEquals("proxyuser", config(dnstt).obj("backend").string("user"))
        val fields = MutableList(50) { "" }
        listOf("28", "ss", "Slip", "s.example.com", "9.9.9.9:53:1", "1", "400", "dcubic", "1080", "127.0.0.1", "0").forEachIndexed { i, v -> fields[i] = v }
        val slip = read("slipnet://" + Base64.getEncoder().encodeToString(fields.joinToString("|").toByteArray()))!!
        assertEquals("slipstream", slip.type)
        assertTrue(config(slip).bool("authoritative"))
        assertEquals("dcubic", config(slip).string("congestion_control"))
    }

    @Test fun cottenLinkWithoutSettingsStillGetsTheClientSettings() {
        val json=jsonObjectOf("schema" to "whitedns.profile","version" to 1,"profile" to jsonObjectOf("name" to "x",
            "server" to jsonObjectOf("domain" to "t.example.com","encryption_key" to "k","encryption_method" to 5)))
        val tunnel=DnsTunnel()
        assertTrue(tunnel.parseFromLink("cottendns://"+Base64Strict.encode(json.toCompact(),urlSafe=true,padding=false)))
        val config=tunnel.privateConfig(1080,0,CarrierBridgeCredentials("u","p"))
        val options=config.obj("options")
        assertEquals(300,options.int("MIN_DOWNLOAD_MTU"))
        assertEquals("resolvers",options.string("STARTUP_MODE"))
        assertTrue(options.bool("FAST_CONNECT"))
        assertTrue("8.8.8.8" in config.array("system_resolvers").strings())
        // The profile's own options still win over the defaults.
        val own=DnsTunnel()
        assertTrue(own.parseFromJson(jsonObjectOf("type" to "cottendns","domain" to "t.example.com","mkey" to "k","encryption" to 5,"options" to jsonObjectOf("MIN_DOWNLOAD_MTU" to 900))))
        assertEquals(900,own.privateConfig(1080,0,CarrierBridgeCredentials("u","p")).obj("options").int("MIN_DOWNLOAD_MTU"))
    }

    @Test fun whiteDnsMasterDnsLink() {
        val json = """{"schema":"whitedns.profile","version":1,"profile":{"name":"Iran","server":{"domain":"v.example.com","encryption_key":"k3y","encryption_method":2}}}"""
        val profile = read("masterdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()))!!
        assertEquals("masterdns", profile.type)
        assertEquals("Iran", profile.name)
        assertEquals(2, config(profile).int("encryption"))
    }

    @Test fun exportedLinksReadBack() {
        for (link in listOf(
            "dnst://t.example.com/vaydns/ssh?pubkey=$key&record-type=cname&user=tunnel&password=secret#Мой туннель",
            "dnst://v.example.com/masterdns/socks?mkey=abcdef1234&encryption=3",
            "dnst://a.example.com/slipstream/socks?cert=MIIBkTCB",
        )) {
            val first = read(link)!!
            val again = read(first.exportToLink())!!
            assertEquals(config(first).also { it.remove("system_resolvers") }.toCompact(),
                config(again).also { it.remove("system_resolvers") }.toCompact())
            assertEquals(first.name, again.name)
        }
    }

    @Test fun sharingKeepsResolversDomainsAndClientOptions() {
        val profile=DnsTunnel()
        assertTrue(profile.parseFromJson(app.rawline.mobile.outbound.json.JsonInput.parseObject("""{
            "type":"masterdns","domain":"a.example.com., b.example.com", "mkey":"secret", "encryption":1,
            "resolvers":["192.0.2.1:5353"],"options":{"PACKET_DUPLICATION_COUNT":3,"MTU_TEST_TIMEOUT":2.5}}
        """)))
        val shared=read(profile.exportToLink())!!
        assertEquals("a.example.com,b.example.com",shared.exportToJson().string("domain"))
        assertEquals(listOf("192.0.2.1:5353"),shared.exportToJson().array("resolvers").strings())
        assertEquals(profile.exportToJson().obj("options").toCompact(),shared.exportToJson().obj("options").toCompact())
        assertFalse(DnsTunnel().parseFromJson(app.rawline.mobile.outbound.json.JsonInput.parseObject(
            """{"type":"masterdns","domain":"a.example.com","mkey":"secret","encryption":1,"options":[]} """)))
    }

    @Test fun s3BypassConfigurationOfTheOriginalApp() {
        val config = """{"log":{"loglevel":"info"},"inbounds":[{"tag":"socks-in","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true}}],
            "outbounds":[{"tag":"proxy","protocol":"fedarisha","settings":{"storage":{"type":"s3","bucket":"vlt-alpha","endpoint":"https://storage.example.ru","region":"ru-msk",
            "prefix":"fedarisha/4/","sessionsDir":"sessions","accessKey":"AK","secretKey":"SK"},"tuning":{"idleTimeoutSec":300,"pollIntervalMs":100,"writeIntervalMs":20,"maxFileSizeBytes":2097152}}},
            {"tag":"direct","protocol":"freedom","settings":{}}]}"""
        val profile = ProfileImport.parse(config).outbounds.single() as S3Bypass
        val private = profile.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))
        assertEquals("vlt-alpha", private.obj("storage").string("bucket"))
        assertEquals(100, private.obj("tuning").int("pollIntervalMs"))
        assertEquals("storage.example.ru/vlt-alpha", profile.displayAddress())
        assertFalse(S3Bypass().parseFromJson(JsonObject().apply { this["type"] = "s3bypass" }))
    }
}
