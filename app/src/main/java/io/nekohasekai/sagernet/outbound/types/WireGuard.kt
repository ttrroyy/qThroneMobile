package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.SecurityInfo
import app.qthrone.mobile.outbound.SecurityLevel
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.json.JsonArray
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.JsonValues
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkCodec
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser
import app.qthrone.mobile.outbound.link.ParsedLink

/** Peer (wireguard.h:6-25, wireguard.cpp:21-122): the single peer of a wireguard endpoint. */
class WireGuardPeer {
    @JvmField var address: String = ""
    @JvmField var port: Int = 0
    @JvmField var public_key: String = ""
    @JvmField var pre_shared_key: String = ""
    @JvmField var reserved: MutableList<Int> = ArrayList()

    /** Seconds, or an AmneziaWG 3.0 range such as "22-30". */
    @JvmField var persistent_keepalive: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** wireguard.cpp:21-53; values are FullyDecoded because PrettyDecoded keeps %2F/%2B/%2C and corrupts base64 keys. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        address = url.host
        port = url.port(51820)
        when {
            q.has("public_key") -> public_key = q.valueFully("public_key")
            q.has("publickey") -> public_key = q.valueFully("publickey")
            q.has("peer_public_key") -> public_key = q.valueFully("peer_public_key")
        }
        when {
            q.has("pre_shared_key") -> pre_shared_key = q.valueFully("pre_shared_key")
            q.has("preshared_key") -> pre_shared_key = q.valueFully("preshared_key")
            q.has("presharedkey") -> pre_shared_key = q.valueFully("presharedkey")
            q.has("psk") -> pre_shared_key = q.valueFully("psk")
        }
        if (q.has("reserved")) {
            val raw = q.valueFully("reserved")
            if (raw.isNotEmpty()) {
                for (item in QtStrings.splitSkipEmpty(raw.replace(',', '-'), "-")) {
                    val value = QtStrings.toIntOrNull(item) ?: continue
                    if (value in 0..255) reserved.add(value)
                }
            }
        }
        when {
            q.has("persistent_keepalive_interval") -> persistent_keepalive = q.valueFully("persistent_keepalive_interval")
            q.has("persistent_keepalive") -> persistent_keepalive = q.valueFully("persistent_keepalive")
            q.has("keepalive") -> persistent_keepalive = q.valueFully("keepalive")
        }
        return true
    }

    /** wireguard.cpp:55-69: the keepalive accepts a number or a range string. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("address")) address = obj.string("address")
        if (obj.contains("port")) port = obj.int("port")
        if (obj.contains("public_key")) public_key = obj.string("public_key")
        if (obj.contains("pre_shared_key")) pre_shared_key = obj.string("pre_shared_key")
        if (obj.contains("reserved")) reserved = obj.array("reserved").mapTo(ArrayList()) { JsonValues.toInt(it) }
        if (obj.contains("persistent_keepalive_interval")) {
            persistent_keepalive = if (obj.isString("persistent_keepalive_interval")) obj.string("persistent_keepalive_interval")
            else obj.int("persistent_keepalive_interval").toString()
        }
        return true
    }

    /** wireguard.cpp:71-84. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (public_key.isNotEmpty()) q.add("public_key" to public_key)
        if (pre_shared_key.isNotEmpty()) q.add("pre_shared_key" to pre_shared_key)
        if (reserved.isNotEmpty()) q.add("reserved" to reserved.joinToString("-"))
        if (persistent_keepalive.isNotEmpty()) q.add("persistent_keepalive_interval" to persistent_keepalive)
        return q
    }

    /** wireguard.cpp:86-95. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (address.isNotEmpty()) obj["address"] = address
        if (port > 0) obj["port"] = port
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (pre_shared_key.isNotEmpty()) obj["pre_shared_key"] = pre_shared_key
        if (reserved.isNotEmpty()) obj["reserved"] = reservedArray()
        writeKeepalive(obj)
        return obj
    }

    /** wireguard.cpp:97-107: ExportToJson plus the catch-all allowed_ips. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj["allowed_ips"] = JsonValues.stringArray(listOf("0.0.0.0/0", "::/0"))
        return obj
    }

    private fun reservedArray(): JsonArray {
        val arr = JsonArray()
        for (value in reserved) arr.add(value)
        return arr
    }

    // wireguard.cpp:110-122: a range must be a string and a plain interval a number; anything else makes the core
    // refuse to start, so a value that is neither is dropped.
    private fun writeKeepalive(obj: JsonObject) {
        if (persistent_keepalive.isEmpty()) return
        val seconds = QtStrings.toIntOrNull(persistent_keepalive)
        if (seconds != null) {
            if (seconds > 0) obj["persistent_keepalive_interval"] = seconds
            return
        }
        if (KEEPALIVE_RANGE.containsMatchIn(persistent_keepalive)) obj["persistent_keepalive_interval"] = persistent_keepalive
    }

    companion object {
        private val KEEPALIVE_RANGE = Regex("^\\d+-\\d+$")
    }
}

/**
 * wireguard / AmneziaWG (include/configs/outbounds/wireguard.h, src/configs/outbounds/wireguard.cpp): a sing-box
 * endpoint. The server address and port live in [peer]; the base `server`/`serverPort` are only what the last link
 * parse left there, exactly as on the desktop, so callers use [getAddress]/[getPort].
 */
