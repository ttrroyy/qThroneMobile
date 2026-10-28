package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.bg.carrier.CarrierKind
import app.qthrone.mobile.bg.carrier.NativeCarrier
import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/**
 * An SSTP (Secure Socket Tunneling Protocol) profile: Microsoft's PPP-over-HTTPS
 * protocol with MS-CHAPv2 authentication, running behind an authenticated SOCKS5
 * loopback bridge via libsstp.so.
 */
class Sstp : Outbound("sstp"), NativeCarrier {
    override val carrierKind get() = CarrierKind.SSTP

    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var sni: String = ""
    @JvmField var allowInsecure: Boolean = false
    @JvmField var useServerDns: Boolean = false

    init {
        type = "sstp"
        serverPort = 443
    }

    override fun displayType(): String = "SSTP"

    override fun displayAddress(): String = Hosts.displayAddress(server, serverPort)

    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "sstp") return false
        name = obj.string("name").ifEmpty { obj.string("tag") }
        val (cleanedServer, detectedPort) = cleanServerAddress(obj.string("server"))
        server = cleanedServer
        if (obj.contains("server_port") && obj.int("server_port") !in 1..65535) return false
        serverPort = when {
            obj.contains("server_port") && obj.int("server_port") in 1..65535 -> obj.int("server_port")
            detectedPort != null -> detectedPort
            else -> 443
        }
        username = obj.string("username").trim()
        password = obj.string("password")
        sni = obj.string("sni").trim()
        allowInsecure = if (obj.contains("allow_insecure")) obj.bool("allow_insecure") else false
        useServerDns = obj.bool("use_server_dns")
        return isValid()
    }

    private fun isValid(): Boolean =
        server.isNotEmpty() && Hosts.normalizeHost(server, false) != null && serverPort in 1..65535 &&
            username.isNotEmpty() && password.isNotEmpty() && username.none { it.isISOControl() } &&
            (sni.isEmpty() || Hosts.normalizeHost(sni, false) != null)

    companion object {
        fun cleanServerAddress(raw: String): Pair<String, Int?> {
            var s = raw.trim()
            for (p in listOf("https://", "http://", "sstp://")) {
                if (s.startsWith(p, ignoreCase = true)) {
                    s = s.substring(p.length)
                    break
                }
            }
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if (Hosts.isIpv6Address(s)) return Hosts.normalizeHost(s, false).orEmpty() to null
            val parsed = LinkParser.parse("sstp://$s")
            if (!parsed.isValid || parsed.userName.isNotEmpty() || parsed.password.isNotEmpty()) return "" to null
            if (parsed.port != -1 && parsed.port !in 1..65535) return "" to null
            return parsed.host to parsed.port.takeIf { it in 1..65535 }
        }
    }

    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link.trim())
        if (!url.isValid || url.scheme != "sstp") return false
        name = url.fragment.orEmpty()
        server = url.host
        serverPort = if (url.port == -1) 443 else url.port
        username = url.userName
        password = url.password
        sni = if (url.query.has("sni")) url.query.valueFully("sni").trim() else ""
        allowInsecure = if (url.query.has("insecure")) {
            val v = url.query.valueFully("insecure")
            v == "1" || v.equals("true", ignoreCase = true)
        } else false
        useServerDns = url.query.value("use_server_dns") in listOf("1", "true")
        return isValid()
    }

    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "sstp"
        if (name.isNotEmpty()) obj["name"] = name
        obj["server"] = server
        obj["server_port"] = serverPort
        obj["username"] = username
        obj["password"] = password
        if (sni.isNotEmpty()) obj["sni"] = sni
        if (allowInsecure) obj["allow_insecure"] = true
        obj["use_server_dns"] = useServerDns
        return obj
    }

    override fun exportIdentity(): JsonObject = exportToJson().also {
        it.remove("name")
        it.remove("tag")
    }

    override fun exportToLink(): String {
        val url = LinkBuilder("sstp")
        url.host = server
        url.port = if (serverPort != 443) serverPort else -1
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (sni.isNotEmpty()) url.addQueryItem("sni", sni)
        if (allowInsecure) url.addQueryItem("insecure", "1")
        if (useServerDns) url.addQueryItem("use_server_dns", "1")
        return url.build()
    }

    override fun build(ctx: BuildContext): BuildResult =
        BuildResult(JsonObject(), "SSTP requires a prepared native transport bridge")

    override fun privateConfig(port: Int, udpPort: Int, credentials: CarrierBridgeCredentials): JsonObject {
        val (cleanedServer, detectedPort) = cleanServerAddress(server)
        if (cleanedServer.isNotEmpty()) {
            server = cleanedServer
            if (detectedPort != null && (serverPort == 443 || serverPort == 0)) {
                serverPort = detectedPort
            }
        }
        require(isValid()) { "Некорректная конфигурация SSTP: проверьте адрес сервера (1..65535) и логин" }
        return exportToJson().also {
            it["backend"] = "sstp"
            it["socks"] = Hosts.displayAddress("127.0.0.1", port)
            it["socks_user"] = credentials.username
            it["socks_pass"] = credentials.password
        }
    }
}
