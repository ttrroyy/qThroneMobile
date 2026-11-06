package app.rawline.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

class CsqttDeployPolicyTest {
    @Test fun generatedPasswordMatchesOriginalDeployValidation() {
        repeat(100) { assertTrue(CsqttDeployPolicy.password().matches(Regex("[A-Za-z0-9]{16}"))) }
    }
    @Test fun environmentEscapingMatchesTheOriginalInstaller() {
        assertEquals("\"a\\\"b\\\\c d\"",CsqttDeployPolicy.environmentValue("a\"b\\c\nd",false))
        assertEquals("a\"b\\c d",CsqttDeployPolicy.environmentValue("a\"b\\c\nd",true))
    }
    @Test fun installerMustConfirmSuccessOnItsOwnLine() {
        assertTrue(DeployProtocol.successful("csqtt",0,"step\nCSQTT_DEPLOY_OK\n"))
        assertFalse(DeployProtocol.successful("csqtt",0,"missing CSQTT_DEPLOY_OK marker"))
        assertFalse(DeployProtocol.successful("csqtt",30,"CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_ROLLED_BACK"))
        assertTrue(CsqttDeployPolicy.failure(31,"CSQTT_DEPLOY_ERROR|verify|broken service").contains("broken service"))
        assertTrue(CsqttDeployPolicy.failure(30,"").contains("восстановлена"))
        assertTrue(CsqttDeployPolicy.failure(20,"").contains("не останавливалась"))
    }
    @Test fun secretPanelPathIsUniqueAndIpv6UrlIsValid() {
        val first=DeployServer.create("csqtt")
        val second=DeployServer.create("csqtt")
        assertNotEquals(first["secretPath"],second["secretPath"])
        first["host"]="2001:db8::1"
        assertEquals("https://[2001:db8::1]:46002/${first["secretPath"]}",first.panelUrl())
        first["manualPorts"]="true";first["webPort"]="8443"
        assertEquals("https://[2001:db8::1]:8443/${first["secretPath"]}",first.panelUrl())
    }
    @Test fun manualPortToggleUsesTheOriginalDefaultsWithoutDeletingCustomValues() {
        val server=DeployServer.create("csqtt")
        server["sshPort"]="2222";server["peerPort"]="47000";server["webPort"]="8443"
        assertEquals(22,server.port("sshPort"))
        assertEquals(46000,server.port("peerPort"))
        assertEquals(46002,server.port("webPort"))
        server["manualPorts"]="true"
        assertEquals(2222,server.port("sshPort"))
        assertEquals(47000,server.port("peerPort"))
        assertEquals(8443,server.port("webPort"))
    }
}
