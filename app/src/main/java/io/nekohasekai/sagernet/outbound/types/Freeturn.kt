package app.rawline.mobile.outbound.types

import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.bg.carrier.CarrierKind
import app.rawline.mobile.bg.carrier.NativeCarrier
import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf
import app.rawline.mobile.outbound.link.Hosts
import app.rawline.mobile.outbound.link.LinkParser
import java.util.Base64

/**
 * A FreeTurn profile: its client relays WireGuard or AmneziaWG over the TURN servers of a VK call, or straight to the
 * server, behind an authenticated SOCKS5 loopback bridge. In TCP mode the next hop of a chain speaks to the server's backend.
 */
class Freeturn : Outbound("freeturn"), NativeCarrier {
    override val carrierKind get() = if (tunnelMode == "tcp") CarrierKind.FREETURN_TCP else CarrierKind.FREETURN

    @JvmField var provider = "vk"
    /** "auto": the linked VK account's call, made when connecting; "manual": the links below. */
    @JvmField var hashSource = "manual"
    @JvmField var links = ""
    @JvmField var threads = DEFAULT_THREADS
    @JvmField var streamsPerCred = DEFAULT_THREADS
    @JvmField var relayTransport = "tcp"
    @JvmField var tunnelMode = "udp"
    @JvmField var bond = false
    @JvmField var obfProfile = "none"
    @JvmField var obfKey = ""
    @JvmField var obfTiming = 0
    @JvmField var clientId = ""
    @JvmField var dnsMode = "auto"
    @JvmField var dnsServers = ""
    @JvmField var manualCaptcha = false
    @JvmField var turnHost = ""
    @JvmField var wgConfig = ""
    @JvmField var kcp: JsonObject? = null

    init {
        type = "freeturn"
        serverPort = 56000
    }

    override fun displayType() = "FreeTurn (" + (if (provider == "direct") "Direct" else "VK") + ")"
    override fun displayAddress(): String = Hosts.displayAddress(server, serverPort)

    /** A bare hash from the hash screen is a call link too. */
    private fun linkList() = links.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { if ('/' in it) it else CALL_LINK + it }

    /** A launch-only copy, as for qWDTT: account calls stay out of stored profiles, exports and subscriptions. */
    fun withCalls(hashes: List<String>): Freeturn {
        val copy = Freeturn()
        check(copy.parseFromJson(exportToJson()))
        copy.links = hashes.joinToString("\n") { CALL_LINK + it }
        copy.hashSource = "manual"
        return copy
    }

