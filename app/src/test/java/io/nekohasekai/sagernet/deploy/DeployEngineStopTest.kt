package app.rawline.mobile.deploy

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class DeployEngineStopTest {
    @Test fun stopDuringSshConnectReportsAStopNotAnError() {
        // The server accepts TCP but never sends the SSH banner, so connect() blocks like a slow VPS.
        ServerSocket(0).use { listener ->
            listener.soTimeout = 10_000
            val server = DeployServer.create("wdttplus").apply {
                set("host", "127.0.0.1"); set("sshPort", listener.localPort.toString()); set("user", "root")
                set("auth", "password"); set("sshPassword", "x"); set("hostKey", "SHA256:pinned")
                set("peerPort", "56000"); set("wgPort", "56001"); set("localPort", "9000")
            }
            DeployEngine.start(server, uninstall = true)
            listener.accept().use {
                DeployEngine.cancel()
                // SSH can finish stopping before this thread observes the intermediate state.
                val afterCancel = DeployEngine.state.value
                assertTrue(afterCancel.stopping || !afterCancel.running)
                DeployEngine.cancel()
                val deadline = System.currentTimeMillis() + 10_000
                while (DeployEngine.state.value.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
            }
            val state = DeployEngine.state.value
            assertFalse(state.running)
            assertFalse(state.stopping)
            assertEquals("Операция остановлена", state.step)
            assertFalse(state.logs, state.logs.contains("Ошибка"))
            assertEquals(1, Regex("Остановка по запросу").findAll(state.logs).count())
        }
    }
}
