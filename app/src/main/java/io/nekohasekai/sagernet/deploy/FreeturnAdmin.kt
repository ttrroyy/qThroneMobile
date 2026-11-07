package app.rawline.mobile.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import org.json.JSONObject

/** The JSON RPC of FreeTurn's own install.sh, as its app runs it over SSH: one command, one answer line. */
object FreeturnRpc {
    const val VERSION = "v4.1.2"
    const val INSTALLER = "https://github.com/hackdiaz-dev/free-turn-proxy/releases/download/v4.1.2/install.sh"
    const val INSTALLER_SHA256 = "91fcea2aa5558d019431da31aa1f32baa0ffb943600529810e073ffa22f83100"
    const val SCRIPT = "/opt/free-turn-proxy/install-rawline.sh"
    val METHODS = listOf("docker", "systemd")
    val BACKENDS = listOf("new", "external")

    fun applyArguments(server: DeployServer): List<String> = buildList {
        add("--method=" + server["ftMethod"].ifBlank { "docker" })
        add("--backend=" + server["ftBackend"].ifBlank { "new" })
        add("--host=" + server["host"])
        add("--listen-port=" + server.port("peerPort"))
        if (server["ftBackend"] == "external") {
            add("--connect=" + server["ftConnect"])
            add("--mode=" + server["ftMode"].ifBlank { "udp" })
        } else add("--wg-port=" + server.port("wgPort"))
        add("--obf-profile=" + server["ftObf"].ifBlank { "rtpopus3" })
        add("--version=$VERSION")
    }

    /** The data object of the answer; a refusal becomes an error with the script's own message. */
    fun data(output: String): JSONObject {
        val answer = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{\"proto\"") }
            ?: throw IllegalStateException("Установщик FreeTurn не вернул ответ")
        val json = JSONObject(answer)
        if (json.optString("result") != "ok") throw IllegalStateException(json.optString("msg").ifBlank { json.optString("code") })
        return json.optJSONObject("data") ?: JSONObject()
    }

    fun shellQuote(value: String) = DeployProtocol.quote(value)

    /** The link with the client's call in it: the server keeps no calls, the link's own «vk» field carries them. */
    fun withCalls(link: String, calls: String): String {
        val scheme = "freeturn://"
        if (calls.isBlank() || !link.startsWith(scheme)) return link
        val json = JSONObject(String(java.util.Base64.getUrlDecoder().decode(link.removePrefix(scheme)), Charsets.UTF_8))
        json.put("vk", calls.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
        return scheme + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
}

/** The client list of a deployed FreeTurn server. */
class FreeturnAdmin(private val server: DeployServer) {
    class Client(val json: JSONObject) {
        val name: String get() = json.optString("name")
        val owner: Boolean get() = json.optBoolean("self")
        val address: String get() = json.optString("ip")
        val handshake: Long get() = json.optLong("hs")
    }

    val available: Boolean get() = server["installed"] == "true" && server["hostKey"].isNotBlank()

    private fun call(vararg arguments: String): JSONObject {
        return SshSessions.use(server) { ssh ->
            val command = (listOf("bash", FreeturnRpc.SCRIPT) + arguments).joinToString(" ") { FreeturnRpc.shellQuote(it) }
            val plan = rootStdinPlan(command, server["sudoPassword"].ifBlank { server["sshPassword"] }, "")
            val channel = ssh.openChannel("exec") as ChannelExec
            channel.setCommand(plan.command + " 2>&1")
            channel.setInputStream(plan.input.byteInputStream())
            val input = channel.inputStream
            channel.connect(30_000)
            val output = input.bufferedReader().use { it.readText() }
            channel.disconnect()
            FreeturnRpc.data(output)
        }
    }

    fun clients(): List<Client> {
        val items = call("client-list").getJSONArray("clients")
        return (0 until items.length()).map { Client(items.getJSONObject(it)) }
    }
    /** The client's freeturn:// link with its WireGuard configuration. */
    fun add(name: String): String = call("client-add", "--name=$name").getString("link")
    fun link(name: String): String = call("client-conf", "--name=$name").getString("link")
    fun remove(name: String) { call("client-remove", "--name=$name") }
}