    private fun isValid() = server.isNotEmpty() && Hosts.normalizeHost(server, false) != null && serverPort in 1..65535 &&
        provider in listOf("vk", "direct") && relayTransport in listOf("tcp", "udp") && tunnelMode in listOf("udp", "tcp") &&
        obfProfile in OBF_PROFILES && dnsMode in listOf("auto", "plain", "doh") && threads in 0..256 && streamsPerCred in 0..256 && obfTiming >= 0 &&
        (obfProfile == "none" || obfKey.matches(Regex("[0-9a-fA-F]{64}"))) && (clientId + turnHost + dnsServers).none(Char::isWhitespace)

    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "freeturn") return false
        name = obj.string("name").ifEmpty { obj.string("tag") }
        server = obj.string("server").trim()
        serverPort = if (obj.contains("server_port")) obj.int("server_port") else 56000
        provider = obj.string("provider").ifEmpty { "vk" }
        links = obj.string("links").trim()
        // A link without a call takes one from the account: the call is each client's own, the server's link never holds it.
        hashSource = obj.string("hash_source").ifEmpty { if (links.isEmpty() && provider != "direct") "auto" else "manual" }
        threads = if (obj.contains("threads")) obj.int("threads") else DEFAULT_THREADS
        streamsPerCred = if (obj.contains("streams_per_cred")) obj.int("streams_per_cred") else DEFAULT_THREADS
        relayTransport = obj.string("relay_transport").ifEmpty { "tcp" }
        tunnelMode = obj.string("tunnel_mode").ifEmpty { "udp" }
        bond = obj.bool("bond")
        obfProfile = obj.string("obf_profile").ifEmpty { "none" }
        obfKey = obj.string("obf_key").trim()
        obfTiming = obj.int("obf_timing")
        clientId = obj.string("client_id").trim()
        dnsMode = obj.string("dns_mode").ifEmpty { "auto" }
        dnsServers = obj.string("dns_servers").trim()
        manualCaptcha = obj.bool("manual_captcha")
        turnHost = obj.string("turn_host").trim()
        wgConfig = obj.string("wg_config").trim()
        kcp = if (obj.contains("kcp")) obj.obj("kcp") else null
        return isValid()
    }

    /** The original app's freeturn:// share link, where a call link may be missing: it belongs to each client. */
    override fun parseFromLink(link: String): Boolean {
        val trimmed = link.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return false
        val payload = runCatching { JsonInput.parseObject(String(Base64.getUrlDecoder().decode(trimmed.substring(SCHEME.length)), Charsets.UTF_8)) }.getOrNull() ?: return false
        if (payload.int("v") != 1) return false
        val peer = LinkParser.parse("udp://" + payload.string("peer"))
        if (!peer.isValid) return false
        val obj = jsonObjectOf("type" to "freeturn", "name" to payload.string("name"), "server" to peer.host, "server_port" to peer.port(56000),
            "provider" to payload.string("provider"), "relay_transport" to payload.string("transport"), "tunnel_mode" to payload.string("mode"),
            "bond" to payload.bool("bond"), "obf_profile" to payload.string("obf"), "obf_key" to payload.string("key"), "obf_timing" to payload.int("timing"),
            "client_id" to payload.string("cid"), "dns_mode" to payload.string("dns"), "dns_servers" to payload.string("dnss"),
            "manual_captcha" to payload.bool("mcap"), "links" to payload.string("vk"), "wg_config" to payload.string("wg"))
        if (payload.int("n") != 0) obj["threads"] = payload.int("n")
        if (payload.int("spc") != 0) obj["streams_per_cred"] = payload.int("spc")
        if (payload.contains("kcp")) obj["kcp"] = payload.obj("kcp")
        return parseFromJson(obj)
    }

    override fun exportToJson(): JsonObject = jsonObjectOf("type" to "freeturn", "name" to name, "server" to server, "server_port" to serverPort,
        "provider" to provider, "hash_source" to hashSource, "links" to links, "threads" to threads, "streams_per_cred" to streamsPerCred, "relay_transport" to relayTransport,
        "tunnel_mode" to tunnelMode, "bond" to bond, "obf_profile" to obfProfile, "obf_key" to obfKey, "obf_timing" to obfTiming, "client_id" to clientId,
        "dns_mode" to dnsMode, "dns_servers" to dnsServers, "manual_captcha" to manualCaptcha, "turn_host" to turnHost, "wg_config" to wgConfig).also { obj ->
        kcp?.let { obj["kcp"] = it }
    }

    override fun exportIdentity(): JsonObject = exportToJson().also { it.remove("name"); it.remove("client_id") }

    override fun exportToLink(): String {
        val obj = jsonObjectOf("v" to 1, "provider" to provider, "peer" to Hosts.displayAddress(server, serverPort), "transport" to relayTransport, "mode" to tunnelMode)
        if (bond) obj["bond"] = true
        if (obfProfile != "none") { obj["obf"] = obfProfile; obj["key"] = obfKey; if (obfTiming != 0) obj["timing"] = obfTiming }
        if (threads != 0) obj["n"] = threads
        if (streamsPerCred != 0) obj["spc"] = streamsPerCred
        if (clientId.isNotEmpty()) obj["cid"] = clientId
        if (dnsMode != "auto") obj["dns"] = dnsMode
        if (dnsServers.isNotEmpty()) obj["dnss"] = dnsServers
        if (manualCaptcha) obj["mcap"] = true
        kcp?.let { obj["kcp"] = it }
        if (name.isNotEmpty()) obj["name"] = name
        if (links.isNotEmpty()) obj["vk"] = links
        if (wgConfig.isNotEmpty()) obj["wg"] = wgConfig
        return SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
    }

    override fun build(ctx: BuildContext): BuildResult = BuildResult(JsonObject(), "FreeTurn requires a prepared native transport bridge")

    /** config.ClientJSON of the core: its decoder refuses unknown fields, so only these reach it. */
    override fun privateConfig(port: Int, udpPort: Int, credentials: CarrierBridgeCredentials): JsonObject {
        require(isValid()) { "Проверьте параметры профиля FreeTurn" }
        require(provider == "direct" || hashSource == "auto" || linkList().isNotEmpty()) { "Добавьте ссылку на звонок VK в профиль" }
        require(wgConfig.isEmpty() || wgConfig.contains("[Interface]", true)) { "Проверьте конфигурацию WireGuard или AmneziaWG" }
        val tcp = tunnelMode == "tcp"
        val tunnel = !tcp && wgConfig.isNotEmpty()
        val core = jsonObjectOf("peer" to Hosts.displayAddress(server, serverPort), "clientId" to clientId, "subUrl" to "", "provider" to provider, "routes" to false,
            "turn" to jsonObjectOf("n" to if (provider == "direct") 1 else threads.takeIf { it > 0 } ?: DEFAULT_THREADS, "transport" to relayTransport, "host" to turnHost, "port" to ""),
            "proxy" to jsonObjectOf("mode" to tunnelMode, "listen" to Hosts.displayAddress("127.0.0.1", udpPort)).also { if (tcp && bond) it["bond"] = true },
            "vk" to jsonObjectOf("links" to JsonArray.of(*linkList().ifEmpty { listOf("") }.toTypedArray()), "streamsPerCred" to (streamsPerCred.takeIf { it > 0 } ?: DEFAULT_THREADS),
                "manualCaptcha" to manualCaptcha, "autoCaptcha" to true, "advancedCaptcha" to false, "platform" to "mobile"),
            "obf" to jsonObjectOf("profile" to obfProfile, "key" to if (obfProfile == "none") "" else obfKey, "timingMs" to if (obfProfile == "none") 0 else obfTiming),
            "dns" to jsonObjectOf("mode" to dnsMode, "servers" to JsonArray.of(*dnsServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())),
            "log" to jsonObjectOf("debug" to false),
            "tunnel" to jsonObjectOf("mode" to if (tunnel) "awg" else "none", "config" to if (tunnel) wgConfig else "", "mtu" to WG_MTU))
        if (tcp) kcp?.let { core["kcp"] = it }
        val state = java.io.File(app.rawline.mobile.ktx.app.noBackupFilesDir, "freeturn")
        return jsonObjectOf("core" to core, "state_dir" to state.absolutePath, "socks" to Hosts.displayAddress("127.0.0.1", port),
            "socks_user" to credentials.username, "socks_pass" to credentials.password)
    }

    companion object {
        const val SCHEME = "freeturn://"
        const val CALL_LINK = "https://vk.com/call/join/"
        const val DEFAULT_THREADS = 12
        // WireGuard goes over TURN: the original's constant, the server keeps the same value.
        const val WG_MTU = 1280
        val OBF_PROFILES = listOf("none", "rtpopus", "rtpopus2", "rtpopus3")
    }
}
