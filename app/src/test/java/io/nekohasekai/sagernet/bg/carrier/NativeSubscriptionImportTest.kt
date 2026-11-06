package app.rawline.mobile.bg.carrier

import app.rawline.mobile.outbound.`import`.ProfileImport
import app.rawline.mobile.outbound.types.Qwdtt
import app.rawline.mobile.outbound.types.Csqtt
import org.junit.Assert.*
import org.junit.Test

class NativeSubscriptionImportTest {
    @Test fun csqttTakesAutomaticHashesFromTheQwdttBindingWithoutAccountLogin() {
        val result=ProfileImport.parse("""{"type":"csqtt","server":"203.0.113.6","server_port":46000,"password":"example","hash_source":"auto","hashes":["exampleHash123456789"]}""")
        val profile=result.outbounds.single() as Csqtt
        assertEquals("auto",profile.hashSource)
        assertEquals(listOf("exampleHash123456789"),profile.hashes)
        // CSQTT joins the binding's calls anonymously: its own account login bans VK accounts.
        val private=profile.privateConfig(1080,1081,CarrierBridgeCredentials("user","pass"))
        assertFalse(private.contains("vk_auth"))
        assertEquals("vkcalls",private.string("vk_anon_path"))
        val manual=ProfileImport.parse("""{"type":"csqtt","server":"203.0.113.6","server_port":46000,"password":"example","hashes":["exampleHash123456789"]}""")
        assertEquals("manual",(manual.outbounds.single() as Csqtt).hashSource)
    }
    @Test fun importsAndroidWrapperWithoutType() {
        val result = ProfileImport.parse("""{"subscriptionName":"Demo","profiles":[{"name":"Demo node","peer":"203.0.113.5:56000","password":"example","hashes":["exampleHash123456789"],"workersPerHash":18,"listenPort":9999}]}""")
        assertEquals(1,result.outbounds.size)
        val node = result.outbounds.single() as Qwdtt
        assertEquals("203.0.113.5",node.server)
        assertEquals(56000,node.serverPort)
        assertEquals(18,node.workers)
        assertEquals("Demo node",node.name)
    }
    @Test fun importsCsqttNativeObjectInsteadOfCustomSingBox() {
        val result = ProfileImport.parse("""{"type":"csqtt","server":"203.0.113.6","server_port":46000,"password":"example","hashes":["exampleHash123456789"]}""")
        assertTrue(result.outbounds.single() is Csqtt)
    }
}
