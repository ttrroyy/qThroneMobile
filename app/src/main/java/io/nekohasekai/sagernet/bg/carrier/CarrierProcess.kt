package app.qthrone.mobile.bg.carrier

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class CarrierKind(val executable: String, val configFlag: String, val minimumApi: Int) {
    QWDTT("libqwdtt.so", "-config", 24),
    CSQTT("libqwdtt.so", "-csqtt-config", 26),
    OPENFLUX("libopenflux.so", "--qthrone-config", 24),
    OPENFLUXKEY("libopenfluxkey.so", "--qthrone-config", 24),
    OLCRTC("libolcrtc.so", "--qthrone-config", 24),
    DNSTT("libdnstun.so", "--qthrone-config", 24),
    VAYDNS("libdnstun.so", "--qthrone-config", 24),
    SLIPSTREAM("libdnstun.so", "--qthrone-config", 24),
    MASTERDNS("libmasterdns.so", "--qthrone-config", 24),
    COTTENDNS("libcottendns.so", "--qthrone-config", 24),
    SOAR("libsoar.so", "--qthrone-config", 24),
    S3BYPASS("libs3bypass.so", "--qthrone-config", 24),
    SSTP("libsstp.so", "--qthrone-config", 24),
    // WDTT Plus takes flags and its secrets as START_CONFIG on stdin, as its own app starts it.
    WDTTPLUS("libwdttplus.so", "", 24),
}

