package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.SecurityLevel
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues
import app.rawline.mobile.outbound.link.Hosts

/** Outbound.h:17-20: ignore the pushed resolvers, use them for the names they claim, or also for every remote query. */
const val TUNNEL_DNS_NONE = "none"
const val TUNNEL_DNS_PREFER = "prefer"
const val TUNNEL_DNS_STRICT = "strict"

/**
 * The shared toolkit of src/configs/sub/vpnFileImport.cpp (its anonymous namespace, `vpnfi*`): the .ovpn tokenizer
 * and directive reader, duration/prefix/fingerprint normalisers, and the QHostAddress::parseSubnet port. Used by
 * [OpenVpn.parseOvpnConfig] and [OpenConnect.parseOpenConnectProfile].
 */
internal object VpnFileImport {
    const val OVPN_DEFAULT_PORT = 1194

    class Directive(
        @JvmField val args: List<String>,
        @JvmField val inlineTag: String = "",
        @JvmField val inlineBody: List<String> = emptyList(),
    )

    class Remote {
        @JvmField var host: String = ""
        @JvmField var port: Int = 0
        @JvmField var network: String = ""
    }

    private val INTEGER = Regex("[+-]?[0-9]+")
    private val UNSIGNED = Regex("\\+?[0-9]+")
    private val DURATION = Regex("^(?:\\d+(?:\\.\\d+)?(?:ns|us|ms|s|m|h|d))+$")
    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

