package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.types.Qwdtt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * qWDTT's own latency probe, as the original app runs it: `-ping-only` takes one set of VK credentials and makes
 * one TURN allocation and DTLS handshake with the server. No tunnel, worker group or device session is created,
 * so a sweep over a subscription cannot run into VK's credential limits or displace the connected VPN.
 */
object QwdttPing {
    class Outcome(val latency: Int, val error: String = "")

    private val result = Regex("^PING_RESULT\\|([0-9]{1,9})$")

    /** The client's log line once VK gave credentials: the handshake budget starts there. */
    private fun authorized(line: String) = line.contains("[VK Auth] Success") || line.contains("[VK Auth] Using cached credentials")

    /** Where the app's carrier and test code is blocked right now, for a ping that overran its deadline. */
    fun stuckThreads(): String = Thread.getAllStackTraces().entries
        .filter { (_, stack) -> stack.any { it.className.startsWith("app.qthrone.mobile.bg.") } }
        .joinToString(" | ") { (thread, stack) ->
            thread.name + " " + thread.state + ": " + stack.take(14).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
        }

    suspend fun measure(profileId: Long, profile: NativeCarrier, authBudgetMs: Long, handshakeMs: Long): Outcome = withContext(Dispatchers.IO) {
        val authDeadline = System.nanoTime() + authBudgetMs * 1_000_000
        // Automatic hashes come from VK over blocking calls: a stop interrupts them, the VK budget bounds them,
        // and a ping never waits for a sign-in.
        val plan = try {
            withTimeout(authBudgetMs) { runInterruptible { CarrierPlan.qwdtt(profileId, profile, interactive = false) } }
        } catch (_: TimeoutCancellationException) {
            return@withContext Outcome(0, "VK не выдал хеши звонков вовремя")
        }
        val json = JsonInput.parseObject(
            NativeSessionPolicy.probeConfig(CarrierKind.QWDTT, plan.privateJson, interactive = false, latencyProbe = true)).also { config ->
            // The handshake is DTLS; the RAW port speaks without it. The original app pings the DTLS port in every mode.
            (profile as? Qwdtt)?.let { config["peer"] = Hosts.displayAddress(it.server, it.serverPort) }
        }.toCompact()
        val binary = File(app.applicationInfo.nativeLibraryDir, CarrierKind.QWDTT.executable)
        check(binary.canExecute()) { "Transport client is not installed" }
        val root = File(app.cacheDir, "carriers")
        check(root.isDirectory || root.mkdirs()) { "Private transport directory unavailable" }
        val folder = File(root, "ping-" + UUID.randomUUID())
        check(folder.mkdir()) { "Cannot create private transport session" }
        try {
            val config = File(folder, "config.json")
            check(config.createNewFile() && config.setReadable(false, false) && config.setWritable(false, false) &&
                config.setReadable(true, true) && config.setWritable(true, true)) { "Cannot protect transport configuration" }
            config.writeText(json, Charsets.UTF_8)
            val process = ProcessBuilder(binary.absolutePath, "-config", config.absolutePath, "-ping-only")
                .directory(binary.parentFile).redirectErrorStream(true)
                .apply { environment()["XDG_CACHE_HOME"] = root.absolutePath }
                .start()
            // Automatic VK checks are solved as for a tunnel; one that needs the user fails the probe instead.
            val captcha = CarrierCaptchaBridge(process, allowUi = false)
            val latency = java.util.concurrent.atomic.AtomicInteger(-1)
            // Set once, by the first credentials: later hashes share the same handshake budget.
            val handshakeDeadline = java.util.concurrent.atomic.AtomicLong(0)
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        captcha.accept(line)
                        if (authorized(line)) handshakeDeadline.compareAndSet(0, System.nanoTime() + handshakeMs * 1_000_000)
                        result.find(line.trim())?.let { latency.set(it.groupValues[1].toInt()) }
                    }
                }
            }, "qThrone-qwdtt-ping").apply { isDaemon = true; start() }
            try {
                while (process.isAlive) {
                    coroutineContext.ensureActive()
                    val now = System.nanoTime()
                    val handshake = handshakeDeadline.get()
                    if (handshake == 0L && now > authDeadline) return@withContext Outcome(0, "VK не выдал доступ к звонку вовремя")
                    if (handshake != 0L && now > handshake) return@withContext Outcome(0, "TURN/DTLS handshake timed out")
                    delay(100)
                }
                reader.join(500)
                when {
                    latency.get() >= 0 -> Outcome(latency.get())
                    captcha.interactionRequired -> Outcome(0, "Требуется подтверждение VK: подключите профиль")
                    else -> Outcome(0, "TURN/DTLS handshake failed")
                }
            } finally {
                captcha.close()
                if (process.isAlive) {
                    runCatching { process.outputStream.write("STOP\n".toByteArray()); process.outputStream.flush() }
                    runCatching { process.destroy() }
                    if (!runCatching { process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false))
                        runCatching { process.destroyForcibly() }
                }
                runCatching { process.inputStream.close() }
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
