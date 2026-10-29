package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.json.JsonInput
import org.junit.Assert.*
import org.junit.Test

class QwdttIntegrationTest {
    @Test fun wgDoesNotInferDirectTransportFromPortOrLegacyFlag() {
        for (port in listOf(56000,56002,46000)) for (legacy in listOf("","&no_dtls=1")) {
            val link="qwdtt://config?peer=192.0.2.1%3A$port&pass=fixture&hashes=demo_hash_123456789&workers=18&port=9000$legacy"
            val profile=Qwdtt().apply { assertTrue(parseFromLink(link)) }
            val saved=profile.exportToJson().also { it["no_dtls"]=true }
            assertTrue(profile.parseFromJson(saved))
            assertEquals("wg",profile.mode)
            val config=profile.privateConfig(19001,19002,CarrierBridgeCredentials("user","pass"))
            assertEquals("192.0.2.1:$port",config.string("peer"))
            assertEquals("127.0.0.1:19002",config.string("listen"))
            assertFalse(config.contains("no_dtls"))
            assertFalse(profile.exportToLink().contains("no_dtls"))
            val restored=Qwdtt().apply { assertTrue(parseFromLink(profile.exportToLink())) }
            assertEquals("wg",restored.mode)
            assertEquals(port,restored.serverPort)
            profile.mode="raw"
            assertEquals("192.0.2.1:56003",profile.privateConfig(19001,19002,CarrierBridgeCredentials("user","pass")).string("peer"))
        }
        val csqtt=app.qthrone.mobile.outbound.types.Csqtt().apply {
            assertTrue(parseFromJson(app.qthrone.mobile.outbound.json.jsonObjectOf("type" to "csqtt","server" to "192.0.2.1","server_port" to 56002,"password" to "fixture")))
        }
        assertFalse(csqtt.exportToJson().contains("no_dtls"))
    }
    private fun profile()=Qwdtt().apply {
        assertTrue(parseFromLink("qwdtt://config?peer=192.0.2.1%3A56000&pass=a%2Bb&hashes=demo_hash_123456789&device_id=stable-test&mode=raw"))
    }
    @Test fun importPreservesLiteralPlusAndDeviceIdentity() {
        val original=profile()
        assertEquals("a+b",original.password)
        val restored=Qwdtt()
        assertTrue(restored.parseFromLink(original.exportToLink()))
        assertEquals(original.password,restored.password)
        assertEquals("stable-test",restored.deviceId)
        assertEquals("qwdtt",OutboundFactory.typeForScheme(original.exportToLink()))
        assertTrue(OutboundFactory.newByType("qwdtt") is Qwdtt)
    }
    @Test fun runtimePlanDoesNotPersistPortsOrBridgeSecrets() {
        val profile=profile()
        val before=profile.exportToJson().toCompact()
        val plan=CarrierPlan.qwdtt(7,profile)
        val config=JsonInput.parseObject(plan.privateJson)
        assertEquals("127.0.0.1:"+plan.port,config.string("socks"))
        assertEquals(plan.credentials.username,config.string("socks_user"))
        assertEquals(plan.credentials.password,plan.outbound().password)
        assertEquals(before,profile.exportToJson().toCompact())
        assertFalse(before.contains(plan.credentials.password))
        assertEquals("stable-test",config.string("device_id"))
    }
    @Test fun nameAndDeviceIdDoNotChangeSubscriptionIdentity() {
        val profile=profile()
        val identity=profile.exportIdentity().toCompact()
        profile.name="Renamed";profile.deviceId="another-device"
        assertEquals(identity,profile.exportIdentity().toCompact())
    }
    @Test fun invalidPrivateConfigIsRejectedBeforeNativeLaunch() {
        val profile=profile();profile.password="bad|secret"
        try { CarrierPlan.qwdtt(7,profile);fail("invalid password accepted") } catch(_:IllegalArgumentException) {}
    }
    @Test fun excessQwdttHashesKeepFirstFourSlots() {
        val input=profile().exportToJson()
        val hashes=(1..7).map { "test_hash_123456789_$it" }
        input["hashes"]=app.qthrone.mobile.outbound.json.JsonArray.of(*hashes.toTypedArray())
        val restored=Qwdtt()
        assertTrue(restored.parseFromJson(input))
        assertEquals(hashes.take(4),restored.hashes)
        assertEquals(hashes.take(4),Qwdtt().apply { assertTrue(parseFromLink(restored.exportToLink())) }.hashes)
    }
    @Test fun excessCsqttLinkHashesKeepFirstSixSlots() {
        val hashes=(1..8).map { "test_hash_123456789_$it" }
        val link="csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=test-password&hashes="+hashes.joinToString("+")
        val restored=app.qthrone.mobile.outbound.types.Csqtt()
        assertTrue(restored.parseFromLink(link))
        assertEquals(hashes.take(6),restored.hashes)
        val again=app.qthrone.mobile.outbound.types.Csqtt()
        assertTrue(again.parseFromLink(restored.exportToLink()))
        assertEquals(restored.hashes,again.hashes)
    }

}