    /** QChar::isSpace(): ASCII whitespace, NEL, NBSP and the Unicode separator categories. */
    fun isSpace(c: Char): Boolean {
        if (c == ' ' || c in '\t'..'\r' || c == '\u0085' || c == '\u00a0') return true
        val type = Character.getType(c)
        return type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /** QString::trimmed(). */
    fun trimmed(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /** QString::simplified(): trimmed, inner whitespace runs collapsed to one space. */
    private fun simplified(s: String): String {
        val sb = StringBuilder()
        var pendingSpace = false
        for (c in trimmed(s)) {
            if (isSpace(c)) {
                pendingSpace = true
                continue
            }
            if (pendingSpace) sb.append(' ')
            pendingSpace = false
            sb.append(c)
        }
        return sb.toString()
    }

    /** QString::toInt() with its ok flag: null when the text is not an integer. */
    fun toIntOk(s: String): Int? {
        val t = s.trim()
        if (!INTEGER.matches(t)) return null
        return t.toIntOrNull()
    }

    private fun toUInt(s: String): Long? {
        val t = s.trim()
        if (!UNSIGNED.matches(t)) return null
        val v = t.toLongOrNull() ?: return null
        return if (v > 0xFFFFFFFFL) null else v
    }

    /** sing-box listables accept a bare string as well as an array of lines (openvpn.cpp:14-18, openconnect.cpp:15-19). */
    fun listable(obj: JsonObject, key: String): MutableList<String> =
        if (obj.isString(key)) QtStrings.splitSkipEmpty(obj.string(key), "\n") else obj.array(key).strings()

    /** vpnfiTokenize (vpnFileImport.cpp:39-81): parse_line treats `#`/`;` as a comment only where a parameter would start. */
    fun tokenize(line: String): MutableList<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quote != null) {
                if (ch == quote) {
                    quote = null
                    i++
                    continue
                }
                if (ch == '\\' && quote == '"' && i + 1 < line.length && (line[i + 1] == '"' || line[i + 1] == '\\')) {
                    token.append(line[++i])
                    i++
                    continue
                }
                token.append(ch)
                i++
                continue
            }
            if (isSpace(ch)) {
                if (inToken) {
                    tokens.add(token.toString())
                    token.setLength(0)
                    inToken = false
                }
                i++
                continue
            }
            if (!inToken && (ch == '#' || ch == ';')) break
            inToken = true
            if (ch == '"' || ch == '\'') {
                quote = ch
                i++
                continue
            }
            if (ch == '\\' && i + 1 < line.length) {
                token.append(line[++i])
                i++
                continue
            }
            token.append(ch)
            i++
        }
        if (inToken) tokens.add(token.toString())
        return tokens
    }

    /** vpnfiReadOvpnDirectives (vpnFileImport.cpp:83-122): inline `<tag>` blocks and backslash continuations. */
    fun readOvpnDirectives(body: String): List<Directive> {
        val result = ArrayList<Directive>()
        val lines = body.split('\n')
        var pending = ""
        var i = 0
        while (i < lines.size) {
            val trimmedLine = trimmed(lines[i].replace("\r", ""))
            if (pending.isEmpty() && trimmedLine.length > 2 && trimmedLine.startsWith('<') && trimmedLine.endsWith('>') &&
                !trimmedLine.startsWith("</")
            ) {
                val tag = trimmed(trimmedLine.substring(1, trimmedLine.length - 1))
                val closing = "</$tag>"
                val inlineBody = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val inner = trimmed(lines[i].replace("\r", ""))
                    if (inner == closing) break
                    inlineBody.add(inner)
                    i++
                }
                while (inlineBody.isNotEmpty() && inlineBody[inlineBody.size - 1].isEmpty()) inlineBody.removeAt(inlineBody.size - 1)
                while (inlineBody.isNotEmpty() && inlineBody[0].isEmpty()) inlineBody.removeAt(0)
                result.add(Directive(emptyList(), tag, inlineBody))
                i++
                continue
            }
            if (trimmedLine.endsWith('\\') && !trimmedLine.endsWith("\\\\")) {
                pending += trimmedLine.dropLast(1)
                pending += ' '
                i++
                continue
            }
            val full = pending + trimmedLine
            pending = ""
            val args = tokenize(full)
            if (args.isNotEmpty()) result.add(Directive(args))
            i++
        }
        if (pending.isNotEmpty()) {
            val args = tokenize(pending)
            if (args.isNotEmpty()) result.add(Directive(args))
        }
        return result
    }

    /** IsValidDuration (generate.cpp:2297-2300). */
    fun isValidDuration(text: String): Boolean = DURATION.containsMatchIn(text)

    /** vpnfiDuration (vpnFileImport.cpp:125-131): sing-box wants a duration string where `.ovpn` writes bare seconds. */
    fun duration(value: String): String {
        val t = value.trim()
        val seconds = if (INTEGER.matches(t)) t.toLongOrNull() else null
        if (seconds != null) return if (seconds > 0) "${seconds}s" else ""
        return if (isValidDuration(value)) value else ""
    }

    /** QHostAddress(text) for a string containing ':' (qhostaddress.cpp parse): the 16 bytes and the scope id. */
    private fun parseHostAddressV6(text: String): Pair<ByteArray, String>? {
        val a = simplified(text)
        if (a.isEmpty()) return null
        val pct = a.lastIndexOf('%')
        val scope = if (pct >= 0) a.substring(pct + 1) else ""
        val bytes = Hosts.parseIpv6(if (pct >= 0) a.substring(0, pct) else a) ?: return null
        return Pair(bytes, scope)
    }

    /** QNetmask::setAddress: the prefix length of an IP-style netmask, null unless it is 1s followed by 0s. */
    private fun netmaskPrefixLength(text: String): Int? {
        val a = simplified(text)
        val bytes: ByteArray = if (a.contains(':')) {
            parseHostAddressV6(a)?.first ?: return null
        } else {
            val v4 = Hosts.parseIpv4(a) ?: return null
            byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
        }
        var netmask = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 255) {
                netmask += 8
                i++
                continue
            }
            netmask += when (b) {
                254 -> 7
                252 -> 6
                248 -> 5
                240 -> 4
                224 -> 3
                192 -> 2
                128 -> 1
                0 -> 0
                else -> return null
            }
            break
        }
        i++
        while (i < bytes.size) {
            if (bytes[i].toInt() != 0) return null
            i++
        }
        return netmask
    }

    /** clearBits (qhostaddress.cpp) for a 128-bit address. */
    private fun clearBits(where: ByteArray, start: Int) {
        val end = 128
        if (start == end) return
        val bytemask = 256 - (1 shl (8 - (start and 7)))
        where[start / 8] = (where[start / 8].toInt() and bytemask).toByte()
        for (i in (start + 7) / 8 until end / 8) where[i] = 0
    }

    /**
     * QHostAddress::parseSubnet (qhostaddress.cpp): "a.b.c.d/nn", "a.b.c.d/m.m.m.m", the short "a.b/nn" forms and
     * "<ipv6>/nn"; the host bits are cleared. Returns the network address text and the prefix length, or null.
     */
    fun parseSubnet(subnet: String): Pair<String, Int>? {
        if (subnet.isEmpty()) return null
        val slash = subnet.indexOf('/')
        val netStr = if (slash != -1) subnet.substring(0, slash) else subnet
        var netmask = -1L
        val isIpv6 = netStr.contains(':')
        if (slash != -1) {
            netmask = if (!isIpv6 && subnet.indexOf('.', slash + 1) != -1) {
                (netmaskPrefixLength(subnet.substring(slash + 1)) ?: return null).toLong()
            } else {
                toUInt(subnet.substring(slash + 1)) ?: return null
            }
        }
        if (isIpv6) {
            if (netmask > 128) return null
            if (netmask < 0) netmask = 128
            val (bytes, scope) = parseHostAddressV6(netStr) ?: return null
            clearBits(bytes, netmask.toInt())
            val text = Hosts.formatIpv6(bytes) + (if (scope.isNotEmpty()) "%$scope" else "")
            return Pair(text, netmask.toInt())
        }
        if (netmask > 32) return null
        val parts = netStr.split('.').toMutableList()
        if (parts.size > 4) return null
        if (parts[parts.size - 1].isEmpty()) parts.removeAt(parts.size - 1)
        var addr = 0L
        for (part in parts) {
            val byteValue = toUInt(part) ?: return null
            if (byteValue > 255) return null
            addr = (addr shl 8) + byteValue
        }
        addr = (addr shl (8 * (4 - parts.size))) and 0xFFFFFFFFL
        if (netmask == -1L) {
            netmask = (8 * parts.size).toLong()
        } else if (netmask == 0L) {
            addr = 0
        } else if (netmask != 32L) {
            val shift = (32 - netmask).toInt()
            addr = addr and (((0xFFFFFFFFL ushr shift) shl shift) and 0xFFFFFFFFL)
        }
        return Pair(Hosts.formatIpv4(addr), netmask.toInt())
    }

    /** vpnfiPrefix (vpnFileImport.cpp:134-139): parseSubnet takes both the /24 and the /255.255.255.0 spelling. */
    fun prefix(address: String, mask: String): String {
        val parsed = parseSubnet("$address/$mask") ?: return ""
        return parsed.first + "/" + parsed.second
    }

    /** vpnfiNormalizePrefix (vpnFileImport.cpp:141-146). */
    fun normalizePrefix(prefix: String): String {
        val parsed = parseSubnet(prefix) ?: return ""
        return parsed.first + "/" + parsed.second
    }

    /** vpnfiFingerprint (vpnFileImport.cpp:149-157): 64 bare lowercase hex characters, not colon-separated. */
    fun fingerprint(raw: String): String {
        val hex = raw.replace(":", "").replace(" ", "").lowercase()
        return if (FINGERPRINT.matches(hex)) hex else ""
    }

    /** vpnfiNetwork (vpnFileImport.cpp:159-169). */
    fun network(proto: String): String {
        val value = proto.lowercase()
        if (value == "udp" || value == "udp4" || value == "udp6" || value == "tcp" || value == "tcp4" || value == "tcp6") return value
        if (value == "tcp-client") return "tcp"
        if (value == "tcp4-client") return "tcp4"
        if (value == "tcp6-client") return "tcp6"
        return ""
    }

    /** vpnfiRouteKeyword (vpnFileImport.cpp:171-175). */
    fun routeKeyword(value: String): Boolean =
        value == "vpn_gateway" || value == "net_gateway" || value == "remote_host" || value == "default" || value == "dhcp"

    /** vpnfiParseRemote (vpnFileImport.cpp:177-188): `remote host [port] [proto]` in either order. */
    fun parseRemote(args: List<String>): Remote {
        val remote = Remote()
        if (args.size < 2) return remote
        remote.host = args[1]
        var i = 2
        while (i < args.size && i < 4) {
            val port = toIntOk(args[i])
            if (port != null && port > 0) {
                remote.port = port
            } else {
                val net = network(args[i])
                if (net.isNotEmpty()) remote.network = net
            }
            i++
        }
        return remote
    }

    /** The `key-direction` / `secret` / `tls-auth` direction argument (vpnFileImport.cpp:396,402,423). */
    fun keyDirection(value: String): String = if (value == "1") "client" else if (value == "0") "server" else ""

    /** vpnfiOvpnIgnored (vpnFileImport.cpp:191-204): no endpoint equivalent, or the endpoint already behaves this way. */
    val OVPN_IGNORED: Set<String> = setOf(
        "allow-recursive-routing", "auth-nocache", "auth-token", "auth-token-user", "bind", "block-outside-dns",
        "client", "connect-retry", "connect-retry-max", "connect-timeout", "daemon", "dco", "dev-node", "dh",
        "dhcp-option", "dhcp-release", "dhcp-renew", "disable-dco", "down", "down-pre", "echo", "errors-to-stderr",
        "fast-io", "float", "group", "hash-size", "ifconfig-noexec", "ifconfig-nowarn", "ipchange", "key-method",
        "log", "log-append", "lport", "machine-readable-output", "max-routes", "mode", "mssfix-default", "mtu-disc",
        "mtu-test", "multihome", "mute", "mute-replay-warnings", "ncp-disable", "nice", "nobind", "opt-verify",
        "passtos", "persist-key", "persist-local-ip", "persist-remote-ip", "persist-tun", "ping-timer-rem", "pull",
        "push-peer-info", "rcvbuf", "register-dns", "remap-usr1", "remote-random-hostname", "resolv-retry",
        "route-delay", "route-method", "route-pre-down", "route-up", "script-security", "server-poll-timeout",
        "service", "setenv-safe", "single-session", "sndbuf", "socket-flags", "status", "suppress-timestamps",
        "syslog", "tls-client", "tls-exit", "tls-verify", "topology-subnet", "tran-window", "tun-mtu-extra",
        "txqueuelen", "up", "up-delay", "up-restart", "user", "verb", "windows-driver", "writepid",
    )

    /** vpnfiOvpnUnsupported (vpnFileImport.cpp:207-212): recognised, but nothing in the endpoint carries them. */
    val OVPN_UNSUPPORTED: Set<String> = setOf(
        "askpass", "capath", "cryptoapicert", "engine", "extra-certs", "http-proxy", "http-proxy-option",
        "inactive", "link-mtu", "management", "management-hold", "management-query-passwords", "no-replay",
        "ping-exit", "pkcs11-id", "pkcs11-providers", "providers", "socks-proxy", "tls-ciphersuites",
        "tls-crypt-v2-verify", "tls-export-cert", "x509-track", "x509-username-field",
    )

    /** vpnfiOvpnServerOnly (vpnFileImport.cpp:214-216). */
    val OVPN_SERVER_ONLY: Set<String> = setOf(
        "client-config-dir", "ifconfig-pool", "push", "server", "server-bridge", "tls-server",
    )
}

