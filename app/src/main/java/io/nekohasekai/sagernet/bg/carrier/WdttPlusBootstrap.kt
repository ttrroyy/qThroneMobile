package app.rawline.mobile.bg.carrier

import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.WdttPlusAdmin
import com.jcraft.jsch.Session
import java.util.concurrent.atomic.AtomicBoolean

internal object WdttPlusBootstrapPolicy {
    fun needed(masque: Boolean, serverBootstrap: Boolean, configExists: Boolean) = masque && serverBootstrap && !configExists
    fun arguments(masque: Boolean, serverBootstrap: Boolean, configExists: Boolean, address: String?): List<String> {
        if(!needed(masque,serverBootstrap,configExists) || address == null) return emptyList()
        require(address.startsWith("127.0.0.1:") && address.substringAfter(':').toIntOrNull()?.let { it in 1..65535 } == true)
        return listOf("-warp-api-relay",address)
    }

    /** Only an unambiguous matching server whose SSH key was already approved may be used. */
    fun server(host: String, dtlsPort: Int, servers: List<DeployServer>): DeployServer? = servers.filter {
        it.kind == "wdttplus" && it["host"].equals(host,ignoreCase=true) &&
            it["peerPort"].toIntOrNull() == dtlsPort && it["hostKey"].startsWith("SHA256:") &&
            it["user"].isNotBlank() && it["sshPort"].toIntOrNull()?.let { port -> port in 1..65535 } == true &&
            (if(it["auth"] == "key") it["key"].isNotBlank() else it["sshPassword"].isNotBlank())
    }.singleOrNull()
}

/** The original first-registration relay: TLS remains end-to-end to the fixed Cloudflare API. */
internal class WdttPlusBootstrapRelay private constructor(private val session: Session, private val port: Int) : AutoCloseable {
    private val closed=AtomicBoolean()
    val address: String get() = "127.0.0.1:$port"

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        runCatching { session.delPortForwardingL("127.0.0.1",port) }
        runCatching { session.disconnect() }
    }

    companion object {
        fun start(host: String, dtlsPort: Int): WdttPlusBootstrapRelay? {
            var session: Session? = null
            try {
                val server=WdttPlusBootstrapPolicy.server(host,dtlsPort,DeployStore.all())
                if(server == null) {
                    moe.matsuri.nb4a.utils.CoreLog.write("[Info] [WDTTPLUS] Для регистрации MASQUE нет сохранённого SSH-доступа к этому серверу; пробуем прямую регистрацию")
                    return null
                }
                val connected=WdttPlusAdmin(server).session()
                session=connected
                val port=connected.setPortForwardingL("127.0.0.1",0,"api.cloudflareclient.com",443)
                check(port in 1..65535)
                return WdttPlusBootstrapRelay(connected,port)
            } catch(error: Exception) {
                runCatching { session?.disconnect() }
                // SSH details can include usernames, hostnames or authentication data; do not copy them into logs.
                moe.matsuri.nb4a.utils.CoreLog.write("[Info] [WDTTPLUS] Регистрация MASQUE по SSH недоступна; пробуем прямую регистрацию")
                return null
            }
        }
    }
}