class WireGuard : Outbound("wireguard") {
    @JvmField var private_key: String = ""
    @JvmField var peer: WireGuardPeer = WireGuardPeer()
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1420
    @JvmField var system: Boolean = false
    @JvmField var worker_count: Int = 0
    @JvmField var udp_timeout: String = ""
    /** The INI `DNS =` resolvers; the official clients resolve through them, and some servers only forward what they resolved. */
    @JvmField var dns: MutableList<String> = ArrayList()

    /** AmneziaWG: jc/jmin/jmax and s1-s4 are integers; h1-h4 (magic headers) and i1-i5 (signature packets) are strings. */
    @JvmField var enable_amnezia: Boolean = false
    @JvmField var jc: Int = 0
    @JvmField var jmin: Int = 0
    @JvmField var jmax: Int = 0
    @JvmField var s1: Int = 0
    @JvmField var s2: Int = 0
    @JvmField var s3: Int = 0
    @JvmField var s4: Int = 0
    @JvmField var h1: String = ""
    @JvmField var h2: String = ""
    @JvmField var h3: String = ""
    @JvmField var h4: String = ""
    @JvmField var i1: String = ""
    @JvmField var i2: String = ""
    @JvmField var i3: String = ""
    @JvmField var i4: String = ""
    @JvmField var i5: String = ""

    /** AmneziaWG 3.0: header_protection_key is a base64 32-byte key; the rest are ranges ("30" or "22-30"). */
    @JvmField var header_protection_key: String = ""
    @JvmField var content_padding_addition: String = ""
    @JvmField var rekey_after_time: String = ""
    @JvmField var rekey_timeout: String = ""
    @JvmField var reject_after_time: String = ""
    @JvmField var keepalive_timeout: String = ""
    @JvmField var max_handshake_attempts: String = ""

    /** AmneziaWG 3.1 */
    @JvmField var random_trailers: Boolean = false
    @JvmField var disable_cookies: Boolean = false

    /** Obfuscator in front of the server: "wg-obfuscator" (ClusterM, Phobos) or "wgobfs" (xt_wgobfs); empty is plain WireGuard. */
    @JvmField var obfs_type: String = ""
    @JvmField var obfs_key: String = ""
    /** wg-obfuscator: NONE, STUN or MEDIA (an RTP video stream). */
    @JvmField var obfs_masking: String = ""
    /** -1 keeps the obfuscator's default of 4. */
    @JvmField var obfs_max_dummy: Int = -1
    @JvmField var obfs_obfuscate_bytes: Int = 0
    @JvmField var obfs_media_pt: Int = 0
    /** Decimal, empty when the SSRC is random. */
    @JvmField var obfs_media_ssrc: String = ""
    @JvmField var obfs_media_clock: Int = 0

    /**
     * wireguard.cpp:124-245: wg-quick / AmneziaWG INI text, or a wg:// / wireguard:// / awg:// URL. The scheme is not
     * checked, and awg:// alone does not enable AmneziaWG; only its parameters do, as on the desktop.
     */
    override fun parseFromLink(link: String): Boolean {
        if (link.contains("[Interface]") && link.contains("[Peer]")) return parseIni(link)

        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)

        when {
            q.has("private_key") -> private_key = q.valueFully("private_key")
            q.has("privatekey") -> private_key = q.valueFully("privatekey")
            url.userName.isNotEmpty() -> private_key = url.userName
        }

        peer.parseFromLink(url)
        server = peer.address
        serverPort = peer.port