/**
 * openvpn (include/configs/outbounds/openvpn.h, src/configs/outbounds/openvpn.cpp): a sing-box endpoint. The stored
 * JSON says `type:"openvpn"` while Build emits `type:"openvpn-client"`; JSON parse accepts both. The share "link"
 * is the text of an `.ovpn` file ([parseOvpnConfig]).
 */
class OpenVpn : Outbound("openvpn") {

    /** OpenVPNRemote (openvpn.h:6-16). */
    class Remote {
        @JvmField var server: String = ""
        @JvmField var server_port: Int = 0
        @JvmField var network: String = ""

        /** openvpn.cpp:21-28. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("server")) server = obj.string("server")
            if (obj.contains("server_port")) server_port = obj.int("server_port")
            if (obj.contains("network")) network = obj.string("network")
            return true
        }

        /** openvpn.cpp:30-37. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (server.isNotEmpty()) obj["server"] = server
            if (server_port > 0) obj["server_port"] = server_port
            if (network.isNotEmpty()) obj["network"] = network
            return obj
        }

        /** openvpn.cpp:39-42. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNPullFilter (openvpn.h:18-27). */
    class PullFilter {
        @JvmField var action: String = ""
        @JvmField var text: String = ""

        /** openvpn.cpp:44-50. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("action")) action = obj.string("action")
            if (obj.contains("text")) text = obj.string("text")
            return true
        }

        /** openvpn.cpp:52-59. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (action.isEmpty() && text.isEmpty()) return obj
            obj["action"] = action
            obj["text"] = text
            return obj
        }

        /** openvpn.cpp:61-64. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNControlWrap (openvpn.h:29-43): tls-auth / tls-crypt / tls-crypt-v2 control channel wrapping. */
    class ControlWrap {
        @JvmField var type: String = ""
        @JvmField var key: MutableList<String> = ArrayList()
        @JvmField var direction: String = ""
        /** Conflicts with `key`. */
        @JvmField var key_path: String = ""

        /** openvpn.cpp:66-74. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("type")) type = obj.string("type")
            if (obj.contains("key")) key = VpnFileImport.listable(obj, "key")
            if (obj.contains("key_path")) key_path = obj.string("key_path")
            if (obj.contains("direction")) direction = obj.string("direction")
            return true
        }

        /** openvpn.cpp:76-85. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (type.isEmpty()) return obj
            obj["type"] = type
            if (key.isNotEmpty()) obj["key"] = JsonValues.stringArray(key)
            if (key_path.isNotEmpty()) obj["key_path"] = key_path
            if (direction.isNotEmpty()) obj["direction"] = direction
            return obj
        }

        /** openvpn.cpp:87-90. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNTLS (openvpn.h:45-74); not the sing-box TLS block, so [hasTls] stays false. */
    class Tls {
        @JvmField var certificate: MutableList<String> = ArrayList()
        @JvmField var client_certificate: MutableList<String> = ArrayList()
        @JvmField var client_key: MutableList<String> = ArrayList()
        @JvmField var control_wrap: ControlWrap = ControlWrap()

        /** Each *_path conflicts with its inline value. */
        @JvmField var server_name: String = ""
        @JvmField var server_name_type: String = ""
        @JvmField var certificate_path: String = ""
        @JvmField var client_certificate_path: String = ""
        @JvmField var client_key_path: String = ""
        @JvmField var peer_fingerprint: MutableList<String> = ArrayList()
        @JvmField var crl_path: String = ""
        @JvmField var remote_certificate_ku: MutableList<String> = ArrayList()
        @JvmField var remote_certificate_eku: String = ""
        @JvmField var remote_certificate_tls: String = ""
        @JvmField var certificate_profile: String = ""
        @JvmField var ns_certificate_type: String = ""
        @JvmField var version_min: String = ""
        @JvmField var version_max: String = ""
        @JvmField var cipher: String = ""
        @JvmField var groups: String = ""

        /** openvpn.cpp:92-116. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("server_name")) server_name = obj.string("server_name")
            if (obj.contains("server_name_type")) server_name_type = obj.string("server_name_type")
            if (obj.contains("certificate")) certificate = VpnFileImport.listable(obj, "certificate")
            if (obj.contains("certificate_path")) certificate_path = obj.string("certificate_path")
            if (obj.contains("client_certificate")) client_certificate = VpnFileImport.listable(obj, "client_certificate")
            if (obj.contains("client_certificate_path")) client_certificate_path = obj.string("client_certificate_path")
            if (obj.contains("client_key")) client_key = VpnFileImport.listable(obj, "client_key")
            if (obj.contains("client_key_path")) client_key_path = obj.string("client_key_path")
            if (obj.contains("peer_fingerprint")) peer_fingerprint = VpnFileImport.listable(obj, "peer_fingerprint")
            if (obj.contains("crl_path")) crl_path = obj.string("crl_path")
            if (obj.contains("remote_certificate_ku")) remote_certificate_ku = VpnFileImport.listable(obj, "remote_certificate_ku")
            if (obj.contains("remote_certificate_eku")) remote_certificate_eku = obj.string("remote_certificate_eku")
            if (obj.contains("remote_certificate_tls")) remote_certificate_tls = obj.string("remote_certificate_tls")
            if (obj.contains("certificate_profile")) certificate_profile = obj.string("certificate_profile")
            if (obj.contains("ns_certificate_type")) ns_certificate_type = obj.string("ns_certificate_type")
            if (obj.contains("version_min")) version_min = obj.string("version_min")
            if (obj.contains("version_max")) version_max = obj.string("version_max")
            if (obj.contains("cipher")) cipher = obj.string("cipher")
            if (obj.contains("groups")) groups = obj.string("groups")
            if (obj.contains("control_wrap")) control_wrap.parseFromJson(obj.obj("control_wrap"))
            return true
        }

        /** openvpn.cpp:118-142. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (server_name.isNotEmpty()) obj["server_name"] = server_name
            if (server_name_type.isNotEmpty()) obj["server_name_type"] = server_name_type
            if (certificate.isNotEmpty()) obj["certificate"] = JsonValues.stringArray(certificate)
            if (certificate_path.isNotEmpty()) obj["certificate_path"] = certificate_path
            if (client_certificate.isNotEmpty()) obj["client_certificate"] = JsonValues.stringArray(client_certificate)
            if (client_certificate_path.isNotEmpty()) obj["client_certificate_path"] = client_certificate_path
            if (client_key.isNotEmpty()) obj["client_key"] = JsonValues.stringArray(client_key)
            if (client_key_path.isNotEmpty()) obj["client_key_path"] = client_key_path
            if (peer_fingerprint.isNotEmpty()) obj["peer_fingerprint"] = JsonValues.stringArray(peer_fingerprint)
            if (crl_path.isNotEmpty()) obj["crl_path"] = crl_path
            if (remote_certificate_ku.isNotEmpty()) obj["remote_certificate_ku"] = JsonValues.stringArray(remote_certificate_ku)
            if (remote_certificate_eku.isNotEmpty()) obj["remote_certificate_eku"] = remote_certificate_eku
            if (remote_certificate_tls.isNotEmpty()) obj["remote_certificate_tls"] = remote_certificate_tls
            if (certificate_profile.isNotEmpty()) obj["certificate_profile"] = certificate_profile
            if (ns_certificate_type.isNotEmpty()) obj["ns_certificate_type"] = ns_certificate_type
            if (version_min.isNotEmpty()) obj["version_min"] = version_min
            if (version_max.isNotEmpty()) obj["version_max"] = version_max
            if (cipher.isNotEmpty()) obj["cipher"] = cipher
            if (groups.isNotEmpty()) obj["groups"] = groups
            val wrap = control_wrap.exportToJson()
            if (wrap.isNotEmpty()) obj["control_wrap"] = wrap
            return obj
        }

        /** openvpn.cpp:144-147. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    @JvmField var network: String = ""
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var static_challenge: String = ""
    @JvmField var static_challenge_echo: Boolean = false
    @JvmField var mtu: Int = 0
    @JvmField var tls: Tls = Tls()
    /** Rawline-only, never sent to the core (openvpn.h:86-89); Android has no OTP store, so it only round-trips. */
    @JvmField var otp_profile_id: Int = -1
    @JvmField var only_advertised_routes: Boolean = true
    @JvmField var tunnel_dns: String = TUNNEL_DNS_PREFER

