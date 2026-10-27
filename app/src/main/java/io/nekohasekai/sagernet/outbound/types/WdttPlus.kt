package app.qthrone.mobile.outbound.types

import android.os.Build
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.bg.carrier.CarrierKind
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import java.util.Locale
import java.util.TimeZone

/**
 * WDTT Plus, run by its own native client as its app runs it: the hashes and password arrive on
 * stdin (START_CONFIG), the tunnel is offered as an authenticated SOCKS5 for this launch only.
 */
class WdttPlus : Qwdtt() {
    override val carrierKind get() = CarrierKind.WDTTPLUS
    var wgPort = 56001
    var localPort = 9000
    var maxWorkers = 0
    var tcpOnly = false
    var turnSni = ""
    var rtMasque = false
    var rtMasqueServerBootstrap = false
    var fingerprint = "firefox"
    var clientIds = "6287487,8202606"
    var vkCallsPreflight = true
    /** Imported from a plain wdtt:// link: shown as WDTT and shared in the same form. */
    var legacy = false
    init { type = "wdttplus"; serverPort = 56000; workers = 18; mode = "wg" }

    override fun displayType() = if (legacy) "WDTT" else "WDTT Plus"

    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.string("type") != "wdttplus") return false
        val copy = obj.copy(); copy["type"] = "qwdtt"; copy.remove("mode"); copy.remove("raw_port"); copy.remove("turn_tcp")
        val requestedWorkers = if (obj.contains("workers")) obj.int("workers") else 18
        if (!super.parseFromJson(copy)) return false
        wgPort = if (obj.contains("wg_port")) obj.int("wg_port") else 56001
        localPort = if (obj.contains("local_port")) obj.int("local_port") else 9000
        maxWorkers = if (obj.contains("max_workers")) obj.int("max_workers") else 0
        legacy = obj.bool("legacy")
        tcpOnly = obj.bool("tcp_only")
        turnSni = normalizeTurnSni(obj.string("turn_sni")) ?: return false
        rtMasque = obj.bool("rt_masque")
        rtMasqueServerBootstrap = obj.bool("rt_masque_server_bootstrap")
        fingerprint = obj.string("fingerprint").ifBlank { "firefox" }
        clientIds = obj.string("client_ids").ifBlank { "6287487,8202606" }
        vkCallsPreflight = !obj.contains("vkcalls_preflight") || obj.bool("vkcalls_preflight")
        workers = normalizeWorkers(requestedWorkers, maxWorkers)
        return wgPort in 1..65535 && localPort in 1..65535 && captchaMode in listOf("auto", "wv", "rjs") &&
            fingerprint in listOf("firefox", "chrome", "safari", "ios", "android", "random") &&
            clientIds.matches(Regex("[0-9]+(?:,[0-9]+){0,15}"))
    }

    override fun parseFromLink(link: String): Boolean {
        // wdtt://<server>:<dtls>:<wg>:<local>:<password>[:<hashes>] — the original WDTT form.
        if (!link.startsWith("wdtt://connect?", true)) {
            if (!link.startsWith("wdtt://", true)) return false
            val value = link.substring(7); val parts = value.substringBefore('#').split(':')
            if (parts.size !in 5..6) return false
            val dtls = parts[1].toIntOrNull() ?: return false
            return parseFromJson(jsonObjectOf("type" to "wdttplus", "legacy" to true, "peer" to Hosts.displayAddress(parts[0], dtls),
                "wg_port" to (parts[2].toIntOrNull() ?: return false), "local_port" to (parts[3].toIntOrNull() ?: return false),
                "password" to LinkCodec.decodeFully(parts[4]), "hashes" to if (parts.size == 6) LinkCodec.decodeFully(parts[5]) else "",
                "tag" to if (value.contains('#')) LinkCodec.decodeFully(value.substringAfter('#')) else ""))
        }
        // wdtt://connect?v=1&host&dtls&wg&local&password&hashes[&name][&max_workers]
        val url = LinkParser.parse("wdtt://connect?" + link.substring(15))
        if (!url.isValid) return false
        val q = url.query
        if (q.valueFully("v") != "1") return false
        val host = q.valueFully("host")
        val dtls = q.valueFully("dtls").toIntOrNull() ?: return false
        val wg = q.valueFully("wg").toIntOrNull() ?: return false
        val local = q.valueFully("local").toIntOrNull() ?: return false
        if (host.isBlank() || dtls !in 1..65535) return false
        val obj = jsonObjectOf("type" to "wdttplus", "peer" to Hosts.displayAddress(host, dtls), "wg_port" to wg, "local_port" to local,
            "password" to q.valueFully("password"), "hashes" to q.valueFully("hashes"),
            "tag" to if (q.has("name")) q.valueFully("name") else (url.fragment ?: ""))
        if (q.has("max_workers")) {
            val limit = q.valueFully("max_workers").toIntOrNull() ?: return false
            if (limit !in 9..108 || limit % 9 != 0) return false
            obj["max_workers"] = limit; obj["workers"] = limit
        }
        return parseFromJson(obj)
    }

    override fun exportToJson() = super.exportToJson().also {
        it["type"] = "wdttplus"
        for (key in listOf("mode", "raw_port", "turn_tcp", "obfs", "go_dns", "vk_anon_path")) it.remove(key)
        it["wg_port"] = wgPort; it["local_port"] = localPort
        it["tcp_only"] = tcpOnly; it["turn_sni"] = turnSni; it["rt_masque"] = rtMasque
        it["rt_masque_server_bootstrap"] = rtMasqueServerBootstrap
        it["fingerprint"] = fingerprint; it["client_ids"] = clientIds; it["vkcalls_preflight"] = vkCallsPreflight
        if (maxWorkers > 0) it["max_workers"] = maxWorkers
        if (legacy) it["legacy"] = true
    }

    /** The link WDTT Plus shares: wdtt://connect?v=1, as its TransferCodec builds it. */
    override fun exportToLink(): String {
        if (legacy) return "wdtt://$server:$serverPort:$wgPort:$localPort:$password:" + hashes.joinToString(",") +
            if (name.isNotBlank()) "#" + LinkCodec.percentEncodeAll(name) else ""
        val url = LinkBuilder("wdtt"); url.host = "connect"; url.plusAsEscape = true
        url.addQueryItem("v", "1"); url.addQueryItem("host", server); url.addQueryItem("dtls", serverPort.toString())
        url.addQueryItem("wg", wgPort.toString()); url.addQueryItem("local", localPort.toString())
        url.addQueryItem("password", password); url.addQueryItem("hashes", hashes.joinToString(","))
        if (name.isNotBlank()) url.addQueryItem("name", name)
        if (maxWorkers in 9..108) url.addQueryItem("max_workers", maxWorkers.toString())
        return url.build()
    }

    override fun privateConfig(port: Int, udpPort: Int, credentials: CarrierBridgeCredentials): JsonObject {
        require(server.isNotBlank() && serverPort in 1..65535)
        require(password.isNotEmpty() && password.none { it == '\n' || it == '\r' })
        require(deviceId.isNotEmpty() && deviceId.none { it == '\n' || it == '\r' })
        require(hashSource == "auto" || hashes.isNotEmpty()) { "Добавьте хеши VK в профиль или выберите автоматический источник" }
        require((hashSource == "auto" || hashes.size in 1..4) && workers in 9..108 && captchaMode in listOf("auto", "wv", "rjs"))
        // Flags as WDTT Plus's TunnelManager passes them; Go boolean flags only as -flag=value.
        val args = mutableListOf(
            "-peer", Hosts.displayAddress(server, serverPort), "-startup-config-stdin=true",
            "-n", workers.toString(), "-listen", Hosts.displayAddress("127.0.0.1", udpPort),
            "-transport-experiment", "auto", "-mode", "socks5",
            "-socks-listen", Hosts.displayAddress("127.0.0.1", port), "-socks-udp=true", "-socks-auth=true",
            "-fingerprint", fingerprint, "-client-ids", clientIds,
            "-vkcalls-preflight=$vkCallsPreflight", "-turn-stream-first=$tcpOnly", "-connection-udp-backoff=0", "-rt-masque=$rtMasque",
            "-device-id", deviceId, "-device-info", deviceInfo(),
            "-config-first-start=true",
        )
        if (turnSni.isNotBlank()) args += listOf("-turn-sni", turnSni)
        if (hashes.size > 1) args += "-hash-fallback=true"
        args += listOf("-captcha-mode", captchaMode)
        // "hashes" is not passed on: it lets the log redactor hide every hash on its own.
        return jsonObjectOf("args" to JsonArray.of(*args.toTypedArray()), "hashes" to JsonArray.of(*hashes.toTypedArray()),
            "masque_server_bootstrap" to rtMasqueServerBootstrap, "start" to jsonObjectOf(
            "vk_hashes" to hashes.joinToString(","), "connection_password" to password,
            "custom_vk_client_id" to "", "custom_vk_client_secret" to "",
            "socks_username" to credentials.username, "socks_password" to credentials.password))
    }

    companion object {
        /** Same external TURN SNI validation as WDTT Plus's normalizeRtTurnSni; blank disables it. */
        fun normalizeTurnSni(value: String): String? {
            val host = value.trim().lowercase(Locale.ROOT)
            if (host.isBlank()) return ""
            if (host.length > 253 || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return null
            val labels = host.split('.')
            if (labels.size < 2 || labels.all { it.toIntOrNull() != null }) return null
            return host.takeIf { labels.all { label ->
                label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            } }
        }
        /** A plain wdtt:// profile that 1.9.0 stored as WDTT Plus, back as the qWDTT profile it was before; null otherwise. */
        fun legacyToQwdtt(json: String): Qwdtt? {
            val obj = JsonInput.parseObjectOrNull(json) ?: return null
            if (obj.string("type") != "wdttplus" || !obj.bool("legacy")) return null
            val copy = obj.copy(); copy["type"] = "qwdtt"
            for (key in listOf("legacy", "wg_port", "local_port", "max_workers", "workers")) copy.remove(key)
            return Qwdtt().takeIf { it.parseFromJson(copy) }
        }

        /** normalizeTunnelWorkerCount of WDTT Plus: groups of 9, up to 108 or the profile's own limit. */
        fun normalizeWorkers(requested: Int, profileMaxWorkers: Int): Int {
            val configured = profileMaxWorkers / 9 * 9
            val maximum = configured.takeIf { it >= 9 }?.coerceAtMost(108) ?: 108
            val rounded = (requested.coerceIn(9,108) + 4) / 9 * 9
            return rounded.coerceIn(9, maximum)
        }

        /** buildDeviceInfoJson of WDTT Plus; the server shows it in its device list. */
        fun deviceInfo(): String {
            val locale = Locale.getDefault()
            val name = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifBlank { "Android device" }
            return org.json.JSONObject().put("name", name).put("manufacturer", Build.MANUFACTURER.orEmpty())
                .put("brand", Build.BRAND.orEmpty()).put("model", Build.MODEL.orEmpty())
                .put("android_version", Build.VERSION.RELEASE.orEmpty()).put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()).put("app_version", "qThroneMobile")
                .put("locale", locale.toLanguageTag()).put("country", locale.country.orEmpty())
                .put("time_zone", TimeZone.getDefault().id).toString()
        }
    }
}