        when {
            q.has("local_address") -> address = QtStrings.splitSkipEmpty(q.valueFully("local_address"), "-")
            q.has("address") -> address = QtStrings.splitSkipEmpty(q.valueFully("address").replace(" ", ""), ",")
            q.has("ip") -> address = QtStrings.splitSkipEmpty(q.valueFully("ip").replace(" ", ""), ",")
        }

        if (q.has("mtu")) mtu = QtStrings.toInt(q.valueFully("mtu"))
        if (q.has("use_system_interface")) system = q.valueFully("use_system_interface") == "true"
        if (q.has("workers")) worker_count = QtStrings.toInt(q.valueFully("workers"))
        if (q.has("udp_timeout")) udp_timeout = q.valueFully("udp_timeout")
        if (q.has("dns")) dns = QtStrings.splitSkipEmpty(q.valueFully("dns").replace(" ", ""), ",")

        if (q.valueFully("enable_amnezia") == "true") enable_amnezia = true
        fun intKey(key: String, set: (Int) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(QtStrings.toInt(q.valueFully(key)))
            enable_amnezia = true
            return true
        }
        fun strKey(key: String, set: (String) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(q.valueFully(key))
            enable_amnezia = true
            return true
        }
        intKey("jc") { jc = it }
        intKey("jmin") { jmin = it }
        intKey("jmax") { jmax = it }
        intKey("s1") { s1 = it }
        intKey("s2") { s2 = it }
        intKey("s3") { s3 = it }
        intKey("s4") { s4 = it }
        strKey("h1") { h1 = it }
        strKey("h2") { h2 = it }
        strKey("h3") { h3 = it }
        strKey("h4") { h4 = it }
        strKey("i1") { i1 = it }
        strKey("i2") { i2 = it }
        strKey("i3") { i3 = it }
        strKey("i4") { i4 = it }
        strKey("i5") { i5 = it }
        if (!strKey("header_protection_key") { header_protection_key = it }) strKey("headerprotectionkey") { header_protection_key = it }
        if (!strKey("content_padding_addition") { content_padding_addition = it }) strKey("contentpaddingaddition") { content_padding_addition = it }
        strKey("rekey_after_time") { rekey_after_time = it }
        strKey("rekey_timeout") { rekey_timeout = it }
        strKey("reject_after_time") { reject_after_time = it }
        strKey("keepalive_timeout") { keepalive_timeout = it }
        strKey("max_handshake_attempts") { max_handshake_attempts = it }
        strKey("random_trailers") { random_trailers = parseAmneziaBool(it) }
        strKey("disable_cookies") { disable_cookies = parseAmneziaBool(it) }

        if (q.has("obfs")) {
            obfs_type = q.valueFully("obfs")
            obfs_key = q.valueFully("obfs_key")
            obfs_masking = q.valueFully("obfs_masking").uppercase()
            if (q.has("obfs_max_dummy")) obfs_max_dummy = QtStrings.toInt(q.valueFully("obfs_max_dummy"))
            obfs_obfuscate_bytes = QtStrings.toInt(q.valueFully("obfs_bytes"))
            obfs_media_pt = QtStrings.toInt(q.valueFully("obfs_media_pt"))
            obfs_media_ssrc = ssrcText(q.valueFully("obfs_media_ssrc"))
            obfs_media_clock = QtStrings.toInt(q.valueFully("obfs_media_clock"))
        }