/** Per-launch credentials, deliberately without a generated toString() containing secrets. */
class CarrierBridgeCredentials(val username: String, val password: String) {
    init {
        require(username.toByteArray(Charsets.UTF_8).size in 1..255)
        require(password.toByteArray(Charsets.UTF_8).size in 1..255)
    }
    companion object {
        fun fresh() = CarrierBridgeCredentials(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    }
}

/** Native client owner. Config secrets are removed before provider output enters application logs. */
class CarrierProcess private constructor(private val process: Process, private val config: File, allowInteractions: Boolean, private val kind: CarrierKind,
    private val onStatus: (NativeConnectionStage) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val startupFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val unavailableCall = AtomicBoolean(false)
    private class UnavailableCall:IllegalStateException("VK: выбранный хеш недоступен; звонок не найден")
    private class TransientStartup:IllegalStateException("Временная ошибка авторизации VK; повторите подключение")
    private val captcha=CarrierCaptchaBridge(process,allowUi=allowInteractions,client=when(kind) { CarrierKind.CSQTT -> "csqtt"; CarrierKind.WDTTPLUS -> "wdttplus"; CarrierKind.OPENFLUX -> "openflux"; CarrierKind.OPENFLUXKEY -> "openfluxkey"; else -> "qwdtt" })
    private val health=NativeTransportHealth(minimumRecoveryPeak=if(kind==CarrierKind.CSQTT) 10 else 9)
    private var wdttLifecycle: WdttPlusLifecycle? = null
    private var wdttBootstrap: WdttPlusBootstrapRelay? = null
    private var wdttDeadSince=0L
    fun recoveryFailure(): Pair<String,Boolean>? {
        if(closed.get() || captcha.waiting) return null
        if(captcha.userCancelled) return (if(kind in listOf(CarrierKind.OPENFLUX,CarrierKind.OPENFLUXKEY)) "Проверка Яндекса отменена" else "Подтверждение VK отменено") to true
        startupFailure.get()?.let { return it to true }
        val exited=try { process.exitValue();true } catch(_:IllegalThreadStateException) { false }
        // WDTT Plus v20 keeps a live Go process through loss of workers, DNS and network handovers.
        // Its native connection policy repairs individual channels without creating new VK calls.
        if(kind == CarrierKind.WDTTPLUS) {
            if(!exited) { wdttDeadSince=0L; return null }
            val now=System.nanoTime()/1_000_000
            if(wdttDeadSince == 0L) wdttDeadSince=now
            if(now-wdttDeadSince < 60_000L) return null
        }
        if(health.wrongPassword(false)) return WRONG_PASSWORD to true
        return if(exited || health.needsRestart(false,false)) "Транспорт потерял соединение" to false else null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        wdttLifecycle?.close(); wdttLifecycle = null
        wdttBootstrap?.close(); wdttBootstrap = null
        captcha.close()
        runCatching { process.outputStream.write("STOP\n".toByteArray()); process.outputStream.flush() }
        runCatching { process.outputStream.close() }
        // Let the Go wrapper shut down its Rust child before sending SIGTERM.
        val deadline=System.nanoTime()+NativeSessionPolicy.shutdownGraceMs(kind)*1_000_000L
        var exited=false
        while(System.nanoTime()<deadline) {
            exited=try { process.exitValue();true } catch(_:IllegalThreadStateException) { false }
            if(exited) break
            try { Thread.sleep(20) } catch(_:InterruptedException) { Thread.currentThread().interrupt();break }
        }
        if(!exited) {
            runCatching { process.destroy() }
            val terminated=runCatching { process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if(!terminated) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(1,java.util.concurrent.TimeUnit.SECONDS) }
            }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        config.parentFile?.deleteRecursively()
    }

    companion object {
        private const val WRONG_PASSWORD="Неверный пароль подключения или несовместимый WRAP: сервер не отвечает на рукопожатие"

        suspend fun start(
            kind: CarrierKind, nativeDirectory: File, privateDirectory: File,
            apiLevel: Int, privateJson: String, socksPort: Int,
            bridgeCredentials: CarrierBridgeCredentials,
            timeoutMs: Long = 60_000,
            allowInteractions: Boolean = true,
            onStatus: (NativeConnectionStage) -> Unit = {},
            onCreated: ((CarrierProcess) -> Unit)? = null,
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): CarrierProcess {
            val hashes=app.qthrone.mobile.outbound.json.JsonInput.parseObject(privateJson).array("hashes").size
            val attempts=if(kind==CarrierKind.QWDTT) hashes.coerceIn(1,4) else 1
            val deadline=System.nanoTime()+NativeSessionPolicy.startupTimeoutMs(kind,timeoutMs)*1_000_000
            var attempt=0
            var retries=0
            while(attempt<attempts) {
                coroutineContext.ensureActive()
                val remaining=(deadline-System.nanoTime())/1_000_000
                check(remaining>0) { "Transport client startup timed out" }
                try {
                    var launchJson=NativeSessionPolicy.rotatedHashes(privateJson,attempt)
                    // Every launch is a new epoch on the server, as in the original app; a retry included.
                    if(kind==CarrierKind.CSQTT) {
                        launchJson=CsqttEpoch.apply(launchJson,CsqttEpoch.reserve(),CsqttEpoch.salt())
                    }
                    return startOnce(kind,nativeDirectory,privateDirectory,apiLevel,
                        launchJson,socksPort,bridgeCredentials,
                        remaining,allowInteractions,onStatus,onCreated,launch)
                } catch(error:UnavailableCall) {
                    if(attempt==attempts-1) throw error
                    attempt++
                    runCatching { onStatus(NativeConnectionStage.RECONNECTING) }
                    moe.matsuri.nb4a.utils.CoreLog.write("[Info] [QWDTT] VK call unavailable; trying another configured hash")
                } catch(error:TransientStartup) {
                    if(retries>=2) throw error
                    retries++
                    runCatching { onStatus(NativeConnectionStage.RECONNECTING) }
                    moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Retrying transient transport startup ($retries/2)")
                    delay(retries*2_000L)
                }
            }
            error("No transport candidate")
        }

        private suspend fun startOnce(
            kind: CarrierKind, nativeDirectory: File, privateDirectory: File,
            apiLevel: Int, privateJson: String, socksPort: Int,
            bridgeCredentials: CarrierBridgeCredentials,
            timeoutMs: Long = 60_000,
            allowInteractions: Boolean = true,
            onStatus: (NativeConnectionStage) -> Unit,
            onCreated: ((CarrierProcess) -> Unit)?,
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): CarrierProcess {
            // withContext may discard a successful return when cancellation wins the
            // dispatcher hand-off. Keep ownership outside it so that process is closed too.
            var owner: CarrierProcess? = null
            var pendingBootstrap: WdttPlusBootstrapRelay? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(apiLevel >= kind.minimumApi) { "Transport requires Android API ${kind.minimumApi}" }
                    require(socksPort in 1..65535 && timeoutMs > 0)
                    require(privateJson.toByteArray(Charsets.UTF_8).size <= 65_536) { "Transport configuration is too large" }
                    val executable = File(nativeDirectory, kind.executable)
                    check(executable.isFile && executable.canExecute()) { "Transport client is not installed" }
                    if (kind == CarrierKind.CSQTT) {
                        check(File(nativeDirectory, "libcsqtt_transport.so").canExecute()) { "CSQTT transport client is not installed" }
                    }
                    if (kind == CarrierKind.SLIPSTREAM) {
                        check(File(nativeDirectory, "libslipstream.so").canExecute()) { "Slipstream transport client is not installed" }
                    }
                    check(privateDirectory.isDirectory || privateDirectory.mkdirs()) { "Private transport directory unavailable" }
                    val sessionDirectory=File(privateDirectory,"session-"+UUID.randomUUID().toString())
                    check(sessionDirectory.mkdir()) { "Cannot create private transport session" }
                    val config=File(sessionDirectory,"config.json")
                    check(config.createNewFile()) { "Cannot create private transport configuration" }
                    try {
                        check(config.setReadable(false, false) && config.setWritable(false, false)
                            && config.setReadable(true, true) && config.setWritable(true, true)) { "Cannot protect transport configuration" }
                        config.writeText(privateJson, Charsets.UTF_8)
                        val launchConfig = if (kind == CarrierKind.WDTTPLUS) org.json.JSONObject(privateJson) else null
                        val command = if (launchConfig != null) {
                            val args = launchConfig.getJSONArray("args")
                            val flags = (0 until args.length()).map { args.getString(it) }
                            val masque = if ("-rt-masque=true" in flags) {
                                val state = File(app.qthrone.mobile.ktx.app.noBackupFilesDir,"wdttplus")
                                check(state.isDirectory || state.mkdirs()) { "Cannot create private WDTT Plus state" }
                                val masqueConfig=File(state,"rt-masque-v1.json")
                                val bootstrap = if(WdttPlusBootstrapPolicy.needed(true,launchConfig.optBoolean("masque_server_bootstrap"),masqueConfig.exists())) {
                                    val peerIndex=flags.indexOf("-peer")
                                    val peer=app.qthrone.mobile.outbound.link.LinkParser.parse("udp://"+flags.getOrNull(peerIndex+1).orEmpty())
                                    if(peerIndex >= 0 && peer.isValid) WdttPlusBootstrapRelay.start(peer.host,peer.port(56000)) else null
                                } else null
                                pendingBootstrap=bootstrap
                                listOf("-rt-masque-config",masqueConfig.absolutePath,"-rt-masque-accept-tos=true") +
                                    WdttPlusBootstrapPolicy.arguments(true,launchConfig.optBoolean("masque_server_bootstrap"),masqueConfig.exists(),bootstrap?.address)
                            } else emptyList()
                            listOf(executable.absolutePath) + flags + masque
                        } else listOf(executable.absolutePath, kind.configFlag, config.absolutePath) +
                            if(kind==CarrierKind.QWDTT && app.qthrone.mobile.outbound.json.JsonInput.parseObject(privateJson).bool("no_dtls")) listOf("-notls") else emptyList()
                        val builder = ProcessBuilder(command)
                            .directory(if (launchConfig != null) sessionDirectory else nativeDirectory).redirectErrorStream(true)
                        if (launchConfig != null) builder.environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                        builder.environment()["XDG_CACHE_HOME"] = privateDirectory.absolutePath
                        // The original CSQTT app caps the Rust client's worker pool the same way.
                        if (kind == CarrierKind.CSQTT) {
                            builder.environment()["RAYON_NUM_THREADS"] = "2"
                            // Lifecycle events (stream count, panel restarts) for the watchdog; no addresses or hashes.
                            builder.environment()["CSQTT_DIAGNOSTICS"] = "1"
                        }
                        val child = try { launch(builder) } catch (_: Exception) {
                            throw IllegalStateException("Cannot start transport client")
                        }
                        val running = CarrierProcess(child, config,allowInteractions,kind,onStatus)
                        owner = running
                        running.wdttBootstrap=pendingBootstrap; pendingBootstrap=null
                        onCreated?.invoke(running)
                        if (launchConfig != null) {
                            val start = android.util.Base64.encodeToString(launchConfig.getJSONObject("start").toString().toByteArray(Charsets.UTF_8),
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                            val line = "START_CONFIG|" + start + '\n'
                            synchronized(child) { child.outputStream.write(line.toByteArray(Charsets.UTF_8)); child.outputStream.flush() }
                            running.wdttLifecycle = WdttPlusLifecycle(app.qthrone.mobile.ktx.app) { command ->
                                synchronized(child) { runCatching {
                                    child.outputStream.write((command+'\n').toByteArray(Charsets.UTF_8)); child.outputStream.flush()
                                } }
                            }
                        }
                        runCatching { onStatus(NativeConnectionStage.AUTHORIZING) }
                        val redactor = CarrierLogRedactor(privateJson, bridgeCredentials)
                        moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Starting native transport")
                        val outputReader=Thread({ runCatching { child.inputStream.bufferedReader().use { input ->
                            input.forEachLine { line ->
                                runCatching { onStatus(running.health.accept(line)) }
                                running.captcha.accept(line)
                                if(NativeSessionPolicy.unavailableCall(line)) running.unavailableCall.set(true)
                                NativeSessionPolicy.startupFailure(line)?.let { running.startupFailure.compareAndSet(null,it) }
                                redactor.redact(line)?.let { safe -> moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] $safe") }
                            }
                        } }.also { running.captcha.close() } }, "qThrone-carrier-output").apply { isDaemon = true; start() }
                        var deadline = System.nanoTime() + timeoutMs * 1_000_000
                        var lastWaitTick=System.nanoTime()
                        var interactionBudget=20L*60*1_000_000_000L
                        while (System.nanoTime() < deadline) {
                            coroutineContext.ensureActive()
                            check(!running.captcha.interactionRequired) { "VK confirmation required; connect this profile before testing" }
                            check(!running.captcha.userCancelled) { "VK confirmation cancelled" }
                            running.startupFailure.get()?.let { throw IllegalStateException(it) }
                            if(running.health.wrongPassword(running.captcha.waiting)) throw IllegalStateException(WRONG_PASSWORD)
                            if(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT) && running.health.needsRestart(running.captcha.waiting,true)) throw TransientStartup()
                            val now=System.nanoTime()
                            if(running.captcha.waiting) {
                                val paused=(now-lastWaitTick).coerceAtMost(interactionBudget)
                                deadline+=paused;interactionBudget-=paused
                            }
                            lastWaitTick=now
                            val exited = try { child.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                            if(exited) {
                                outputReader.join(200)
                                running.startupFailure.get()?.let { throw IllegalStateException(it) }
                                if(kind==CarrierKind.QWDTT && running.unavailableCall.get()) throw UnavailableCall()
                                if(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT) && running.health.transientFailure) throw TransientStartup()
                                error(running.startupFailure.get() ?: "Transport client stopped before becoming ready")
                            }
                            if (socksReady(socksPort, bridgeCredentials)) {
                                moe.matsuri.nb4a.utils.CoreLog.write("[Info] [${kind.name}] Native transport ready")
                                runCatching { onStatus(NativeConnectionStage.CONNECTED) }
                                return@withContext running
                            }
                            running.startupFailure.get()?.let { throw IllegalStateException(it) }
                            delay(100)
                        }
                        throw IllegalStateException("Transport client startup timed out")
                    } catch (error: Throwable) {
                        // The pool closes a cancelled startup off the service thread, keeping its session reserved.
                        if (error is kotlinx.coroutines.CancellationException && onCreated != null && owner != null) {
                            owner = null
                            throw error
                        }
                        pendingBootstrap?.close(); pendingBootstrap=null
                        owner?.close()
                        config.parentFile?.deleteRecursively()
                        throw error
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException && onCreated != null && owner != null) {
                    owner = null
                    throw error
                }
                pendingBootstrap?.close()
                owner?.close()
                throw error
            }
        }

        internal fun socksReady(port: Int, credentials: CarrierBridgeCredentials): Boolean = runCatching {
            // A loopback address alone does not isolate this listener from other Android apps.
            // Reject a client which accepts the no-authentication method, even if auth also works.
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.soTimeout = 200
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                val input = socket.getInputStream()
                if (input.read() != 5 || input.read() != 255) return@runCatching false
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.soTimeout = 200
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                val input = socket.getInputStream()
                if (input.read() != 5 || input.read() != 2) return@runCatching false
                val username = credentials.username.toByteArray(Charsets.UTF_8)
                val password = credentials.password.toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(1, username.size.toByte()) + username +
                    byteArrayOf(password.size.toByte()) + password)
                input.read() == 1 && input.read() == 0
            }
        }.getOrDefault(false)
    }
}
