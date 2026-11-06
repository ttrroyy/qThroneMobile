package app.rawline.mobile.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The WDTT Plus server's admin interface, as its app's ServerAdminClient drives it: over SSH as root,
 * `wdtt-server admin --request-stdin` reads {"main_password", "args"} and answers with one JSON line.
 */
class WdttPlusAdmin(private val server: DeployServer) {
    class Access(val json: JSONObject) {
        val password: String get() = json.optString("password")
        val label: String get() = json.optString("label")
        val vkHash: String get() = json.optString("vk_hash")
        val ports: String get() = json.optString("ports")
        val status: String get() = json.optString("status")
        val expiresAt: Long get() = json.optLong("expires_at")
        val deviceId: String get() = json.optString("device_id")
        val deviceName: String get() = json.optString("device_name").ifBlank { json.optJSONObject("device")?.optString("name").orEmpty() }
        val deviceIp: String get() = json.optString("device_ip")
        val lastSeen: Long get() = json.optLong("device_last_seen")
        val downBytes: Long get() = json.optLong("down_bytes")
        val upBytes: Long get() = json.optLong("up_bytes")
        // Server statuses: active, deactivated, expired, expired_retained, broken.
        val deactivated: Boolean get() = status == "deactivated"
        val expired: Boolean get() = status.startsWith("expired")
        val hashes: List<String> get() = vkHash.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val title: String get() = label.ifBlank { password }
        fun traffic(period: String): Long = json.optJSONObject("traffic")?.optJSONObject(period)?.let { it.optLong("down") + it.optLong("up") } ?: (downBytes + upBytes)
    }

    class State(val server: JSONObject, val accesses: List<Access>)

    val available: Boolean get() = server["installed"] == "true" && server["hostKey"].isNotBlank() && server["password"].isNotBlank()

    internal fun session(): Session {
        val jsch = JSch()
        if (server["auth"] == "key") jsch.addIdentity("Rawline", server["key"].toByteArray(), null, server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        // Only the key trusted during deploy is accepted here; a new key goes through the deploy editor.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, bytes: ByteArray): Int {
                val fingerprint = "SHA256:" + android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                return if (server["hostKey"].isNotBlank() && server["hostKey"] == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, userinfo: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "Rawline encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        return jsch.getSession(server["user"], server["host"], server.port("sshPort")).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", if (server["auth"] == "key") "publickey" else "password,keyboard-interactive")
            if (server["auth"] != "key") setPassword(server["sshPassword"])
            connect(20_000)
        }
    }

    /** One admin request; sudo's password, when needed, goes first on stdin. */
    private fun call(args: List<String>, timeoutMs: Long = 45_000): JSONObject {
        val request = JSONObject().put("main_password", server["password"]).put("args", JSONArray(args)).toString()
        val ssh = session()
        try {
            val channel = ssh.openChannel("exec") as ChannelExec
            val plan = rootStdinPlan("/usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin",
                server["sudoPassword"].ifBlank { server["sshPassword"] },request+"\n")
            channel.setCommand(plan.command + " 2>&1")
            channel.setInputStream(plan.input.byteInputStream())
            val input = channel.inputStream
            channel.connect(20_000)
            val output = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (true) {
                while (input.available() > 0) { val count = input.read(buffer); if (count < 0) break; output.append(String(buffer, 0, count, Charsets.UTF_8)) }
                if (channel.isClosed && input.available() == 0) break
                check(System.currentTimeMillis() < deadline) { "Сервер не ответил вовремя" }
                Thread.sleep(80)
            }
            channel.disconnect()
            val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") }
                ?: throw IllegalStateException(friendly(output.toString().ifBlank { "сервер не вернул ответ" }))
            val json = JSONObject(line)
            if (!json.optBoolean("ok", false)) throw IllegalStateException(friendly(json.optString("message", line)))
            return json
        } finally { ssh.disconnect() }
    }

