package app.rawline.mobile.deploy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class WdttPlusDeploySafetyTest {
    private fun linux() = org.junit.Assume.assumeTrue(File("/bin/bash").canExecute() && File("/usr/bin/stat").canExecute())

    private fun bash(script: String): String {
        val process = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true).start()
        assertTrue("shell timed out", process.waitFor(15, TimeUnit.SECONDS))
        return process.inputStream.bufferedReader().readText()
    }

    /** A temporary server root holding what WDTT Plus's Android deploy leaves under /etc, /usr/local/bin and systemd. */
    private class Root : AutoCloseable {
        val root: File = Files.createTempDirectory("wdtt-root-").toFile()
        val config = File(root, "etc/wdtt")
        val unit = File(root, "etc/systemd/system/wdtt.service")
        val binary = File(root, "usr/local/bin/wdtt-server")
        fun installAndroidDeploy(marker: Boolean = true) {
            config.mkdirs(); unit.parentFile.mkdirs(); binary.parentFile.mkdirs()
            File(config, "passwords.json").writeText("""{"main_password":"owner","passwords":{},"devices":{}}""")
            File(config, "wg-keys.dat").writeText("keys")
            File(config, "server.log").writeText("log")
            unit.writeText((if (marker) "# Managed by WDTT Plus Android deploy\n# WDTT deploy compatibility: 1\n" else "") +
                "[Service]\nExecStart=/usr/local/bin/wdtt-server -config-dir /etc/wdtt -iface wdtt0\n")
            binary.writeText("server"); binary.setExecutable(true)
            permissions(config, "rwx------"); permissions(File(config, "passwords.json"), "rw-------"); permissions(File(config, "wg-keys.dat"), "rw-------")
        }
        fun permissions(file: File, value: String) = Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(value))
        /** What WDTT Plus's performUninstall in preserve mode leaves: no unit and binary, the data kept. */
        fun uninstallKeepingData() { unit.delete(); binary.delete() }
        fun onRoot(command: String) = command.replace("/etc/wdtt", config.path)
        override fun close() { root.deleteRecursively() }
    }

    private fun Root.ownership() = deploymentOwnershipFromProbe(bash(standaloneInstallerOwnershipProbeScript(root.path)))

    @Test fun ownershipFollowsTheAndroidDeployLifecycle() = Root().use { host ->
        linux()
        assertEquals(DeploymentOwnership.NoInstall, host.ownership())
        host.installAndroidDeploy()
        assertEquals(DeploymentOwnership.AndroidDeploy, host.ownership())
        host.uninstallKeepingData()
        // server.log is still there, so only the marker set before the uninstall proves the data is ours.
        assertEquals(DeploymentOwnership.UnknownExisting, host.ownership())
        assertTrue(DeployProtocol.wdttPlusMarked(bash(host.onRoot(DeployProtocol.WDTT_PLUS_MARK_PRESERVED))))
        assertEquals(DeploymentOwnership.PreservedAndroidData, host.ownership())
    }

    @Test fun dataLeftByOldQthroneUninstallIsRecognisedOnlyInItsExactShape() = Root().use { host ->
        linux()
        fun unmarked() = markerValue(bash(unmarkedQthroneDataProbe(host.root.path)), "WDTT_UNMARKED_QTHRONE_DATA")
        assertEquals("0", unmarked())
        host.installAndroidDeploy()
        assertEquals("0", unmarked())
        host.uninstallKeepingData()
        // deploy.sh uninstall in qThrone 1.8.1 left the data unmarked and possibly with other permissions.
        host.permissions(host.config, "rwxr-xr-x")
        assertEquals("1", unmarked())
        bash("chmod 700 '${host.config}' && chmod 600 '${host.config}/passwords.json' '${host.config}/wg-keys.dat'")
        assertTrue(DeployProtocol.wdttPlusMarked(bash(host.onRoot(DeployProtocol.WDTT_PLUS_MARK_PRESERVED))))
        assertEquals("0", unmarked())
        assertEquals(DeploymentOwnership.PreservedAndroidData, host.ownership())
        File(host.config, "wg-keys.dat").delete()
        File(host.config, ".android-deploy-preserved").delete()
        assertEquals("0", unmarked())
    }

    @Test fun unmarkedOldInstallIsLegacyAndStandaloneIsNeverTouched() = Root().use { host ->
        linux()
        host.installAndroidDeploy(marker = false)
        assertEquals(DeploymentOwnership.LegacyAndroidDeploy, host.ownership())
        File(host.root, "var/lib/wdtt-server-installer").mkdirs()
        File(host.root, "var/lib/wdtt-server-installer/ownership").writeText("Managed by WDTT Plus standalone server installer\n")
        host.unit.writeText("# Managed by WDTT Plus standalone server installer\n" + host.unit.readText())
        assertEquals(DeploymentOwnership.StandaloneInstaller, host.ownership())
    }

    @Test fun eachInstallStateAllowsOnlyItsSafeModes() {
        fun allowed(ownership: DeploymentOwnership, mode: DeployMode) = runCatching { assertDeploymentMayBeUpdated(ownership, mode) }.isSuccess
        assertTrue(allowed(DeploymentOwnership.NoInstall, DeployMode.FreshInstall))
        assertFalse(allowed(DeploymentOwnership.AndroidDeploy, DeployMode.FreshInstall))
        assertTrue(allowed(DeploymentOwnership.AndroidDeploy, DeployMode.PreserveData))
        assertTrue(allowed(DeploymentOwnership.PreservedAndroidData, DeployMode.PreserveData))
        assertTrue(allowed(DeploymentOwnership.LegacyAndroidDeploy, DeployMode.PreserveData))
        assertFalse(allowed(DeploymentOwnership.LegacyAndroidDeploy, DeployMode.ResetAll))
        assertFalse(allowed(DeploymentOwnership.IncompleteAndroidDeploy, DeployMode.PreserveData))
        assertTrue(allowed(DeploymentOwnership.IncompleteAndroidDeploy, DeployMode.ResetAll))
        for (mode in DeployMode.values()) {
            assertFalse(allowed(DeploymentOwnership.StandaloneInstaller, mode))
            assertFalse(allowed(DeploymentOwnership.UnknownExisting, mode))
        }
        assertThrows(IllegalStateException::class.java) { deploymentOwnershipFromProbe("WDTT_STANDALONE_MANAGED=0") }
    }

    @Test fun rootAccessNeedsItsMarkerAndExplainsSudo() {
        validateRootAccessOutput("WDTT_ROOT_ACCESS=ok\n")
        assertThrows(IllegalStateException::class.java) { validateRootAccessOutput("") }
        val sudo = runCatching { validateRootAccessOutput("sudo: 3 incorrect password attempts") }.exceptionOrNull()
        assertTrue(sudo?.message.orEmpty().contains("sudo"))
    }

    /** WDTT Plus's DeployDataSafetyTest case for its rollback probe. */
    @Test fun rollbackProbeDistinguishesValidCorruptedAndUnknownBackups() {
        linux()
        val root = Files.createTempDirectory("wdtt-update-backup-test").toFile()
        try {
            fun probe(dir: File) = parseServerUpdateRollbackState(bash(serverUpdateRollbackProbeScript(dir.path)))
            assertTrue(probe(File(root, "missing")) is ServerUpdateRollbackState.None)
            val backup = File(root, "backup").apply { mkdirs() }
            Files.setPosixFilePermissions(backup.toPath(), PosixFilePermissions.fromString("rwx------"))
            File(backup, "state").writeText("prepared\n")
            File(backup, "had_config").createNewFile()
            File(backup, "config").mkdir()
            val valid = probe(backup)
            assertTrue(valid is ServerUpdateRollbackState.PreparedValid)
            assertTrue((valid as ServerUpdateRollbackState.PreparedValid).markers.hadConfig)
            File(backup, "config").delete()
            assertTrue(probe(backup) is ServerUpdateRollbackState.PreparedCorrupted)
            File(backup, "had_config").delete()
            File(backup, "state").writeText("committed\n")
            assertTrue(probe(backup) is ServerUpdateRollbackState.UnknownState)
        } finally { root.deleteRecursively() }
    }

    /** WDTT Plus's DeployDataSafetyTest cases for the staged database and DNS. */
    @Test fun preparedDeployDatabasePreservesClientsAndUnknownFields() {
        val current = JSONObject().put("main_password", "old-owner").put("admin_id", "old-admin").put("bot_token", "old-token")
            .put("dns", "1.1.1.1").put("max_passwords", 50).put("default_ports", "56000,56001,9000").put("future_compatible_field", "keep-me")
            .put("passwords", JSONObject().put("ABCDEFGHJKLMNPQR", JSONObject().put("device_id", "").put("expires_at", 0).put("down_bytes", 10).put("up_bytes", 20)))
            .put("devices", JSONObject())
        val prepared = JSONObject(prepareDeployDatabaseJson(current.toString(), "new-owner", "", "", "9.9.9.9", "56100,56101,9100"))
        assertEquals("new-owner", prepared.getString("main_password"))
        assertEquals("old-admin", prepared.getString("admin_id"))
        assertEquals("old-token", prepared.getString("bot_token"))
        assertEquals("9.9.9.9", prepared.getString("dns"))
        assertEquals("56100,56101,9100", prepared.getString("default_ports"))
        assertEquals("keep-me", prepared.getString("future_compatible_field"))
        assertTrue(prepared.getJSONObject("passwords").has("ABCDEFGHJKLMNPQR"))
        assertEquals("1.1.1.1,1.0.0.1", normalizedDeployDns(" 1.1.1.1 ", "1.0.0.1"))
        assertTrue(runCatching { normalizedDeployDns("1.1.1.1; reboot", "") }.isFailure)
        assertThrows(IllegalArgumentException::class.java) { prepareDeployDatabaseJson(null, "", "", "", "1.1.1.1", "56000,56001,9000") }
    }

    @Test fun preservingDatabaseRejectsBrokenStructureAndRecoversOnlyAMissingOwnerPassword() {
        val base = JSONObject().put("main_password", "owner").put("passwords", JSONObject()).put("devices", JSONObject())
        assertFalse(validatePasswordsDbForPreserving(JSONObject(base.toString()), "owner"))
        assertTrue(validatePasswordsDbForPreserving(JSONObject(base.toString()).put("main_password", ""), "owner"))
        assertThrows(IllegalArgumentException::class.java) { validatePasswordsDbForPreserving(JSONObject(base.toString()).put("main_password", ""), "") }
        val orphan = JSONObject(base.toString()).put("passwords", JSONObject().put("client", JSONObject().put("device_id", "missing")))
        assertThrows(IllegalArgumentException::class.java) { validatePasswordsDbStructure(orphan) }
        val badKey = JSONObject(base.toString()).put("devices", JSONObject().put("d", JSONObject().put("pub_key", "short")))
        assertThrows(IllegalArgumentException::class.java) { validatePasswordsDbStructure(badKey) }
        val goodKey = JSONObject(base.toString()).put("devices", JSONObject().put("d", JSONObject().put("pub_key", "A".repeat(43) + "=")))
        validatePasswordsDbStructure(goodKey)
    }

    @Test fun missingMainPasswordIsRepairedWithoutTouchingClientsOrMutatingTheSource() {
        val source = JSONObject().put("passwords",JSONObject().put("client",JSONObject().put("label","Keep").put("up_bytes",42)))
            .put("devices",JSONObject()).put("future_field","keep")
        assertTrue(validatePasswordsDbForPreserving(source,"new-owner"))
        assertFalse(source.has("main_password"))
        val after = prepareDeployDatabaseJson(source.toString(),"new-owner","","","1.1.1.1","56000,56001,9000")
        validatePreservedServerState(source.toString(),after)
        val database = JSONObject(after)
        assertEquals("new-owner",database.getString("main_password"))
        assertEquals(42,database.getJSONObject("passwords").getJSONObject("client").getInt("up_bytes"))
        assertEquals("keep",database.getString("future_field"))
    }

    @Test fun updateMustKeepEveryActiveClientAndItsCounters() {
        fun db(down: Long, withClient: Boolean = true) = JSONObject().put("main_password", "owner").put("devices", JSONObject())
            .put("passwords", if (withClient) JSONObject().put("client", JSONObject().put("label", "a").put("down_bytes", down)
                .put("traffic", JSONArray().put(JSONObject().put("date", "2026-10-18").put("down_bytes", down)))) else JSONObject()).toString()
        validatePreservedServerState(db(10), db(15))
        assertThrows(IllegalStateException::class.java) { validatePreservedServerState(db(10), db(10, withClient = false)) }
        assertThrows(IllegalArgumentException::class.java) { validatePreservedServerState(db(10), db(5)) }
    }

    @Test fun installIsConfirmedOnlyByTheCurrentServerAndRunningService() {
        val sha = "a".repeat(64)
        val good = "BINARY=1\nCONFIG=1\nSERVICE=active\nADMIN_SOCKET=1\nSERVER_SHA256=$sha\nSERVER_VERSION=0.20.3\n"
        assertEquals("", wdttPlusInstallMissing(good, sha))
        assertEquals("актуальная версия бинарника", wdttPlusInstallMissing(good, "b".repeat(64)))
        assertEquals("служба wdtt", wdttPlusInstallMissing(good.replace("SERVICE=active", "SERVICE=failed"), sha))
        assertTrue(wdttPlusInstallMissing("", sha).contains("версия серверной части"))
        assertFalse(unsafeLegacyWdttPlusServer("/etc/wdtt wdtt0 wg0".toByteArray()))
        assertTrue(unsafeLegacyWdttPlusServer("/etc/wireguard wg0".toByteArray()))
    }

    @Test fun uninstallIsConfirmedForTheChosenMode() {
        val clean = "WDTT_UNINSTALL_SERVICE=0\nWDTT_UNINSTALL_BINARY=0\nWDTT_UNINSTALL_INTERFACE=0\nWDTT_UNINSTALL_PROCESS=0\nWDTT_UNINSTALL_SYSCTL=0\nWDTT_UNINSTALL_FIREWALL=0\n"
        assertTrue(wdttPlusUninstallVerified(clean + "WDTT_UNINSTALL_DATA=preserved\n", ServerUninstallMode.PreserveData))
        assertFalse(wdttPlusUninstallVerified(clean + "WDTT_UNINSTALL_DATA=preserved\n", ServerUninstallMode.RemoveAll))
        assertTrue(wdttPlusUninstallVerified(clean + "WDTT_UNINSTALL_DATA=removed\n", ServerUninstallMode.RemoveAll))
        assertFalse(wdttPlusUninstallVerified(clean.replace("FIREWALL=0", "FIREWALL=1") + "WDTT_UNINSTALL_DATA=removed\n", ServerUninstallMode.RemoveAll))
        // The full removal refuses a symlinked /etc/wdtt instead of deleting where it points.
        assertTrue(wdttPlusUninstallSteps(ServerUninstallMode.RemoveAll, 22, 56000, 56001).any { "if [ -L /etc/wdtt ]" in it.second })
    }

    @Test fun recoveryTouchesOnlyItsOwnAttempt() = Root().use { host ->
        linux()
        val paths = ServerUpdatePaths(backup = File(host.root, "backup").path)
        File(paths.backup).mkdirs()
        File(paths.backup, "attempt").writeText("mine\n")
        assertEquals("1", markerValue(bash(attemptOwnedScript("mine", paths)), "WDTT_UPDATE_ATTEMPT_OWNED"))
        assertNull(markerValue(bash(attemptOwnedScript("other'; touch hacked; '", paths)), "WDTT_UPDATE_ATTEMPT_OWNED"))
        assertFalse(File("hacked").exists())
    }
}
