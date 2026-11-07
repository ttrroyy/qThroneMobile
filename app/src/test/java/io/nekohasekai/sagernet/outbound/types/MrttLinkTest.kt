package app.rawline.mobile.outbound.types

import app.rawline.mobile.deploy.DeployProtocol
import app.rawline.mobile.deploy.MrttBundle
import app.rawline.mobile.outbound.import.ProfileImport
import app.rawline.mobile.outbound.json.jsonObjectOf
import org.junit.Assert.*
import org.junit.Test

/** The vectors come from the original's scripts/make_mrtt_link.py algorithm. */
class MrttLinkTest {
    private val jitsiLink = "mrtt://nTNteRMGk2tTnQmI_H6q5mNe3lJzMk7-wseaQsVINHEs9GKcR2tnJvS8M9Pzd1WelBdgOiCID1JgCRTQr04fW4NAM4W60YCgCN9XmxfyvkAukfpDTETKanrWFsEFpzFjOHuwAZz0q8XpHK0kZRs1Y_ankEkJ4Ji6rXIdD6hfMCIpg00jLU_OG7WxrAeh"

    @Test fun readsTheOriginalLink() {
        val link = Mrtt.parseLink(jitsiLink)!!
        assertEquals("vpn.example.com",link.host)
        assertEquals(46000,link.port)
        assertEquals("abc123",link.password)
        assertEquals("meet.example.org/room",link.jitsiRoom)
        assertEquals(6,link.jitsiSeats)
        assertEquals("jitsi",link.transport)
    }
    @Test fun writesTheOriginalLinkByteForByte() {
        assertEquals(jitsiLink,Mrtt.formatLink("vpn.example.com",46000,"abc123",emptyList(),"","","meet.example.org/room",6,"jitsi"))
    }
    @Test fun aLinkChoosesVkOrJitsiAndMaxStaysLocal() {
        val hash = "a".repeat(16)
        val vk = Mrtt().apply { assertTrue(parseFromLink(Mrtt.formatLink("h.example",46000,"p",listOf(hash),"","","",0,""))) }
        assertEquals("vk",vk.transport); assertEquals(listOf(hash),vk.hashes)
        val jitsi = Mrtt().apply { assertTrue(parseFromLink(jitsiLink)) }
        assertEquals("jitsi",jitsi.transport)
        // A hand-chosen MAX never enters the link's transport field.
        jitsi.transport = "max"
        assertEquals("",Mrtt.parseLink(jitsi.exportToLink())!!.transport)
    }
    @Test fun refusesWhatTheOriginalRefuses() {
        for(plain in listOf("connect?v=2&host=127.0.0.1&peer=1&password=p","connect?v=2&host=h&peer=70000&password=p","connect?v=1&host=h&peer=1&password=p",
            "connect?v=2&host=h&peer=1&password=p&max=abc","connect?v=2&host=h&peer=1&password=p&jitsi=nothing","connect?v=2&host=h&peer=1&password=p&hashes=short",
            "connect?v=2&host=h&peer=1&password=p&peer=2","mrtt://connect?v=2&host=h&peer=1"))
            assertNull(plain,Mrtt.parseLink(Mrtt.SCHEME+Mrtt.encrypt(plain)))
        assertNull(Mrtt.parseLink("csqtt://x"))
    }
    @Test fun workersFollowItsPolicy() {
        assertEquals(27,Mrtt.normalizeWorkers(100,1,false))
        assertEquals(162.coerceAtMost(126)/9*9,Mrtt.normalizeWorkers(200,6,true))
        assertEquals(9,Mrtt.normalizeWorkers(1,1,false))
        assertEquals(18,Mrtt.normalizeWorkers(20,6,false))
    }
    @Test fun subscriptionTakesOnlyValidLinks() {
        val sub = Mrtt.SUBSCRIPTION_SCHEME+Mrtt.encrypt(jsonObjectOf("v" to 1,"name" to "S","links" to app.rawline.mobile.outbound.json.JsonArray.of(
            jsonObjectOf("name" to "ok","link" to jitsiLink),jsonObjectOf("name" to "bad","link" to "vless://x@y:1"))).toCompact())
        val parsed = ProfileImport.parse(sub).outbounds
        assertEquals(listOf("ok"),parsed.map { it.name })
        assertEquals("mrtt",parsed.single().type)
    }
    @Test fun hashesCanBeAppliedLikeOtherVkProfiles() {
        val profile = Mrtt()
        assertTrue(app.rawline.mobile.bg.carrier.NativeHashPolicy.supportsAutomatic(profile))
        assertTrue(profile is Qwdtt)
    }
    @Test fun serverDeployMarkers() {
        assertTrue(DeployProtocol.successful("mrtt",0,"x\nMRTT_DEPLOY_OK\n"))
        assertFalse(DeployProtocol.successful("mrtt",0,"not MRTT_DEPLOY_OK"))
        assertEquals(50 to "Шаг",DeployProtocol.progress("mrtt","MRTT_PROGRESS|0.5|Шаг"))
        assertTrue(MrttBundle.failure(30,"").contains("восстановлена"))
        assertTrue(MrttBundle.failure(1,"MRTT_DEPLOY_ERROR|x|порт занят").contains("порт занят"))
        assertEquals("mrtt-linux-arm64",MrttBundle.serverAsset("aarch64\n"))
    }
    @Test fun readsPlainUstarEntries() {
        val header = ByteArray(512)
        "deploy.sh".toByteArray().copyInto(header)
        "00000000003 ".toByteArray().copyInto(header,124)
        header[156] = '0'.code.toByte()
        val body = "abc".toByteArray().copyOf(512)
        val files = MrttBundle.untar(header+body+ByteArray(1024))
        assertEquals("abc",files.getValue("deploy.sh").toString(Charsets.UTF_8))
    }
}