        fixAddress()
        return !(private_key.isEmpty() || peer.public_key.isEmpty() || server.isEmpty())
    }

    // wireguard.cpp:125-184: keys are matched lower-cased; no base parse, so the name and dial fields stay untouched.
    private fun parseIni(text: String): Boolean {
        var section = ""
        var target = ""
        for (line in QtStrings.split(text, "\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                continue
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim().lowercase()
            val value = trimmed.substring(eqIdx + 1).trim()
            // Phobos links write "none" for every field the panel left unset.
            if (value.equals("none", ignoreCase = true)) continue
            // The [instance] section of a Phobos .conf is the wg-obfuscator client in front of the server.
            if (section == "instance") {
                obfs_type = "wg-obfuscator"
                when (key) {
                    "key" -> obfs_key = value
                    "target" -> target = value
                    "masking" -> obfs_masking = value.uppercase().takeIf { it != "AUTO" }.orEmpty()
                    "max-dummy" -> obfs_max_dummy = QtStrings.toInt(value)
                    "obfuscate-bytes" -> obfs_obfuscate_bytes = QtStrings.toInt(value)
                    "media-pt" -> obfs_media_pt = QtStrings.toInt(value)
                    "media-ssrc" -> obfs_media_ssrc = ssrcText(value)
                    "media-clock" -> obfs_media_clock = QtStrings.toInt(value)
                }
                continue
            }
            when (key) {
                "privatekey" -> private_key = value
                // WARP and Amnezia write IPv4 and IPv6 as separate Address lines.
                "address" -> address = (address + QtStrings.splitSkipEmpty(value.replace(" ", ""), ",")).toMutableList()
                "mtu" -> mtu = QtStrings.toInt(value)
                "dns" -> dns = (dns + QtStrings.splitSkipEmpty(value.replace(" ", ""), ",")).toMutableList()
                "publickey" -> peer.public_key = value
                "presharedkey", "preshared_key", "psk" -> peer.pre_shared_key = value
                "persistentkeepalive", "persistent_keepalive" -> peer.persistent_keepalive = value
                "endpoint" -> {
                    val lastColon = value.lastIndexOf(':')
                    if (lastColon != -1) {
                        var host = value.substring(0, lastColon).trim()
                        if (host.startsWith('[') && host.endsWith(']')) {
                            host = if (host.length >= 2) host.substring(1, host.length - 1) else ""
                        }
                        val port = QtStrings.toInt(value.substring(lastColon + 1).trim())
                        peer.address = host
                        peer.port = if (port > 0) port else 51820
                        server = peer.address
                        serverPort = peer.port
                    }
                }
                "jc" -> { jc = QtStrings.toInt(value); enable_amnezia = true }
                "jmin" -> { jmin = QtStrings.toInt(value); enable_amnezia = true }
                "jmax" -> { jmax = QtStrings.toInt(value); enable_amnezia = true }
                "s1" -> { s1 = QtStrings.toInt(value); enable_amnezia = true }
                "s2" -> { s2 = QtStrings.toInt(value); enable_amnezia = true }
                "s3" -> { s3 = QtStrings.toInt(value); enable_amnezia = true }
                "s4" -> { s4 = QtStrings.toInt(value); enable_amnezia = true }
                "h1" -> { h1 = value; enable_amnezia = true }
                "h2" -> { h2 = value; enable_amnezia = true }
                "h3" -> { h3 = value; enable_amnezia = true }
                "h4" -> { h4 = value; enable_amnezia = true }
                "i1" -> { i1 = value; enable_amnezia = true }
                "i2" -> { i2 = value; enable_amnezia = true }
                "i3" -> { i3 = value; enable_amnezia = true }
                "i4" -> { i4 = value; enable_amnezia = true }
                "i5" -> { i5 = value; enable_amnezia = true }
                "headerprotectionkey", "header_protection_key" -> { header_protection_key = value; enable_amnezia = true }
                "contentpaddingaddition", "content_padding_addition" -> { content_padding_addition = value; enable_amnezia = true }
                "rekeyaftertime", "rekey_after_time" -> { rekey_after_time = value; enable_amnezia = true }
                "rekeytimeout", "rekey_timeout" -> { rekey_timeout = value; enable_amnezia = true }
                "rejectaftertime", "reject_after_time" -> { reject_after_time = value; enable_amnezia = true }
                "keepalivetimeout", "keepalive_timeout" -> { keepalive_timeout = value; enable_amnezia = true }
                "maxhandshakeattempts", "max_handshake_attempts" -> { max_handshake_attempts = value; enable_amnezia = true }
                "randomtrailers", "random_trailers" -> { random_trailers = parseAmneziaBool(value); enable_amnezia = true }
                "disablecookies", "disable_cookies" -> { disable_cookies = parseAmneziaBool(value); enable_amnezia = true }
            }
        }
        // The peer's Endpoint points at the local obfuscator; the server is its target.
        val colon = target.lastIndexOf(':')
        if (colon > 0) {
            var host = target.substring(0, colon).trim()
            if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
            peer.address = host
            peer.port = QtStrings.toInt(target.substring(colon + 1))
            server = peer.address
            serverPort = peer.port
        }
        // AmneziaVPN runs an AmneziaWG config without an MTU line at 1376: its padding does not fit WireGuard's 1420.
        if (enable_amnezia && !Regex("""(?im)^\s*MTU\s*=""").containsMatchIn(text)) mtu = 1376
        fixAddress()
        return private_key.isNotEmpty() && peer.public_key.isNotEmpty() && (obfs_type.isEmpty() || obfs_key.isNotEmpty())
    }

    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy=ClashProxy(node)
        if(proxy.type!="wireguard") return false
        baseParseFromClash(proxy)
        private_key=proxy.string("private-key")
        peer=WireGuardPeer().apply {
            address=server;port=serverPort
            public_key=proxy.string("public-key")
            pre_shared_key=proxy.string("pre-shared-key")
            persistent_keepalive=proxy.string("persistent-keepalive")
            if(node.isArray("reserved")) reserved=node.array("reserved").map { JsonValues.toInt(it) }.toMutableList()
        }
        if(peer.reserved.any { it !in 0..255 }) return false
        address=(proxy.strings("ip")+proxy.strings("ipv6")).filter(String::isNotBlank).toMutableList()
        if(proxy.has("mtu")) mtu=proxy.int("mtu")
        val amnezia=JsonObject()
        val options=proxy.obj("amnezia-wg-option")
        for((key,value) in options.node) {
            val field=key.replace('-','_')
            amnezia[field]=when(field) {
                "h1","h2","h3","h4" -> options.string(key)
                "random_trailers","disable_cookies" -> options.bool(key)
                else -> value
            }
        }
        amneziaFromJson(amnezia)
        fixAddress()
        return private_key.isNotEmpty() && peer.public_key.isNotEmpty() && peer.address.isNotEmpty() &&
            peer.port in 1..65535 && address.isNotEmpty()
    }

    /** wireguard.cpp:247-260. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "wireguard") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peers") && obj.isArray("peers")) {
            val peers = obj.array("peers")
            if (peers.isNotEmpty()) peer.parseFromJson(peers[0] as? JsonObject ?: JsonObject())
        }
        if (obj.contains("address")) address = obj.array("address").strings()
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("worker_count")) worker_count = obj.int("worker_count")
        if (obj.contains("udp_timeout")) udp_timeout = obj.string("udp_timeout")
        if (obj.contains("dns")) dns = obj.array("dns").strings()
        if (obj.contains("amnezia_wg")) amneziaFromJson(obj.obj("amnezia_wg"))
        if (obj.contains("obfs")) obfsFromJson(obj.obj("obfs"))
        fixAddress()
        return true
    }

    /** wireguard.cpp:262-312: `wg://<peer address>:<peer port>?...`, dial items before the peer items. */
    override fun exportToLink(): String {
        // wg-obfuscator profiles travel as Phobos links, which Phobos clients import too.
        if (obfsType() == "wg-obfuscator") {
            return "phobos://" + Base64Strict.encode(exportPhobosConf().toByteArray(Charsets.UTF_8), urlSafe = true, padding = false) +
                "#" + (if (name.isEmpty()) "none" else LinkCodec.percentEncodeAll(name))
        }
        val url = LinkBuilder("wg")
        url.host = peer.address
        url.port = peer.port
        if (name.isNotEmpty()) url.fragment = name

        if (private_key.isNotEmpty()) url.addQueryItem("private_key", private_key)
        if (address.isNotEmpty()) url.addQueryItem("local_address", address.joinToString("-"))
        if (mtu > 0 && mtu != 1420) url.addQueryItem("mtu", mtu.toString())
        if (system) url.addQueryItem("use_system_interface", "true")
        if (worker_count > 0) url.addQueryItem("workers", worker_count.toString())
        if (udp_timeout.isNotEmpty()) url.addQueryItem("udp_timeout", udp_timeout)
        if (dns.isNotEmpty()) url.addQueryItem("dns", dns.joinToString(","))

        if (enable_amnezia) {
            url.addQueryItem("enable_amnezia", "true")
            if (jc > 0) url.addQueryItem("jc", jc.toString())
            if (jmin > 0) url.addQueryItem("jmin", jmin.toString())
            if (jmax > 0) url.addQueryItem("jmax", jmax.toString())
            if (s1 > 0) url.addQueryItem("s1", s1.toString())
            if (s2 > 0) url.addQueryItem("s2", s2.toString())
            if (s3 > 0) url.addQueryItem("s3", s3.toString())
            if (s4 > 0) url.addQueryItem("s4", s4.toString())
            if (h1.isNotEmpty()) url.addQueryItem("h1", h1)
            if (h2.isNotEmpty()) url.addQueryItem("h2", h2)
            if (h3.isNotEmpty()) url.addQueryItem("h3", h3)
            if (h4.isNotEmpty()) url.addQueryItem("h4", h4)
            if (i1.isNotEmpty()) url.addQueryItem("i1", i1)
            if (i2.isNotEmpty()) url.addQueryItem("i2", i2)
            if (i3.isNotEmpty()) url.addQueryItem("i3", i3)
            if (i4.isNotEmpty()) url.addQueryItem("i4", i4)
            if (i5.isNotEmpty()) url.addQueryItem("i5", i5)
            if (header_protection_key.isNotEmpty()) url.addQueryItem("header_protection_key", header_protection_key)
            if (content_padding_addition.isNotEmpty()) url.addQueryItem("content_padding_addition", content_padding_addition)
            if (rekey_after_time.isNotEmpty()) url.addQueryItem("rekey_after_time", rekey_after_time)
            if (rekey_timeout.isNotEmpty()) url.addQueryItem("rekey_timeout", rekey_timeout)
            if (reject_after_time.isNotEmpty()) url.addQueryItem("reject_after_time", reject_after_time)
            if (keepalive_timeout.isNotEmpty()) url.addQueryItem("keepalive_timeout", keepalive_timeout)
            if (max_handshake_attempts.isNotEmpty()) url.addQueryItem("max_handshake_attempts", max_handshake_attempts)
            if (random_trailers) url.addQueryItem("random_trailers", "true")
            if (disable_cookies) url.addQueryItem("disable_cookies", "true")
        }

        if (obfsType().isNotEmpty()) {
            url.addQueryItem("obfs", obfs_type)
            url.addQueryItem("obfs_key", obfs_key)
        }

        url.addQueryItems(baseLinkQuery())
        url.addQueryItems(peer.exportToLink())
        return url.build()
    }

    /** wireguard.cpp:314-335: no server/server_port; the peer carries them. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.exportToJson())
        writeFields(obj)
        if (dns.isNotEmpty()) obj["dns"] = JsonValues.stringArray(dns)
        val peerObj = peer.exportToJson()
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return obj
    }

    /** wireguard.cpp:337-357. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))
        writeFields(obj)
        if (obfsType().isNotEmpty()) {
            if (enable_amnezia) return BuildResult(JsonObject(), "WireGuard obfuscation cannot be combined with AmneziaWG")
            if (obfs_key.isEmpty()) return BuildResult(JsonObject(), "WireGuard obfuscation needs a key")
        }
        val peerObj = peer.build(ctx)
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject) {
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (system) obj["system"] = system
        if (worker_count > 0) obj["worker_count"] = worker_count
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        val amnezia = amneziaToJson()
        if (amnezia.isNotEmpty()) obj["amnezia_wg"] = amnezia
        val obfs = obfsToJson()
        if (obfs.isNotEmpty()) obj["obfs"] = obfs
    }

    /** The first IPv4 `DNS =` resolver, or "" (shortcut: IPv6-only DNS lines are ignored, add them if a server needs it). */
    fun dnsServer(): String = dns.firstOrNull { Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it) }.orEmpty()

    /** The obfuscator in use; the editor's "off" and anything unknown are plain WireGuard. */
    fun obfsType(): String = obfs_type.takeIf { it == "wg-obfuscator" || it == "wgobfs" }.orEmpty()

    /** The core's "obfs" object; empty without an obfuscator. */
    fun obfsToJson(): JsonObject {
        val obj = JsonObject()
        if (obfsType().isEmpty()) return obj
        obj["type"] = obfs_type
        obj["key"] = obfs_key
        if (obfs_type != "wg-obfuscator") return obj
        if (obfs_masking.isNotEmpty() && obfs_masking != "NONE") obj["masking"] = obfs_masking.lowercase()
        if (obfs_max_dummy >= 0) obj["max_dummy"] = obfs_max_dummy
        if (obfs_obfuscate_bytes > 0) obj["obfuscate_bytes"] = obfs_obfuscate_bytes
        if (obfs_media_pt > 0) obj["media_pt"] = obfs_media_pt
        if (parseSsrc(obfs_media_ssrc) > 0) obj["media_ssrc"] = parseSsrc(obfs_media_ssrc)
        if (obfs_media_clock > 0) obj["media_clock"] = obfs_media_clock
        return obj
    }

    private fun obfsFromJson(obj: JsonObject) {
        obfs_type = obj.string("type")
        obfs_key = obj.string("key")
        obfs_masking = obj.string("masking").uppercase()
        obfs_max_dummy = if (obj.contains("max_dummy")) obj.int("max_dummy") else -1
        obfs_obfuscate_bytes = obj.int("obfuscate_bytes")
        obfs_media_pt = obj.int("media_pt")
        obfs_media_ssrc = ssrcText(JsonValues.toInteger(obj["media_ssrc"]).toString())
        obfs_media_clock = obj.int("media_clock")
    }

    /** The Phobos client .conf: [Interface], [Peer] and the obfuscator's [instance]. */
    fun exportPhobosConf(): String {
        val conf = arrayListOf("[Interface]", "PrivateKey = $private_key", "Address = " + address.joinToString(", "))
        if (mtu > 0) conf += "MTU = $mtu"
        conf += listOf("", "[Peer]", "PublicKey = " + peer.public_key)
        if (peer.pre_shared_key.isNotEmpty()) conf += "PresharedKey = " + peer.pre_shared_key
        conf += "AllowedIPs = 0.0.0.0/0, ::/0"
        if (peer.persistent_keepalive.isNotEmpty()) conf += "PersistentKeepalive = " + peer.persistent_keepalive
        conf += listOf("Endpoint = 127.0.0.1:13255", "", "[instance]", "source-if = 127.0.0.1", "source-lport = 13255",
            "target = " + Hosts.displayAddress(peer.address, peer.port), "key = $obfs_key", "masking = " + obfs_masking.ifEmpty { "NONE" })
        if (obfs_max_dummy >= 0) conf += "max-dummy = $obfs_max_dummy"
        if (obfs_obfuscate_bytes > 0) conf += "obfuscate-bytes = $obfs_obfuscate_bytes"
        if (obfs_media_pt > 0) conf += "media-pt = $obfs_media_pt"
        if (parseSsrc(obfs_media_ssrc) > 0) conf += "media-ssrc = " + parseSsrc(obfs_media_ssrc)
        if (obfs_media_clock > 0) conf += "media-clock = $obfs_media_clock"
        return conf.joinToString("\n") + "\n"
    }

    /** wireguard.cpp:359-373: the peer holds the address the profile list and the DNS resolver work with. */
    override fun setAddress(newAddr: String) {
        peer.address = newAddr
    }

    override fun getAddress(): String = peer.address

    override fun setPort(newPort: Int) {
        peer.port = newPort
    }

    override fun getPort(): String = peer.port.toString()

    /** wireguard.cpp:375-377. */
    override fun displayAddress(): String = Hosts.displayAddress(peer.address, peer.port)

    /** wireguard.cpp:379-381. */
    override fun displayType(): String = "WireGuard"

    /** wireguard.cpp:383-385. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", when {
        enable_amnezia -> "AmneziaWG"
        obfsType() == "wgobfs" -> "wgobfs"
        obfsType().isEmpty() -> ""
        obfs_masking.isEmpty() || obfs_masking == "NONE" -> "wg-obfuscator"
        else -> "wg-obfuscator $obfs_masking"
    }, SecurityLevel.Secure)

    /** wireguard.cpp:387-389. */
    override fun isEndpoint(): Boolean = true

    /** wireguard.cpp:391-420. */
    private fun amneziaToJson(): JsonObject {
        val obj = JsonObject()
        if (!enable_amnezia) return obj
        if (jc > 0) obj["jc"] = jc
        if (jmin > 0) obj["jmin"] = jmin
        if (jmax > 0) obj["jmax"] = jmax
        if (s1 > 0) obj["s1"] = s1
        if (s2 > 0) obj["s2"] = s2
        if (s3 > 0) obj["s3"] = s3
        if (s4 > 0) obj["s4"] = s4
        if (h1.isNotEmpty()) obj["h1"] = h1
        if (h2.isNotEmpty()) obj["h2"] = h2
        if (h3.isNotEmpty()) obj["h3"] = h3
        if (h4.isNotEmpty()) obj["h4"] = h4
        if (i1.isNotEmpty()) obj["i1"] = i1
        if (i2.isNotEmpty()) obj["i2"] = i2
        if (i3.isNotEmpty()) obj["i3"] = i3
        if (i4.isNotEmpty()) obj["i4"] = i4
        if (i5.isNotEmpty()) obj["i5"] = i5
        if (header_protection_key.isNotEmpty()) obj["header_protection_key"] = header_protection_key
        if (content_padding_addition.isNotEmpty()) obj["content_padding_addition"] = content_padding_addition
        if (rekey_after_time.isNotEmpty()) obj["rekey_after_time"] = rekey_after_time
        if (rekey_timeout.isNotEmpty()) obj["rekey_timeout"] = rekey_timeout
        if (reject_after_time.isNotEmpty()) obj["reject_after_time"] = reject_after_time
        if (keepalive_timeout.isNotEmpty()) obj["keepalive_timeout"] = keepalive_timeout
        if (max_handshake_attempts.isNotEmpty()) obj["max_handshake_attempts"] = max_handshake_attempts
        if (random_trailers) obj["random_trailers"] = true
        if (disable_cookies) obj["disable_cookies"] = true
        return obj
    }

    /** wireguard.cpp:422-450: any non-empty block switches AmneziaWG on. */
    private fun amneziaFromJson(obj: JsonObject) {
        if (obj.isEmpty()) return
        enable_amnezia = true
        if (obj.contains("jc")) jc = obj.int("jc")
        if (obj.contains("jmin")) jmin = obj.int("jmin")
        if (obj.contains("jmax")) jmax = obj.int("jmax")
        if (obj.contains("s1")) s1 = obj.int("s1")
        if (obj.contains("s2")) s2 = obj.int("s2")
        if (obj.contains("s3")) s3 = obj.int("s3")
        if (obj.contains("s4")) s4 = obj.int("s4")
        if (obj.contains("h1")) h1 = obj.string("h1")
        if (obj.contains("h2")) h2 = obj.string("h2")
        if (obj.contains("h3")) h3 = obj.string("h3")
        if (obj.contains("h4")) h4 = obj.string("h4")
        if (obj.contains("i1")) i1 = obj.string("i1")
        if (obj.contains("i2")) i2 = obj.string("i2")
        if (obj.contains("i3")) i3 = obj.string("i3")
        if (obj.contains("i4")) i4 = obj.string("i4")
        if (obj.contains("i5")) i5 = obj.string("i5")
        if (obj.contains("header_protection_key")) header_protection_key = obj.string("header_protection_key")
        if (obj.contains("content_padding_addition")) content_padding_addition = amneziaRange(obj["content_padding_addition"])
        if (obj.contains("rekey_after_time")) rekey_after_time = amneziaRange(obj["rekey_after_time"])
        if (obj.contains("rekey_timeout")) rekey_timeout = amneziaRange(obj["rekey_timeout"])
        if (obj.contains("reject_after_time")) reject_after_time = amneziaRange(obj["reject_after_time"])
        if (obj.contains("keepalive_timeout")) keepalive_timeout = amneziaRange(obj["keepalive_timeout"])
        if (obj.contains("max_handshake_attempts")) max_handshake_attempts = amneziaRange(obj["max_handshake_attempts"])
        if (obj.contains("random_trailers")) random_trailers = obj.bool("random_trailers")
        if (obj.contains("disable_cookies")) disable_cookies = obj.bool("disable_cookies")
    }

    /** wireguard.cpp:453-455: ranges are written as strings, but sing-box also accepts a bare number. */
    private fun amneziaRange(value: Any?): String = value as? String ?: JsonValues.toInt(value).toString()

    /** wireguard.cpp:457-470: bare IPs get their host prefix length (an empty item becomes "/32", as on the desktop). */
    private fun fixAddress() {
        val fixed = ArrayList<String>(address.size)
        for (addr in address) {
            var trimmed = addr.trim()
            if (!trimmed.contains("/")) trimmed += if (trimmed.contains(":")) "/128" else "/32"
            fixed.add(trimmed)
        }
        address = fixed
    }

    companion object {
        /** wireguard.cpp:16-19. */
        @JvmStatic
        fun parseAmneziaBool(value: String): Boolean {
            val trimmed = value.trim().lowercase()
            return trimmed == "true" || trimmed == "1" || trimmed == "on" || trimmed == "yes"
        }

        /** An RTP SSRC as wg-obfuscator reads it: decimal or 0x hex, 32 bits. */
        @JvmStatic
        fun parseSsrc(value: String): Long {
            val text = value.trim().lowercase()
            val parsed = if (text.startsWith("0x")) text.substring(2).toLongOrNull(16) else text.toLongOrNull()
            return parsed?.takeIf { it in 0..0xFFFFFFFFL } ?: 0
        }

        @JvmStatic
        fun ssrcText(value: String): String = parseSsrc(value).takeIf { it > 0 }?.toString().orEmpty()
    }
}
