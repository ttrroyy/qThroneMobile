package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.`import`.ProfileImport
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.jsonObjectOf
import app.qthrone.mobile.outbound.types.OlcRtc
import org.junit.Assert.*
import org.junit.Test

class NativeWarpChainTest {
    private val settings=GeneratorSettings(vpnMode=true,enableWarp=true,
        warpPrivateKey="private",warpPublicKey="public",warpEp="192.0.2.200:2408",warpIfcAddrs=listOf("172.16.0.2/32"),
        warpMasquePrivateKey="private",warpMasquePeerPublicKey="public",warpMasqueEp="192.0.2.200:443",warpMasqueIfcAddrs=listOf("172.16.0.2/32"))

    private fun checkBridge(profile:Outbound,settings:GeneratorSettings) {
        val before=profile.exportToJson().toCompact()
        val result=ConfigGenerator(ProfileProvider { if(it==1L) profile else null },settings).build(1)
        assertNull(profile.type+": "+result.error,result.error)
        assertEquals(1,result.carrierPlans.size)
        val plan=result.carrierPlans.single()
        val core=JsonInput.parseObject(result.coreConfig)
        val warp=core.array("endpoints").filterIsInstance<JsonObject>().single { it.string("tag")=="proxy" }
        assertEquals("warp-bypass",warp.string("detour"))
        val bridge=core.array("outbounds").filterIsInstance<JsonObject>().single { it.string("tag")=="warp-bypass" }
        assertEquals("socks",bridge.string("type"))
        assertEquals("127.0.0.1",bridge.string("server"));assertEquals(plan.port,bridge.int("server_port"))
        assertEquals(plan.credentials.username,bridge.string("username"));assertEquals(plan.credentials.password,bridge.string("password"))
        assertFalse("The native loopback listener must be reached directly",bridge.contains("detour"))
        assertEquals(before,profile.exportToJson().toCompact())
    }

    @Test fun wireGuardAndMasqueKeepTheAuthenticatedNativeEntry() {
        val links=listOf(
            "qwdtt://config?peer=192.0.2.1%3A56002&pass=fixture&hashes=demo_hash_123456789",
            "qwdtt://config?peer=192.0.2.1%3A56000&pass=fixture&hashes=demo_hash_123456789&mode=raw",
            "csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=fixture&hashes=demo_hash_123456789",
            "wdtt://connect?v=1&host=192.0.2.1&dtls=56000&wg=56001&local=9000&password=fixture&hashes=demo_hash_123456789",
            "openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__",
        )
        for(link in links) for(mode in listOf("wireguard","masque")) {
            val profile=ProfileImport.parse(link).outbounds.single()
            checkBridge(profile,settings.copy(warpMode=mode,warpMasqueHttpMode=2))
        }
    }

    @Test fun tcpOnlyOlcRtcCarriesMasqueHttp2() {
        val profile=OlcRtc().apply { assertTrue(parseFromJson(jsonObjectOf("type" to "olcrtc","room" to "https://meet.example.org/fixture","key" to "a".repeat(64)))) }
        checkBridge(profile,settings.copy(warpMode="masque",warpMasqueHttpMode=2))
    }
}
