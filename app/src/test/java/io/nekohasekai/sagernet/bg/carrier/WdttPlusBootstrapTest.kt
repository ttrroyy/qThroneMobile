package app.rawline.mobile.bg.carrier

import app.rawline.mobile.deploy.DeployServer
import org.junit.Assert.*
import org.junit.Test

class WdttPlusBootstrapTest {
    private fun saved() = DeployServer.create("wdttplus").apply {
        set("host","server.example.org"); set("hostKey","SHA256:fixture"); set("sshPassword","ssh-secret")
    }

    @Test fun bootstrapUsesOnlyTheUnambiguousMatchingServerWithApprovedSshAccess() {
        val server=saved()
        assertSame(server,WdttPlusBootstrapPolicy.server("SERVER.EXAMPLE.ORG",56000,listOf(server)))
        assertNull(WdttPlusBootstrapPolicy.server("other.example.org",56000,listOf(server)))
        assertNull(WdttPlusBootstrapPolicy.server("server.example.org",56100,listOf(server)))
        assertNull(WdttPlusBootstrapPolicy.server("server.example.org",56000,listOf(server,saved())))
        server["hostKey"]=""
        assertNull(WdttPlusBootstrapPolicy.server("server.example.org",56000,listOf(server)))
        server["hostKey"]="SHA256:fixture"; server["sshPassword"]=""
        assertNull(WdttPlusBootstrapPolicy.server("server.example.org",56000,listOf(server)))
        server["auth"]="key"; server["key"]="private-key-fixture"
        assertSame(server,WdttPlusBootstrapPolicy.server("server.example.org",56000,listOf(server)))
    }

    @Test fun enrollmentRelayFlagIsAddedOnlyForEnabledFirstRegistration() {
        val address="127.0.0.1:34001"
        assertEquals(listOf("-warp-api-relay",address),WdttPlusBootstrapPolicy.arguments(true,true,false,address))
        for((masque,bootstrap,exists) in listOf(Triple(false,true,false),Triple(true,false,false),Triple(true,true,true))) {
            assertFalse(WdttPlusBootstrapPolicy.needed(masque,bootstrap,exists))
            assertTrue(WdttPlusBootstrapPolicy.arguments(masque,bootstrap,exists,address).isEmpty())
        }
        assertTrue(WdttPlusBootstrapPolicy.arguments(true,true,false,null).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { WdttPlusBootstrapPolicy.arguments(true,true,false,"0.0.0.0:443") }
        assertThrows(IllegalArgumentException::class.java) { WdttPlusBootstrapPolicy.arguments(true,true,false,"127.0.0.1:0") }
    }
}
