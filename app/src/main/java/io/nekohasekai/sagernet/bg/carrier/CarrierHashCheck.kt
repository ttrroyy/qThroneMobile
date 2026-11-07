package app.rawline.mobile.bg.carrier

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Same native -check-hashes protocol as qWDTT's HashCheckHelper (GPL-3.0).
 * Own process, no bridge listener/config, and no access to the running VPN's process. */
class CarrierHashCheck : AutoCloseable {
    companion object {
        internal fun stateDirectory(cache:File,deviceId:String):File {
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
            val key=digest.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            return File(cache,"carriers/Rawline/qwdtt/$key")
        }
    }
    private val stopped = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var captcha: CarrierCaptchaBridge? = null
    @Volatile private var watchdog: Thread? = null

    override fun close() {
        stopped.set(true)
        process?.let { runCatching { it.destroyForcibly() } }
        captcha?.close()
        watchdog?.interrupt()
    }

    /**
     * CSQTT's own check, as its original app runs it: `--validate-vk-hashes` asks VK for an anonymous
     * token of each call, one after another, without TURN credentials or a captcha.
     */
    fun runCsqtt(context: Context, hashes: List<Pair<Int, String>>, onUpdate: (Int, String) -> Unit, client: String = "libcsqtt_transport.so") {
        if (hashes.isEmpty() || stopped.get()) return
        require(hashes.all { it.second.matches(Regex("[A-Za-z0-9_-]{16,512}")) }) { "Некорректный хеш VK" }
        val binary = File(context.applicationInfo.nativeLibraryDir, client)
        check(binary.canExecute()) { "Бинарный клиент не найден" }
        val child = ProcessBuilder(binary.absolutePath, "--validate-vk-hashes", "--vk", hashes.joinToString(",") { it.second },
            "--fingerprint", "firefox", "--client-ids", "8202606,6287487")
            .directory(context.cacheDir).redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
            .start()
        process = child
        if (stopped.get()) { close(); return }
        hashes.forEach { onUpdate(it.first, "Проверяется…") }
        val finished = mutableSetOf<Int>()
        // The original allows 75 s for the whole list.
        val killer = Thread({
            try { if (!child.waitFor(75, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly() }
            catch (_: InterruptedException) { }
        }, "Rawline-csqtt-hash-check-timeout").apply { isDaemon = true }
        watchdog = killer
        killer.start()
        try {
            child.inputStream.bufferedReader().use { reader ->
                while (!stopped.get()) {
                    val line = reader.readLine() ?: break
                    val result = runCatching { org.json.JSONObject(line.substringAfter("HASH_CHECK:", "")) }.getOrNull() ?: continue
                    val slots = hashes.filter { it.second == result.optString("hash") }.map { it.first }
                    // VK's messages can echo the call link: only the verdict is shown.
                    val status = when (result.optString("status")) {
                        "valid" -> "Звонок доступен"
                        "invalid" -> "Звонок закрыт или хеш недействителен"
                        else -> "Не удалось проверить"
                    }
                    slots.forEach { finished.add(it); onUpdate(it, status) }
                }
            }
            if (!stopped.get()) hashes.filter { it.first !in finished }.forEach { onUpdate(it.first, "Нет ответа проверки") }
        } finally { close() }
    }

    /**
     * WDTT Plus checks with its own client: `-check-hashes`, the hashes on stdin as START_CONFIG,
     * its own captcha chain through the bridge, 90 s per hash.
     */
    fun runWdttPlus(context: Context, hashes: List<Pair<Int, String>>, captchaMode: String, onUpdate: (Int, String) -> Unit) {
        if (hashes.isEmpty() || stopped.get()) return
        require(hashes.all { it.second.matches(Regex("[A-Za-z0-9_-]{16,512}")) }) { "Некорректный хеш VK" }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libwdttplus.so")
        check(binary.canExecute()) { "Бинарный клиент не найден" }
        val state = File(context.cacheDir, "wdttplus-check").apply { mkdirs() }
        val child = ProcessBuilder(binary.absolutePath, "-check-hashes", "-startup-config-stdin=true",
            "-captcha-mode", captchaMode.takeIf { it in listOf("auto", "wv", "rjs") } ?: "auto",
            "-fingerprint", "firefox", "-client-ids", "6287487,8202606", "-vkcalls-preflight=true")
            .directory(state).redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
            .start()
        process = child
        if (stopped.get()) { close(); return }
        val start = org.json.JSONObject().put("vk_hashes", hashes.joinToString(",") { it.second })
            .put("connection_password", "").put("custom_vk_client_id", "").put("custom_vk_client_secret", "").toString()
        val line = "START_CONFIG|" + android.util.Base64.encodeToString(start.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) + "\n"
        synchronized(child) { runCatching { child.outputStream.write(line.toByteArray(Charsets.UTF_8)); child.outputStream.flush() } }
        val bridge = CarrierCaptchaBridge(child, onlyIfUiIdle = true, client = "wdttplus")
        captcha = bridge
        if (stopped.get()) { close(); return }
        val finished = mutableSetOf<Int>()
        val timedOut = AtomicBoolean(false)
        val killer = Thread({
            try {
                if (!child.waitFor(hashes.size * 100_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    timedOut.set(true); child.destroyForcibly(); bridge.close()
                }
            } catch (_: InterruptedException) { }
        }, "Rawline-wdttplus-hash-check-timeout").apply { isDaemon = true }
        watchdog = killer
        killer.start()
        try {
            child.inputStream.bufferedReader().use { reader ->
                while (!stopped.get()) {
                    val output = reader.readLine() ?: break
                    if (stopped.get()) break
                    bridge.accept(output)
                    HashCheckProtocol.parse(output, hashes)?.let { if (it.finished) finished.add(it.slot); onUpdate(it.slot, it.message) }
                }
            }
            if (!stopped.get()) hashes.filter { it.first !in finished }.forEach {
                onUpdate(it.first, if (timedOut.get()) "Время проверки истекло" else "Нет ответа проверки")
            }
        } finally { close() }
    }

    fun run(context: Context, hashes: List<Pair<Int, String>>, captchaMode: String,
            anonPath: String, dns: String, deviceId: String, onUpdate: (Int, String) -> Unit) {
        if (hashes.isEmpty() || stopped.get()) return
        require(hashes.all { it.second.matches(Regex("[A-Za-z0-9_-]{16,512}")) }) { "Некорректный хеш VK" }
        require(deviceId.isNotBlank() && deviceId.none { it == '|' || it == '\n' || it == '\r' })
        // Match bridge_config.go: use the same per-profile VK browser identity as the tunnel.
        val state = stateDirectory(context.cacheDir,deviceId)
        check(state.isDirectory || state.mkdirs()) { "Не удалось открыть каталог проверки VK" }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libqwdtt.so")
        check(binary.canExecute()) { "Бинарный клиент не найден" }
        val child = ProcessBuilder(binary.absolutePath, "-check-hashes", "-vk", hashes.joinToString(",") { it.second },
            "-captcha-mode", captchaMode, "-vk-auth", "anonymous", "-vk-anon-path",
            if (anonPath == "legacy") "legacy" else "vkcalls", "-go-dns", dns,
            "-device-id", deviceId)
            .directory(state).redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                environment()["XDG_CACHE_HOME"] = File(context.cacheDir,"carriers").absolutePath
            }.start()
        process = child
        if (stopped.get()) { close(); return }
        val bridge = CarrierCaptchaBridge(child, onlyIfUiIdle = true)
        captcha = bridge
        if (stopped.get()) { close(); return }
        val finished = mutableSetOf<Int>()
        val timedOut = AtomicBoolean(false)
        val killer = Thread({
            try {
                if (!child.waitFor((hashes.size * 100_000L).coerceAtLeast(90_000L), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    timedOut.set(true)
                    child.destroyForcibly()
                    bridge.close()
                }
            } catch (_: InterruptedException) { }
        }, "Rawline-hash-check-timeout").apply { isDaemon = true }
        watchdog = killer
        if (stopped.get()) { close(); return }
        killer.start()
        try {
            child.inputStream.bufferedReader().use { reader ->
                while (!stopped.get()) {
                    val line = reader.readLine() ?: break
                    if (stopped.get()) break
                    bridge.accept(line)
                    HashCheckProtocol.parse(line,hashes)?.let {
                        if(it.finished) finished.add(it.slot)
                        onUpdate(it.slot,it.message)
                    }
                }
            }
            if (!stopped.get()) hashes.filter { it.first !in finished }.forEach {
                onUpdate(it.first, if (timedOut.get()) "Время проверки истекло" else "Нет ответа проверки")
            }
        } finally { close() }
    }
}