    @JvmField var mode: String = ""
    @JvmField var servers: MutableList<Remote> = ArrayList()
    @JvmField var remote_random: Boolean = false
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var peer_address: String = ""
    @JvmField var peer_address_ipv6: String = ""
    @JvmField var topology: String = ""
    @JvmField var auth_retry: String = ""
    @JvmField var static_key: MutableList<String> = ArrayList()
    @JvmField var static_key_path: String = ""
    @JvmField var key_direction: String = ""
    @JvmField var cipher: String = ""
    @JvmField var data_ciphers: MutableList<String> = ArrayList()
    @JvmField var data_ciphers_fallback: String = ""
    @JvmField var auth: String = ""
    @JvmField var mss_fix: Int = 0
    @JvmField var mss_fix_disabled: Boolean = false
    @JvmField var mss_fix_mode: String = ""
    @JvmField var fragment: Int = 0
    @JvmField var replay_window: Int = 0
    @JvmField var replay_window_time: String = ""
    @JvmField var compression: String = ""
    @JvmField var compression_lzo: String = ""
    @JvmField var allow_compression: String = ""
    @JvmField var route_no_pull: Boolean = false
    @JvmField var pull_filters: MutableList<PullFilter> = ArrayList()
    @JvmField var routes: MutableList<String> = ArrayList()
    @JvmField var route_gateway: String = ""
    @JvmField var route_metric: Int = 0
    @JvmField var redirect_gateway: Boolean = false
    @JvmField var redirect_gateway_flags: MutableList<String> = ArrayList()
    @JvmField var redirect_private: Boolean = false
    @JvmField var block_ipv6: Boolean = false
    @JvmField var ping_interval: String = ""
    @JvmField var ping_restart: String = ""
    @JvmField var ping_restart_disabled: Boolean = false
    @JvmField var renegotiate_interval: String = ""
    @JvmField var renegotiate_disabled: Boolean = false
    @JvmField var renegotiate_bytes: Long = 0
    @JvmField var renegotiate_packets: Long = 0
    @JvmField var tls_timeout: String = ""
    @JvmField var handshake_window: String = ""
    @JvmField var explicit_exit_notify: Int = 0
    @JvmField var system: Boolean = false
    /** sing-box "name" (system interface name); [name] is the tag (openvpn.h:135-136). */
    @JvmField var interface_name: String = ""
    @JvmField var udp_timeout: String = ""
    @JvmField var udp_mapping: String = ""
    @JvmField var udp_filtering: String = ""
    @JvmField var udp_nat_max: Int = 0

    /** openvpn.cpp:149-152: the "link" is the text of an .ovpn file. */
    override fun parseFromLink(link: String): Boolean = parseOvpnConfig(link, null)

