package app.rawline.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

class DeployProtocolTest {
    @Test fun rejectsLegacyWireguardAssetsLikeQwdtt() {
        assertFalse(DeployProtocol.safeQwdttServer("/etc/wireguard wdtt0".toByteArray()))
        assertFalse(DeployProtocol.safeQwdttServer("wg0".toByteArray()))
        assertTrue(DeployProtocol.safeQwdttServer("wg0 wdtt0 /etc/wdtt".toByteArray()))
    }
    @Test fun progressComesFromTheMatchingAuthorInstaller() {
        assertEquals(60 to "Бинарник",DeployProtocol.progress("qwdtt","WDTT_PROGRESS|0.60|Бинарник"))
        assertEquals(75 to "Сервис",DeployProtocol.progress("csqtt","CSQTT_PROGRESS|0.75|Сервис"))
        assertNull(DeployProtocol.progress("qwdtt","CSQTT_PROGRESS|0.75|Сервис"))
        assertNull(DeployProtocol.progress("csqtt","CSQTT_PROGRESS|NaN|Сервис"))
    }
    @Test fun nonzeroExitNeverMeansSuccess() {
        assertFalse(DeployProtocol.successful("csqtt",1,"CSQTT_DEPLOY_OK"))
        assertFalse(DeployProtocol.successful("qwdtt",1,"WDTT_DEPLOY_OK\nWDTT_ADMIN_PIN|sha256/abc="))
    }
    @Test fun qwdttRequiresProtectedPanelPin() {
        assertFalse(DeployProtocol.successful("qwdtt",0,"WDTT_DEPLOY_OK"))
        assertTrue(DeployProtocol.successful("qwdtt",0,"WDTT_DEPLOY_OK\nWDTT_ADMIN_PIN|sha256/abc="))
        assertTrue(DeployProtocol.successful("csqtt",0,"CSQTT_DEPLOY_OK"))
    }
    @Test fun shellArgumentsKeepQuotesAndSubstitutionLiteral() {
        assertEquals("'a'\"'\"'b'",DeployProtocol.quote("a'b"))
        assertEquals("'\$(id); rm -rf /'",DeployProtocol.quote("\$(id); rm -rf /"))
    }

    @Test fun wdttPlusMarkerOutputIsExact() {
        assertFalse(DeployProtocol.wdttPlusMarked("WDTT_PRESERVED_MARKER=readyx"))
        assertTrue(DeployProtocol.wdttPlusMarked("log\nWDTT_PRESERVED_MARKER=ready\n"))
    }

    /** Runs the marker command on a temporary /etc/wdtt; bash is on CI, the test is skipped where it is absent. */
    @Test fun wdttPlusPreservedMarkerIsWrittenOnlyForRealDataFiles() {
        val bash=listOf("/bin/bash","/usr/bin/bash").firstOrNull { java.io.File(it).canExecute() }
        org.junit.Assume.assumeNotNull(bash)
        fun run(prepare: (java.io.File)->Unit): Pair<String,java.io.File> {
            val dir=java.io.File(kotlin.io.path.createTempDirectory("wdtt").toFile(),"wdtt").apply { mkdirs() }
            prepare(dir)
            val process=ProcessBuilder(bash!!,"-c",DeployProtocol.WDTT_PLUS_MARK_PRESERVED.replace("/etc/wdtt",dir.path)).redirectErrorStream(true).start()
            val output=process.inputStream.bufferedReader().readText();process.waitFor()
            return output to java.io.File(dir,".android-deploy-preserved")
        }
        val (ok,marker)=run { java.io.File(it,"passwords.json").writeText("{}");java.io.File(it,"wg-keys.dat").writeText("k") }
        assertTrue(ok,DeployProtocol.wdttPlusMarked(ok))
        // deploy.sh accepts the marker only as exactly this line (grep -Fqx).
        assertEquals("Preserved by WDTT Plus Android deploy\n",marker.readText())
        assertFalse(java.io.File(marker.parentFile,".android-deploy-preserved.new").exists())
        val (noKeys,noKeysMarker)=run { java.io.File(it,"passwords.json").writeText("{}") }
        assertFalse(DeployProtocol.wdttPlusMarked(noKeys));assertFalse(noKeysMarker.exists())
        val (link,linkMarker)=run {
            java.io.File(it,"wg-keys.dat").writeText("k")
            java.nio.file.Files.createSymbolicLink(java.io.File(it,"passwords.json").toPath(),java.io.File(it,"wg-keys.dat").toPath())
        }
        assertFalse(DeployProtocol.wdttPlusMarked(link));assertFalse(linkMarker.exists())
    }
}
