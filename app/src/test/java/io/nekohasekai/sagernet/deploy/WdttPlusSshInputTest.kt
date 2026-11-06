package app.rawline.mobile.deploy

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WdttPlusSshInputTest {
    private val request = "{\"main_password\":\"owner-secret\",\"args\":[\"list\"]}\n"

    private fun shell(command: String, input: String, directory: File? = null): String {
        org.junit.Assume.assumeTrue(File("/bin/bash").canExecute())
        val builder=ProcessBuilder("bash","-c",command).redirectErrorStream(true)
        if(directory != null) builder.environment()["PATH"] = directory.path+":"+System.getenv("PATH")
        val process=builder.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        assertTrue("shell timed out",process.waitFor(5,TimeUnit.SECONDS))
        val output=process.inputStream.bufferedReader().readText()
        assertEquals(output,0,process.exitValue())
        return output
    }

    @Test fun adminJsonIsIntactWithPromptCachedSudoAndRoot() {
        val plan=rootStdinPlan("cat","sudo-secret",request)
        assertEquals(request,shell(framedStdinCommand("cat"),plan.input))
        assertEquals(request,shell(framedStdinCommand("cat"),plan.input.substringAfter('\n')))
        assertFalse(plan.command.contains("sudo-secret"))
        assertFalse(plan.command.contains("owner-secret"))
        val directory=Files.createTempDirectory("wdtt-sudo-").toFile()
        try {
            for(prompt in listOf(true,false)) {
                File(directory,"id").apply { writeText("#!/bin/sh\nprintf '1000\\n'\n");setExecutable(true) }
                File(directory,"sudo").apply {
                    writeText("#!/bin/sh\n[ \"\$1\" = '-S' ] && shift\n[ \"\$1\" = '-p' ] && shift 2\n"+
                        (if(prompt) "IFS= read -r supplied; [ \"\$supplied\" = 'sudo-secret' ] || exit 41\n" else "")+"exec \"\$@\"\n")
                    setExecutable(true)
                }
                assertEquals(request,shell(plan.command,plan.input,directory))
            }
            File(directory,"id").writeText("#!/bin/sh\nprintf '0\\n'\n")
            assertEquals(request,shell(plan.command,plan.input,directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun passwordsCannotInjectFrameLinesAndUnusedSecretsDoNotEnterOrdinaryCommands() {
        assertThrows(IllegalArgumentException::class.java) { rootStdinPlan("cat","a\nb",request) }
        assertNull(deployStdinPlan("uname -m","secret"))
        val plan=rootStdinPlan("cat","__WDTT_ADMIN_STDIN_V1__",request)
        assertEquals(request,shell(framedStdinCommand("cat"),plan.input))
    }

    @Test fun longRootScriptsTravelInStdinAndRemainExecutable() {
        val command="if [ \"\$(id -u)\" = 0 ]; then printf ready; else sudo -S -p '' printf ready; fi\n"+"v=x\n".repeat(40_000)
        val plan=deployStdinPlan(command,"secret")!!
        assertTrue(plan.command.length < 2000)
        assertFalse(plan.command.contains("secret"))
        assertTrue(plan.input.contains(command))
        assertEquals("ready",shell(framedStdinCommand("exec sh -s",shell="sh"),plan.input))
    }
}