    private fun friendly(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "no_passwords_json" in lower -> "На сервере не найдена база WDTT Plus. Сначала выполните деплой."
            "admin_socket_unavailable" in lower -> "Сервер не поддерживает управление без перезапуска. Обновите сервер."
            "main_password" in lower || "unauthorized" in lower -> "Главный пароль не подходит к серверу."
            else -> raw.lines().lastOrNull { it.isNotBlank() }?.take(300) ?: raw.take(300)
        }
    }

    fun list(): State {
        val json = call(listOf("list"))
        val items = json.optJSONArray("passwords") ?: JSONArray()
        return State(json.optJSONObject("server") ?: JSONObject(), (0 until items.length()).map { Access(items.getJSONObject(it)) })
    }

    fun create(label: String, hashes: List<String>, days: Int, password: String?): Access {
        val args = mutableListOf("create", "--days", days.coerceIn(0, 365).toString())
        if (label.isNotBlank()) args += listOf("--label", label.trim())
        if (hashes.isNotEmpty()) args += listOf("--vk-hash", hashes.joinToString(","))
        password?.takeIf { it.isNotBlank() }?.let { args += listOf("--client-password", it) }
        return Access(call(args).optJSONObject("password") ?: JSONObject())
    }

    fun action(command: String, password: String) { call(listOf(command, "--password", password)) }
    fun setExpiry(password: String, days: Int) { call(listOf("set-expiry", "--password", password, "--days", days.coerceIn(0, 365).toString())) }
    fun setLabel(password: String, label: String) { call(listOf("set-label", "--password", password, "--label", label)) }
    fun setHash(password: String, hashes: List<String>) { call(listOf("set-hash", "--password", password, "--vk-hash", hashes.joinToString(","))) }
    fun setPassword(password: String, newPassword: String) { call(listOf("set-password", "--password", password, "--new-password", newPassword)) }

    fun updateSettings(dns: String, limit: Int, defaultPorts: String, publicHost: String) {
        call(listOf("update-settings", "--dns", dns, "--limit", limit.coerceIn(1, 500).toString(), "--ports", defaultPorts, "--public-ip", publicHost.ifBlank { "auto" }))
    }
    fun maintenance(command: String) { call(listOf(command)) }

    fun backups(): JSONObject = call(listOf("backup-status"), 120_000).optJSONObject("backup_status") ?: JSONObject()
    fun configureBackups(enabled: Boolean, intervalHours: Int, retention: Int): JSONObject =
        call(listOf("backup-configure", "--enabled", enabled.toString(), "--interval-hours", intervalHours.coerceIn(6, 168).toString(),
            "--retention", retention.coerceIn(2, 90).toString()), 120_000).optJSONObject("backup_status") ?: JSONObject()
    fun createBackup(reason: String = "manual"): JSONObject {
        require(reason in setOf("manual", "pre_deploy", "pre_restore")) { "Некорректная причина резервной копии" }
        return call(listOf("backup-create", "--reason", reason), 180_000).optJSONObject("backup_status") ?: JSONObject()
    }
    fun verifyBackup(id: String): JSONObject = call(listOf("backup-verify", "--id", id), 120_000).optJSONObject("backup") ?: JSONObject()
    fun deleteBackup(id: String): JSONObject = call(listOf("backup-delete", "--id", id, "--confirm", "УДАЛИТЬ"), 120_000).optJSONObject("backup_status") ?: JSONObject()
    fun exportBackup(id: String): JSONObject = call(listOf("backup-export", "--id", id), 180_000).optJSONObject("backup_document") ?: JSONObject()

    /** The connection link of one access: wdtt://connect?v=1, as its app's buildServerConnectionLink. */
    fun link(access: Access, state: State): String? {
        val ports = access.ports.ifBlank { state.server.optString("default_ports", "56000,56001,9000") }.split(',').map { it.trim().toIntOrNull() }
        if (ports.size != 3 || ports.any { it == null || it !in 1..65535 }) return null
        val host = state.server.optString("effective_public_ip").ifBlank { state.server.optString("public_ip") }.ifBlank { server["host"] }
        return app.rawline.mobile.outbound.types.WdttPlus().apply {
            this.server = host; serverPort = ports[0]!!; wgPort = ports[1]!!; localPort = ports[2]!!
            password = access.password; hashes = access.hashes; name = access.title
        }.exportToLink()
    }
}