    /** openvpn.cpp:154-237. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        val type = obj.string("type")
        if (type != "openvpn" && type != "openvpn-client") return false
        super.parseFromJson(obj)

        if (obj.contains("mode")) mode = obj.string("mode")
        if (obj.contains("network")) network = obj.string("network")
        if (obj.contains("servers")) {
            servers.clear()
            for (item in obj.array("servers")) {
                val remote = Remote()
                if (remote.parseFromJson(item as? JsonObject ?: JsonObject())) servers.add(remote)
            }
        }
        if (obj.contains("remote_random")) remote_random = obj.bool("remote_random")
        if (obj.contains("address")) address = VpnFileImport.listable(obj, "address")
        if (obj.contains("peer_address")) peer_address = obj.string("peer_address")
        if (obj.contains("peer_address_ipv6")) peer_address_ipv6 = obj.string("peer_address_ipv6")
        if (obj.contains("topology")) topology = obj.string("topology")
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("auth_retry")) auth_retry = obj.string("auth_retry")
        if (obj.contains("static_challenge")) static_challenge = obj.string("static_challenge")
        if (obj.contains("static_challenge_echo")) static_challenge_echo = obj.bool("static_challenge_echo")
        if (obj.contains("static_key")) static_key = VpnFileImport.listable(obj, "static_key")
        if (obj.contains("static_key_path")) static_key_path = obj.string("static_key_path")
        if (obj.contains("key_direction")) key_direction = obj.string("key_direction")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("cipher")) cipher = obj.string("cipher")
        if (obj.contains("data_ciphers")) data_ciphers = VpnFileImport.listable(obj, "data_ciphers")
        if (obj.contains("data_ciphers_fallback")) data_ciphers_fallback = obj.string("data_ciphers_fallback")
        if (obj.contains("auth")) auth = obj.string("auth")
        if (obj.contains("mss_fix")) mss_fix = obj.int("mss_fix")
        if (obj.contains("mss_fix_disabled")) mss_fix_disabled = obj.bool("mss_fix_disabled")
        if (obj.contains("mss_fix_mode")) mss_fix_mode = obj.string("mss_fix_mode")
        if (obj.contains("fragment")) fragment = obj.int("fragment")
        if (obj.contains("replay_window")) replay_window = obj.int("replay_window")
        if (obj.contains("replay_window_time")) replay_window_time = obj.string("replay_window_time")
        if (obj.contains("compression")) compression = obj.string("compression")
        if (obj.contains("compression_lzo")) compression_lzo = obj.string("compression_lzo")
        if (obj.contains("allow_compression")) allow_compression = obj.string("allow_compression")
        if (obj.contains("route_no_pull")) route_no_pull = obj.bool("route_no_pull")
        if (obj.contains("pull_filters")) {
            pull_filters.clear()
            for (item in obj.array("pull_filters")) {
                val filter = PullFilter()
                if (filter.parseFromJson(item as? JsonObject ?: JsonObject())) pull_filters.add(filter)
            }
        }
        if (obj.contains("routes")) routes = VpnFileImport.listable(obj, "routes")
        if (obj.contains("route_gateway")) route_gateway = obj.string("route_gateway")
        if (obj.contains("route_metric")) route_metric = obj.int("route_metric")
        if (obj.contains("redirect_gateway")) redirect_gateway = obj.bool("redirect_gateway")
        if (obj.contains("redirect_gateway_flags")) redirect_gateway_flags = VpnFileImport.listable(obj, "redirect_gateway_flags")
        if (obj.contains("redirect_private")) redirect_private = obj.bool("redirect_private")
        if (obj.contains("block_ipv6")) block_ipv6 = obj.bool("block_ipv6")
        if (obj.contains("ping_interval")) ping_interval = obj.string("ping_interval")
        if (obj.contains("ping_restart")) ping_restart = obj.string("ping_restart")
        if (obj.contains("ping_restart_disabled")) ping_restart_disabled = obj.bool("ping_restart_disabled")
        if (obj.contains("renegotiate_interval")) renegotiate_interval = obj.string("renegotiate_interval")
        if (obj.contains("renegotiate_disabled")) renegotiate_disabled = obj.bool("renegotiate_disabled")
        if (obj.contains("renegotiate_bytes")) renegotiate_bytes = obj.integer("renegotiate_bytes")
        if (obj.contains("renegotiate_packets")) renegotiate_packets = obj.integer("renegotiate_packets")
        if (obj.contains("tls_timeout")) tls_timeout = obj.string("tls_timeout")
        if (obj.contains("handshake_window")) handshake_window = obj.string("handshake_window")
        if (obj.contains("explicit_exit_notify")) explicit_exit_notify = obj.int("explicit_exit_notify")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("name")) interface_name = obj.string("name")
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("udp_timeout")) udp_timeout = obj.string("udp_timeout")
        if (obj.contains("udp_mapping")) udp_mapping = obj.string("udp_mapping")
        if (obj.contains("udp_filtering")) udp_filtering = obj.string("udp_filtering")
        if (obj.contains("udp_nat_max")) udp_nat_max = obj.int("udp_nat_max")

        if (obj.contains("otp_profile_id")) otp_profile_id = obj.int("otp_profile_id")
        if (obj.contains("only_advertised_routes")) only_advertised_routes = obj.bool("only_advertised_routes")
        // Profiles saved before the mode existed carry the two checkboxes it replaced.
        if (obj.contains("tunnel_dns")) tunnel_dns = obj.string("tunnel_dns")
        else if (obj.bool("block_outside_dns")) tunnel_dns = TUNNEL_DNS_STRICT
        else if (obj.contains("use_tunnel_dns") && !obj.bool("use_tunnel_dns")) tunnel_dns = TUNNEL_DNS_NONE
        return true
    }

    /** openvpn.cpp:239-318. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "openvpn"
        obj.merge(baseExportToJson())

        if (mode.isNotEmpty()) obj["mode"] = mode
        if (network.isNotEmpty()) obj["network"] = network
        if (servers.isNotEmpty()) {
            val remotes = JsonArray()
            for (remote in servers) {
                val item = remote.exportToJson()
                if (item.isNotEmpty()) remotes.add(item)
            }
            if (remotes.isNotEmpty()) obj["servers"] = remotes
        }
        if (remote_random) obj["remote_random"] = true
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (peer_address.isNotEmpty()) obj["peer_address"] = peer_address
        if (peer_address_ipv6.isNotEmpty()) obj["peer_address_ipv6"] = peer_address_ipv6
        if (topology.isNotEmpty()) obj["topology"] = topology
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (auth_retry.isNotEmpty()) obj["auth_retry"] = auth_retry
        if (static_challenge.isNotEmpty()) obj["static_challenge"] = static_challenge
        if (static_challenge_echo) obj["static_challenge_echo"] = true
        if (static_key.isNotEmpty()) obj["static_key"] = JsonValues.stringArray(static_key)
        if (static_key_path.isNotEmpty()) obj["static_key_path"] = static_key_path
        if (key_direction.isNotEmpty()) obj["key_direction"] = key_direction
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        if (cipher.isNotEmpty()) obj["cipher"] = cipher
        if (data_ciphers.isNotEmpty()) obj["data_ciphers"] = JsonValues.stringArray(data_ciphers)
        if (data_ciphers_fallback.isNotEmpty()) obj["data_ciphers_fallback"] = data_ciphers_fallback
        if (auth.isNotEmpty()) obj["auth"] = auth
        if (mss_fix > 0) obj["mss_fix"] = mss_fix
        if (mss_fix_disabled) obj["mss_fix_disabled"] = true
        if (mss_fix_mode.isNotEmpty()) obj["mss_fix_mode"] = mss_fix_mode
        if (fragment > 0) obj["fragment"] = fragment
        if (replay_window > 0) obj["replay_window"] = replay_window
        if (replay_window_time.isNotEmpty()) obj["replay_window_time"] = replay_window_time
        if (compression.isNotEmpty()) obj["compression"] = compression
        if (compression_lzo.isNotEmpty()) obj["compression_lzo"] = compression_lzo
        if (allow_compression.isNotEmpty()) obj["allow_compression"] = allow_compression
        if (route_no_pull) obj["route_no_pull"] = true
        if (pull_filters.isNotEmpty()) {
            val filters = JsonArray()
            for (filter in pull_filters) {
                val item = filter.exportToJson()
                if (item.isNotEmpty()) filters.add(item)
            }
            if (filters.isNotEmpty()) obj["pull_filters"] = filters
        }
        if (routes.isNotEmpty()) obj["routes"] = JsonValues.stringArray(routes)
        if (route_gateway.isNotEmpty()) obj["route_gateway"] = route_gateway
        if (route_metric > 0) obj["route_metric"] = route_metric
        if (redirect_gateway) obj["redirect_gateway"] = true
        if (redirect_gateway_flags.isNotEmpty()) obj["redirect_gateway_flags"] = JsonValues.stringArray(redirect_gateway_flags)
        if (redirect_private) obj["redirect_private"] = true
        if (block_ipv6) obj["block_ipv6"] = true
        if (ping_interval.isNotEmpty()) obj["ping_interval"] = ping_interval
        if (ping_restart.isNotEmpty()) obj["ping_restart"] = ping_restart
        if (ping_restart_disabled) obj["ping_restart_disabled"] = true
        if (renegotiate_interval.isNotEmpty()) obj["renegotiate_interval"] = renegotiate_interval
        if (renegotiate_disabled) obj["renegotiate_disabled"] = true
        if (renegotiate_bytes > 0) obj["renegotiate_bytes"] = renegotiate_bytes
        if (renegotiate_packets > 0) obj["renegotiate_packets"] = renegotiate_packets
        if (tls_timeout.isNotEmpty()) obj["tls_timeout"] = tls_timeout
        if (handshake_window.isNotEmpty()) obj["handshake_window"] = handshake_window
        if (explicit_exit_notify > 0) obj["explicit_exit_notify"] = explicit_exit_notify
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (mtu > 0) obj["mtu"] = mtu
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max

        if (otp_profile_id >= 0) obj["otp_profile_id"] = otp_profile_id
        obj["only_advertised_routes"] = only_advertised_routes
        obj["tunnel_dns"] = tunnel_dns
        return obj
    }

    /** openvpn.cpp:320-424. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "openvpn-client"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))

        // `server`/`server_port` and `servers` are mutually exclusive.
        if (servers.isEmpty()) {
            if (server.isNotEmpty()) obj["server"] = server
            if (serverPort > 0) obj["server_port"] = serverPort
        } else {
            val remotes = JsonArray()
            for (remote in servers) {
                val item = remote.build(ctx)
                if (item.isNotEmpty()) remotes.add(item)
            }
            obj["servers"] = remotes
            if (remote_random) obj["remote_random"] = true
        }

        // Android has no OTP store: otp_profile_id only round-trips and the stored credentials reach the core as they are.
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (static_challenge.isNotEmpty()) obj["static_challenge"] = static_challenge
        if (static_challenge_echo) obj["static_challenge_echo"] = true

        if (mode.isNotEmpty()) obj["mode"] = mode
        if (network.isNotEmpty()) obj["network"] = network
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (peer_address.isNotEmpty()) obj["peer_address"] = peer_address
        if (peer_address_ipv6.isNotEmpty()) obj["peer_address_ipv6"] = peer_address_ipv6
        if (topology.isNotEmpty()) obj["topology"] = topology
        if (auth_retry.isNotEmpty()) obj["auth_retry"] = auth_retry
        if (static_key.isNotEmpty()) obj["static_key"] = JsonValues.stringArray(static_key)
        if (static_key_path.isNotEmpty()) obj["static_key_path"] = static_key_path
        if (key_direction.isNotEmpty()) obj["key_direction"] = key_direction
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        if (cipher.isNotEmpty()) obj["cipher"] = cipher
        if (data_ciphers.isNotEmpty()) obj["data_ciphers"] = JsonValues.stringArray(data_ciphers)
        if (data_ciphers_fallback.isNotEmpty()) obj["data_ciphers_fallback"] = data_ciphers_fallback
        if (auth.isNotEmpty()) obj["auth"] = auth
        if (mss_fix > 0) obj["mss_fix"] = mss_fix
        if (mss_fix_disabled) obj["mss_fix_disabled"] = true
        if (mss_fix_mode.isNotEmpty()) obj["mss_fix_mode"] = mss_fix_mode
        if (fragment > 0) obj["fragment"] = fragment
        if (replay_window > 0) obj["replay_window"] = replay_window
        if (replay_window_time.isNotEmpty()) obj["replay_window_time"] = replay_window_time
        if (compression.isNotEmpty()) obj["compression"] = compression
        if (compression_lzo.isNotEmpty()) obj["compression_lzo"] = compression_lzo
        if (allow_compression.isNotEmpty()) obj["allow_compression"] = allow_compression
        if (route_no_pull) obj["route_no_pull"] = true
        if (pull_filters.isNotEmpty()) {
            val filters = JsonArray()
            for (filter in pull_filters) {
                val item = filter.build(ctx)
                if (item.isNotEmpty()) filters.add(item)
            }
            if (filters.isNotEmpty()) obj["pull_filters"] = filters
        }
        if (routes.isNotEmpty()) obj["routes"] = JsonValues.stringArray(routes)
        if (route_gateway.isNotEmpty()) obj["route_gateway"] = route_gateway
        if (route_metric > 0) obj["route_metric"] = route_metric
        if (redirect_gateway) obj["redirect_gateway"] = true
        if (redirect_gateway_flags.isNotEmpty()) obj["redirect_gateway_flags"] = JsonValues.stringArray(redirect_gateway_flags)
        if (redirect_private) obj["redirect_private"] = true
        if (block_ipv6) obj["block_ipv6"] = true
        if (ping_interval.isNotEmpty()) obj["ping_interval"] = ping_interval
        if (ping_restart.isNotEmpty()) obj["ping_restart"] = ping_restart
        if (ping_restart_disabled) obj["ping_restart_disabled"] = true
        if (renegotiate_interval.isNotEmpty()) obj["renegotiate_interval"] = renegotiate_interval
        if (renegotiate_disabled) obj["renegotiate_disabled"] = true
        if (renegotiate_bytes > 0) obj["renegotiate_bytes"] = renegotiate_bytes
        if (renegotiate_packets > 0) obj["renegotiate_packets"] = renegotiate_packets
        if (tls_timeout.isNotEmpty()) obj["tls_timeout"] = tls_timeout
        if (handshake_window.isNotEmpty()) obj["handshake_window"] = handshake_window
        if (explicit_exit_notify > 0) obj["explicit_exit_notify"] = explicit_exit_notify
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (mtu > 0) obj["mtu"] = mtu
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max
        return BuildResult(obj)
    }

    /** openvpn.cpp:426-429. */
    override fun displayType(): String = "OpenVPN"

    /** openvpn.cpp:431-437. */
    override fun security(): SecurityInfo {
        if (mode == "static_key") return SecurityInfo("Static Key", "", SecurityLevel.Weak)
        val pinned = tls.certificate.isNotEmpty() || tls.certificate_path.isNotEmpty() || tls.peer_fingerprint.isNotEmpty()
        if (!pinned) return SecurityInfo("Unverified TLS", "", SecurityLevel.Weak)
        return SecurityInfo("TLS", "", SecurityLevel.Secure)
    }

    /** openvpn.cpp:439-442. */
    override fun isEndpoint(): Boolean = true

    /** openvpn.cpp:444-447. */
    override fun supportsCredentialStrip(): Boolean = true

    /** openvpn.cpp:449-464. */
    override fun stripCredentials() {
        username = ""
        password = ""
        static_key.clear()
        static_key_path = ""
        otp_profile_id = -1
        tls.client_key.clear()
        tls.client_key_path = ""
        tls.control_wrap.key.clear()
        tls.control_wrap.key_path = ""
        // Absolute local paths: meaningless on another machine and they carry the OS user name.
        tls.certificate_path = ""
        tls.client_certificate_path = ""
        tls.crl_path = ""
    }

    /**
     * ParseOvpnConfig (vpnFileImport.cpp:219-742) applied to this profile. Every note the desktop would show is
     * appended to [problems]; a fatal problem appends its reason too, then returns false.
     */
    @JvmOverloads
    fun parseOvpnConfig(body: String, problems: MutableList<String>? = null): Boolean {
        val notes = ArrayList<String>()
        val directives = VpnFileImport.readOvpnDirectives(body)
        fun flush() {
            problems?.addAll(notes)
        }
        if (directives.isEmpty()) {
            notes.add("Empty OpenVPN configuration.")
            flush()
            return false
        }

        servers.clear()
        address.clear()
        routes.clear()
        data_ciphers.clear()
        pull_filters.clear()
        redirect_gateway_flags.clear()
        tls.peer_fingerprint.clear()
        tls.remote_certificate_ku.clear()

        val remotes = ArrayList<VpnFileImport.Remote>()
        var defaultPort = 0
        var keyDirection = ""
        var legacyCipher = ""
        var ifconfigLocal = ""
        var ifconfigSecond = ""
        var fatal = ""
        var clientCertSwitch = ""
        var friendlyName = ""

        // OpenVPN Connect keeps its display name in a comment, which the tokenizer drops.
        for (line in body.split('\n')) {
            var comment = VpnFileImport.trimmed(line)
            if (!comment.startsWith('#')) continue
            comment = VpnFileImport.trimmed(comment.substring(1))
            val prefix = "OVPN_FRIENDLY_PROFILE_NAME="
            if (comment.startsWith(prefix)) friendlyName = VpnFileImport.trimmed(comment.substring(prefix.length))
        }

        for (item in directives) {
            if (fatal.isNotEmpty()) break

            if (item.inlineTag.isNotEmpty()) {
                val tag = item.inlineTag
                if (tag == "connection") {
                    var remote = VpnFileImport.Remote()
                    var blockNetwork = ""
                    var blockPort = 0
                    for (line in item.inlineBody) {
                        val args = VpnFileImport.tokenize(line)
                        if (args.isEmpty()) continue
                        if (args[0] == "remote") remote = VpnFileImport.parseRemote(args)
                        else if (args[0] == "proto" && args.size > 1) blockNetwork = VpnFileImport.network(args[1])
                        else if ((args[0] == "port" || args[0] == "rport") && args.size > 1) blockPort = QtStrings.toInt(args[1])
                    }
                    if (remote.host.isEmpty()) {
                        notes.add("<connection> block without a remote, skipped.")
                        continue
                    }
                    if (remote.network.isEmpty()) remote.network = blockNetwork
                    if (remote.port == 0) remote.port = blockPort
                    remotes.add(remote)
                    continue
                }
                when (tag) {
                    "ca" -> tls.certificate = ArrayList(item.inlineBody)
                    "cert" -> tls.client_certificate = ArrayList(item.inlineBody)
                    "key" -> tls.client_key = ArrayList(item.inlineBody)
                    "tls-auth", "tls-crypt", "tls-crypt-v2" -> {
                        tls.control_wrap.type = tag.replace('-', '_')
                        tls.control_wrap.key = ArrayList(item.inlineBody)
                    }
                    "secret" -> {
                        mode = "static_key"
                        static_key = ArrayList(item.inlineBody)
                    }
                    "auth-user-pass" -> {
                        if (item.inlineBody.isNotEmpty()) username = item.inlineBody[0]
                        if (item.inlineBody.size > 1) password = item.inlineBody[1]
                    }
                    "peer-fingerprint" -> for (line in item.inlineBody) {
                        val hex = VpnFileImport.fingerprint(line)
                        if (hex.isNotEmpty()) tls.peer_fingerprint.add(hex)
                        else notes.add("Ignored an unreadable peer fingerprint: $line")
                    }
                    "pkcs12" -> fatal = "PKCS#12 bundles are not supported; export the CA, certificate and key as PEM."
                    else -> notes.add("Ignored inline block: <$tag>")
                }
                continue
            }

            val args = item.args
            val key = args[0]
            val value = if (args.size > 1) args[1] else ""

            if (key in VpnFileImport.OVPN_SERVER_ONLY) {
                fatal = "This is an OpenVPN server configuration ($key), not a client profile."
                continue
            }
            if (key == "pkcs12") {
                fatal = "PKCS#12 bundles are not supported; export the CA, certificate and key as PEM."
                continue
            }
            if (key == "dev" || key == "dev-type") {
                if (value.startsWith("tap")) {
                    fatal = "TAP (layer 2) tunnels are not supported; only `dev tun` profiles can be imported."
                }
                continue
            }
            if (key == "mode") {
                if (value == "server") {
                    fatal = "This is an OpenVPN server configuration (mode server), not a client profile."
                }
                continue
            }

            if (key == "remote") {
                val remote = VpnFileImport.parseRemote(args)
                if (remote.host.isNotEmpty()) remotes.add(remote)
                continue
            }
            if (key == "proto") {
                val net = VpnFileImport.network(value)
                if (net.isNotEmpty()) network = net
                else if (value.endsWith("-server")) fatal = "`proto $value` is a server transport."
                else notes.add("Unknown transport: proto $value")
                continue
            }
            if (key == "port" || key == "rport") {
                defaultPort = QtStrings.toInt(value)
                continue
            }
            if (key == "remote-random") {
                remote_random = true
                continue
            }

            if (key == "ifconfig" && args.size > 2) {
                ifconfigLocal = args[1]
                ifconfigSecond = args[2]
                continue
            }
            if (key == "ifconfig-ipv6" && args.size > 1) {
                if (VpnFileImport.normalizePrefix(args[1]).isNotEmpty()) address.add(args[1])
                else notes.add("Ignored an unreadable IPv6 interface address: ${args[1]}")
                if (args.size > 2) peer_address_ipv6 = args[2]
                continue
            }
            if (key == "topology") {
                topology = value
                continue
            }
            if (key == "auth-retry") {
                auth_retry = value
                continue
            }
            if (key == "auth-user-pass") {
                if (value.isNotEmpty() && value != "[inline]") {
                    notes.add("Credentials live in $value; enter them in the profile editor.")
                }
                continue
            }
            // OpenVPN 3 reads both as a client-side "log in without a client certificate"; 2.x has no such switch.
            if (key == "client-cert-not-required") {
                clientCertSwitch = key
                continue
            }
            if (key == "setenv") {
                val setting = if (args.size > 2) args[2] else ""
                if (value == "CLIENT_CERT" && setting == "0") clientCertSwitch = "setenv CLIENT_CERT 0"
                else if (value == "FRIENDLY_NAME" && setting.isNotEmpty()) friendlyName = setting
                continue
            }
            if (key == "static-challenge") {
                static_challenge = value
                if (args.size > 2) static_challenge_echo = args[2] == "1"
                continue
            }
            if (key == "key-direction") {
                keyDirection = VpnFileImport.keyDirection(value)
                continue
            }
            if (key == "secret") {
                mode = "static_key"
                if (value.isNotEmpty() && value != "[inline]") static_key_path = value
                if (args.size > 2) keyDirection = VpnFileImport.keyDirection(args[2])
                continue
            }

            if (key == "ca" || key == "cert" || key == "key" || key == "crl-verify") {
                if (value.isEmpty() || value == "[inline]") continue
                when (key) {
                    "ca" -> tls.certificate_path = value
                    "cert" -> tls.client_certificate_path = value
                    "key" -> tls.client_key_path = value
                    else -> {
                        tls.crl_path = value
                        if (args.size > 2 && args[2] == "dir") {
                            notes.add("A hash-directory CRL is not supported; point `crl-verify` at a PEM or DER file.")
                        }
                    }
                }
                continue
            }
            if (key == "tls-auth" || key == "tls-crypt" || key == "tls-crypt-v2") {
                tls.control_wrap.type = key.replace('-', '_')
                if (value.isNotEmpty() && value != "[inline]") tls.control_wrap.key_path = value
                if (key == "tls-auth" && args.size > 2) keyDirection = VpnFileImport.keyDirection(args[2])
                continue
            }
            if (key == "peer-fingerprint" || key == "verify-hash") {
                val hex = VpnFileImport.fingerprint(value)
                if (hex.isNotEmpty()) tls.peer_fingerprint.add(hex)
                else notes.add("Ignored an unreadable peer fingerprint: $value")
                continue
            }
            if (key == "remote-cert-tls") {
                tls.remote_certificate_tls = value
                continue
            }
            if (key == "remote-cert-ku") {
                for (i in 1 until args.size) tls.remote_certificate_ku.add(args[i])
                continue
            }
            if (key == "remote-cert-eku") {
                tls.remote_certificate_eku = value
                continue
            }
            if (key == "ns-cert-type") {
                tls.ns_certificate_type = value
                continue
            }
            if (key == "verify-x509-name") {
                tls.server_name = value
                tls.server_name_type = if (args.size > 2) args[2] else "subject"
                continue
            }
            if (key == "tls-version-min" || key == "tls-version-max") {
                if (key == "tls-version-min") tls.version_min = value else tls.version_max = value
                continue
            }
            if (key == "tls-cipher") {
                tls.cipher = value
                continue
            }
            if (key == "tls-groups") {
                tls.groups = value
                continue
            }
            if (key == "tls-cert-profile") {
                tls.certificate_profile = value
                continue
            }

            if (key == "cipher") {
                legacyCipher = value
                continue
            }
            if (key == "data-ciphers" || key == "ncp-ciphers") {
                data_ciphers = QtStrings.splitSkipEmpty(value, ":")
                continue
            }
            if (key == "data-ciphers-fallback") {
                data_ciphers_fallback = value
                continue
            }
            if (key == "auth") {
                auth = value
                continue
            }
            if (key == "mssfix") {
                if (value.isEmpty()) continue
                if (value == "0") {
                    mss_fix_disabled = true
                } else {
                    mss_fix = QtStrings.toInt(value)
                    if (args.size > 2 && (args[2] == "mtu" || args[2] == "fixed")) mss_fix_mode = args[2]
                }
                continue
            }
            if (key == "fragment") {
                fragment = QtStrings.toInt(value)
                continue
            }
            if (key == "replay-window") {
                replay_window = QtStrings.toInt(value)
                if (args.size > 2) replay_window_time = VpnFileImport.duration(args[2])
                continue
            }
            if (key == "compress") {
                val compressMode = value.lowercase()
                // The endpoint spells `compress lzo` as the identical comp-lzo mode.
                if (compressMode == "lzo") compression_lzo = "yes"
                else if (compressMode.isEmpty()) compression = "stub"
                else if (compressMode == "migrate") compression = "none"
                else compression = compressMode
                continue
            }
            if (key == "comp-lzo") {
                compression_lzo = if (value.isEmpty()) "adaptive" else value.lowercase()
                continue
            }
            if (key == "allow-compression") {
                allow_compression = value.lowercase()
                continue
            }

            if (key == "route-nopull") {
                route_no_pull = true
                continue
            }
            if (key == "pull-filter" && args.size > 2) {
                val filter = PullFilter()
                filter.action = args[1]
                filter.text = args[2]
                pull_filters.add(filter)
                continue
            }
            if (key == "route" && args.size > 1) {
                if (VpnFileImport.routeKeyword(args[1])) {
                    notes.add("Ignored a symbolic route target: route ${args[1]}")
                    continue
                }
                val prefix: String
                var next = 2
                if (args[1].contains('/')) {
                    prefix = VpnFileImport.normalizePrefix(args[1])
                } else if (args.size > 2 && !VpnFileImport.routeKeyword(args[2]) && VpnFileImport.prefix(args[1], args[2]).isNotEmpty()) {
                    prefix = VpnFileImport.prefix(args[1], args[2])
                    next = 3
                } else {
                    prefix = VpnFileImport.prefix(args[1], "255.255.255.255")
                }
                if (prefix.isEmpty()) {
                    notes.add("Ignored an unreadable route: ${args.subList(1, args.size).joinToString(" ")}")
                    continue
                }
                if (prefix !in routes) routes.add(prefix)
                if (args.size > next && !VpnFileImport.routeKeyword(args[next]) && route_gateway.isEmpty()) {
                    route_gateway = args[next]
                }
                if (args.size > next + 1 && route_metric == 0) route_metric = QtStrings.toInt(args[next + 1])
                continue
            }
            if (key == "route-ipv6" && args.size > 1) {
                val prefix = VpnFileImport.normalizePrefix(args[1])
                if (prefix.isNotEmpty()) {
                    if (prefix !in routes) routes.add(prefix)
                } else {
                    notes.add("Ignored an unreadable route: ${args.subList(1, args.size).joinToString(" ")}")
                }
                continue
            }
            if (key == "route-gateway") {
                if (!VpnFileImport.routeKeyword(value)) route_gateway = value
                continue
            }
            if (key == "route-metric") {
                route_metric = QtStrings.toInt(value)
                continue
            }
            if (key == "redirect-gateway" || key == "redirect-private") {
                if (key == "redirect-gateway") redirect_gateway = true
                else redirect_private = true
                for (i in 1 until args.size) {
                    val flag = args[i].lowercase()
                    if (flag == "block-local" || flag == "bypass-dhcp" || flag == "bypass-dns") {
                        notes.add("redirect-gateway flag has no sing-box equivalent: $flag")
                        continue
                    }
                    if (flag !in redirect_gateway_flags) redirect_gateway_flags.add(flag)
                }
                continue
            }
            if (key == "block-ipv6") {
                block_ipv6 = true
                continue
            }

            if (key == "keepalive" && args.size > 2) {
                ping_interval = VpnFileImport.duration(args[1])
                ping_restart = VpnFileImport.duration(args[2])
                continue
            }
            if (key == "ping") {
                ping_interval = VpnFileImport.duration(value)
                continue
            }
            if (key == "ping-restart") {
                if (value == "0") ping_restart_disabled = true
                else ping_restart = VpnFileImport.duration(value)
                continue
            }
            if (key == "reneg-sec") {
                if (value == "0") renegotiate_disabled = true
                else renegotiate_interval = VpnFileImport.duration(value)
                continue
            }
            if (key == "reneg-bytes") {
                renegotiate_bytes = QtStrings.toLong(value)
                continue
            }
            if (key == "reneg-pkts") {
                renegotiate_packets = QtStrings.toLong(value)
                continue
            }
            if (key == "tls-timeout") {
                tls_timeout = VpnFileImport.duration(value)
                continue
            }
            if (key == "hand-window") {
                handshake_window = VpnFileImport.duration(value)
                continue
            }
            if (key == "explicit-exit-notify") {
                explicit_exit_notify = if (value.isEmpty()) 1 else QtStrings.toInt(value)
                continue
            }
            if (key == "tun-mtu") {
                mtu = QtStrings.toInt(value)
                continue
            }

            if (key in VpnFileImport.OVPN_IGNORED) continue
            if (key in VpnFileImport.OVPN_UNSUPPORTED) {
                notes.add("Not supported by the OpenVPN endpoint, ignored: $key")
                continue
            }
            notes.add("Unknown OpenVPN directive, ignored: $key")
        }

        if (fatal.isNotEmpty()) {
            notes.add(fatal)
            flush()
            return false
        }

        if (clientCertSwitch.isNotEmpty()) {
            val dropped = tls.client_certificate.isNotEmpty() || tls.client_certificate_path.isNotEmpty() ||
                tls.client_key.isNotEmpty() || tls.client_key_path.isNotEmpty()
            tls.client_certificate.clear()
            tls.client_certificate_path = ""
            tls.client_key.clear()
            tls.client_key_path = ""
            if (dropped) {
                notes.add("`$clientCertSwitch` turns the client certificate off; it was dropped and the server has to accept password login.")
            }
        }
        // The core, like OpenVPN 2.x, refuses a lone certificate or key; OpenVPN 3 only tolerates it behind the switch.
        val hasClientCert = tls.client_certificate.isNotEmpty() || tls.client_certificate_path.isNotEmpty()
        val hasClientKey = tls.client_key.isNotEmpty() || tls.client_key_path.isNotEmpty()
        if (hasClientCert != hasClientKey) {
            notes.add(
                if (hasClientCert) "`cert` without a `key`: add the private key, or `client-cert-not-required` for password-only login."
                else "`key` without a `cert`: add the client certificate."
            )
            flush()
            return false
        }

        for (remote in remotes) {
            if (remote.port == 0) remote.port = if (defaultPort > 0) defaultPort else VpnFileImport.OVPN_DEFAULT_PORT
        }
        if (remotes.isEmpty()) {
            notes.add("No `remote` server in the OpenVPN configuration.")
            flush()
            return false
        }
        // `server`/`server_port` and `servers` conflict in the core.
        if (remotes.size == 1) {
            server = remotes[0].host
            serverPort = remotes[0].port
            if (remotes[0].network.isNotEmpty()) network = remotes[0].network
        } else {
            server = ""
            serverPort = 0
            for (remote in remotes) {
                val entry = Remote()
                entry.server = remote.host
                entry.server_port = remote.port
                entry.network = remote.network
                servers.add(entry)
            }
        }
        if (friendlyName.isNotEmpty()) name = friendlyName
        if (name.isEmpty()) name = remotes[0].host

        if (ifconfigLocal.isNotEmpty() && ifconfigSecond.isNotEmpty()) {
            // Only `--topology subnet` makes the second argument a netmask.
            val masked = topology == "subnet" || ifconfigSecond.startsWith("255.")
            val parsed = VpnFileImport.parseSubnet(ifconfigLocal + "/" + (if (masked) ifconfigSecond else "32"))
            if (parsed != null) address.add(ifconfigLocal + "/" + parsed.second)
            else notes.add("Ignored an unreadable interface address: $ifconfigLocal")
            if (!masked) peer_address = ifconfigSecond
        }

        // `key-direction` binds to the control-channel wrap in TLS mode, the secret otherwise.
        if (keyDirection.isNotEmpty()) {
            if (tls.control_wrap.type == "tls_auth") tls.control_wrap.direction = keyDirection
            else if (mode == "static_key") key_direction = keyDirection
        }
        // Outside static-key mode `cipher` is the pre-negotiation data cipher.
        if (legacyCipher.isNotEmpty()) {
            if (mode == "static_key") cipher = legacyCipher
            else if (data_ciphers_fallback.isEmpty()) data_ciphers_fallback = legacyCipher
        }
        if (tls.remote_certificate_eku.isNotEmpty() && tls.remote_certificate_tls.isNotEmpty()) {
            notes.add("`remote-cert-eku` replaces `remote-cert-tls`; the latter was dropped.")
            tls.remote_certificate_tls = ""
        }
        // A redirect-gateway config is a full tunnel, not a management network.
        if (redirect_gateway && !route_no_pull) only_advertised_routes = false

        // Every inline value conflicts with its own *_path in the core.
        if (tls.certificate.isNotEmpty()) tls.certificate_path = ""
        if (tls.client_certificate.isNotEmpty()) tls.client_certificate_path = ""
        if (tls.client_key.isNotEmpty()) tls.client_key_path = ""
        if (tls.control_wrap.key.isNotEmpty()) tls.control_wrap.key_path = ""
        if (static_key.isNotEmpty()) static_key_path = ""

        flush()
        return true
    }
}
