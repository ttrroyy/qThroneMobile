package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.SecurityInfo
import app.qthrone.mobile.outbound.common.QuicFields
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.JsonValues
import app.qthrone.mobile.outbound.json.jsonObjectOf
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkCodec
import app.qthrone.mobile.outbound.link.LinkParser
import app.qthrone.mobile.outbound.link.ParsedLink
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** hysteria.h:7 */
val hysteriaBBRProfiles = listOf("standard", "conservative", "aggressive")

/** hysteria, v1 and v2 in one class (include/configs/outbounds/hysteria.h, src/configs/outbounds/hysteria.cpp). */
class Hysteria : Outbound("hysteria") {
    @JvmField var protocol_version: String = "1"
    @JvmField var server_ports: MutableList<String> = ArrayList()
    @JvmField var hop_interval: String = ""
    @JvmField var up_mbps: Int = 0
    @JvmField var down_mbps: Int = 0
    @JvmField var obfs: String = ""

    // Hysteria1
    @JvmField var auth_type: String = ""
    @JvmField var auth: String = ""
    @JvmField var recv_window_conn: Int = 0
    @JvmField var recv_window: Int = 0
    @JvmField var disable_mtu_discovery: Boolean = false

    // Hysteria2
    @JvmField var password: String = ""
    @JvmField var min_packet_size: Int = 0
    @JvmField var max_packet_size: Int = 0
    @JvmField var obfs_type: String = "salamander"
    @JvmField var hop_interval_max: String = ""
    @JvmField var bbr_profile: String = ""
    @JvmField var disable_chrome_parrot: Boolean = false

    // Hysteria2 realm (NAT traversal rendezvous); replaces server/server_port/server_ports.
    @JvmField var realm_enabled: Boolean = false
    @JvmField var realm_server_url: String = ""
    @JvmField var realm_token: String = ""
    @JvmField var realm_id: String = ""
    @JvmField var realm_stun_servers: MutableList<String> = ArrayList()
    @JvmField var realm_ip_version: Int = 0
    @JvmField var realm_port_mapping: Boolean = false
    @JvmField var realm_port_mapping_timeout: String = ""
    @JvmField var realm_port_mapping_lifetime: String = ""
    @JvmField var realm_http_client: JsonObject = JsonObject()

    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** hysteria.h:52-55: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    /** hysteria.h:57-59. */
    fun realmActive(): Boolean = realm_enabled && protocol_version == "2"

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    /** hysteria.cpp:55-123. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) {
            if (!url.invalidPortOnly) return false
            // hysteria.cpp:62-67: the port list is whatever follows QUrl::authority() in the link text; the first
            // occurrence is searched, so a recoded user-info, a normalised host or a host that also occurs in the
            // scheme make the desktop silently drop the list (reproduced as is)
            val authority = prettyAuthority(link, url)
            val portStartIndex = link.indexOf(authority) + authority.length
            val match = if (portStartIndex <= link.length) PORT_LIST.find(link.substring(portStartIndex)) else null
            if (match != null) server_ports = portsToPorts(QtStrings.split(match.groupValues[1], ","))
        }
        val q = url.query
        super.parseFromLink(url)
        if (url.scheme == "hysteria") {
            protocol_version = "1"
            if (q.has("obfsParam")) obfs = q.valueFully("obfsParam")
            if (q.has("auth")) {
                auth = q.value("auth")
                auth_type = "STRING"
            }
            if (q.has("recv_window_conn")) recv_window_conn = QtStrings.toInt(q.value("recv_window_conn"))
            if (q.has("recv_window")) recv_window = QtStrings.toInt(q.value("recv_window"))
            if (q.has("disable_mtu_discovery")) disable_mtu_discovery = q.value("disable_mtu_discovery") == "true"
        } else {
            protocol_version = "2"
            password = if (url.password.isEmpty()) url.userName else url.userName + ":" + url.password
            if (q.has("obfs-password")) obfs = q.valueFully("obfs-password")
            obfs_type = when {
                q.has("obfs") -> q.value("obfs")
                q.has("min_packet_size") || q.has("max_packet_size") -> "gecko"
                else -> "salamander"
            }
            if (q.has("min_packet_size")) min_packet_size = QtStrings.toInt(q.value("min_packet_size"))
            if (q.has("max_packet_size")) max_packet_size = QtStrings.toInt(q.value("max_packet_size"))
            if (q.has("hop_interval_max")) hop_interval_max = q.value("hop_interval_max")
            if (q.has("bbr_profile")) bbr_profile = q.value("bbr_profile")
            if (q.has("disable_chrome_parrot")) disable_chrome_parrot = q.value("disable_chrome_parrot") == "true"
        }
        if (q.has("upmbps")) up_mbps = QtStrings.toInt(q.value("upmbps"))
        if (q.has("downmbps")) down_mbps = QtStrings.toInt(q.value("downmbps"))
        if (q.has("hop_interval")) hop_interval = q.value("hop_interval")
        if (q.has("mport")) server_ports = portsToPorts(QtStrings.split(q.valueFully("mport"), ","))
        tls.parseFromLink(url)
        tls.enabled = true
        quic.parseFromLink(url)
        if (serverPort == 0 && server_ports.isEmpty()) serverPort = 443
        return true
    }

    /**
     * hysteria.cpp:191-262: `hysteria` is v1 and `hysteria2` v2; bandwidth strings accept the mihomo units. The
     * desktop stores `recv-window-conn` into recv_window (a slip); it lands in recv_window_conn here.
     */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        protocol_version = when (proxy.type) {
            "hysteria" -> "1"
            "hysteria2" -> "2"
            else -> return false
        }
        baseParseFromClash(proxy)
        val ports = proxy.string("ports")
        if (ports.isNotEmpty()) server_ports = portsToPorts(ports.split(CLASH_PORT_SEPARATOR).filter { it.isNotEmpty() })
        val up = proxy.string("up")
        if (up.isNotEmpty()) up_mbps = anyToMbps(up)
        val down = proxy.string("down")
        if (down.isNotEmpty()) down_mbps = anyToMbps(down)
        if (protocol_version == "1") {
            val authString = proxy.string("auth-str").ifEmpty { proxy.string("auth_str") }
            if (authString.isNotEmpty()) {
                auth = authString
                auth_type = "STRING"
            }
            val clashObfs = proxy.string("obfs")
            if (clashObfs.isNotEmpty()) obfs = clashObfs
            val window = proxy.int("recv-window").takeIf { it > 0 } ?: proxy.int("recv_window")
            if (window > 0) recv_window = window
            val windowConn = proxy.int("recv-window-conn").takeIf { it > 0 } ?: proxy.int("recv_window_conn")
            if (windowConn > 0) recv_window_conn = windowConn
            disable_mtu_discovery = proxy.bool("disable_mtu_discovery")
        } else {
            val clashPassword = proxy.string("password")
            if (clashPassword.isNotEmpty()) password = clashPassword
            val obfsPassword = proxy.string("obfs-password")
            if (obfsPassword.isNotEmpty()) obfs = obfsPassword
        }
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /** hysteria.cpp:125-189: `type` picks the version (`hysteria` / `hysteria2`); the QUIC keys are flat. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        protocol_version = when (obj.string("type")) {
            "hysteria" -> "1"
            "hysteria2" -> "2"
            else -> return false
        }
        super.parseFromJson(obj)
        if (obj.contains("server_ports")) server_ports = obj.array("server_ports").strings()
        if (obj.contains("hop_interval")) hop_interval = obj.string("hop_interval")
        if (obj.contains("up_mbps")) up_mbps = obj.int("up_mbps")
        if (obj.contains("down_mbps")) down_mbps = obj.int("down_mbps")
        if (protocol_version == "1") {
            if (obj.contains("obfs")) obfs = obj.string("obfs")
            if (obj.contains("auth")) {
                auth = obj.string("auth")
                auth_type = "BASE64"
            }
            if (obj.contains("auth_str")) {
                auth = obj.string("auth_str")
                auth_type = "STRING"
            }
            if (obj.contains("recv_window_conn")) recv_window_conn = obj.int("recv_window_conn")
            if (obj.contains("recv_window")) recv_window = obj.int("recv_window")
            if (obj.contains("disable_mtu_discovery")) disable_mtu_discovery = obj.bool("disable_mtu_discovery")
        } else {
            if (obj.contains("obfs")) {
                val obfsObj = obj.obj("obfs")
                if (obfsObj.contains("password")) obfs = obfsObj.string("password")
                obfs_type = if (obfsObj.contains("type")) obfsObj.string("type") else "salamander"
                if (obfsObj.contains("min_packet_size")) min_packet_size = obfsObj.int("min_packet_size")
                if (obfsObj.contains("max_packet_size")) max_packet_size = obfsObj.int("max_packet_size")
            }
            if (obj.contains("obfsPassword")) obfs = obj.string("obfsPassword")
            if (obj.contains("password")) password = obj.string("password")
            if (obj.contains("hop_interval_max")) hop_interval_max = obj.string("hop_interval_max")
            if (obj.contains("bbr_profile")) bbr_profile = obj.string("bbr_profile")
            if (obj.contains("disable_chrome_parrot")) disable_chrome_parrot = obj.bool("disable_chrome_parrot")
            if (obj.isObject("realm")) {
                val realmObj = obj.obj("realm")
                realm_enabled = true
                realm_server_url = realmObj.string("server_url")
                realm_token = realmObj.string("token")
                realm_id = realmObj.string("realm_id")
                // Listable: the core accepts a bare string as well as an array.
                if (realmObj.isArray("stun_servers")) realm_stun_servers = realmObj.array("stun_servers").strings()
                else if (realmObj.contains("stun_servers")) realm_stun_servers = mutableListOf(realmObj.string("stun_servers"))
                realm_ip_version = realmObj.int("ip_version")
                val portMapping = realmObj.obj("port_mapping")
                realm_port_mapping = portMapping.bool("enabled")
                realm_port_mapping_timeout = portMapping.string("timeout")
                realm_port_mapping_lifetime = portMapping.string("lifetime")
                realm_http_client = realmObj.obj("http_client").copy()
            }
        }
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        quic.parseFromJson(obj)
        return true
    }

    /** hysteria.cpp:264-331: no link form carries realm; a port list replaces the port right after the authority. */
    override fun exportToLink(): String {
        if (realmActive()) return ""
        val v1 = protocol_version == "1"
        val url = LinkBuilder(if (v1) "hysteria" else "hysteria2")
        url.host = server
        if (name.isNotEmpty()) url.fragment = name
        var linkUser: String? = null
        var linkPassword: String? = null
        if (v1) {
            if (obfs.isNotEmpty()) url.addQueryItemPercentEncoded("obfsParam", LinkCodec.percentEncodeAll(obfs))
            if (auth_type == "STRING" && auth.isNotEmpty()) url.addQueryItem("auth", auth)
            if (recv_window_conn > 0) url.addQueryItem("recv_window_conn", recv_window_conn.toString())
            if (recv_window > 0) url.addQueryItem("recv_window", recv_window.toString())
            if (disable_mtu_discovery) url.addQueryItem("disable_mtu_discovery", "true")
        } else {
            if (password.contains(":")) {
                linkUser = QtStrings.substrBefore(password, ":")
                linkPassword = QtStrings.substrAfter(password, ":")
                url.setUserName(linkUser)
                url.setPassword(linkPassword)
            } else if (password.isNotEmpty()) {
                // a profile from a link or JSON without a password holds a null QString, which adds no user-info
                linkUser = password
                url.setUserName(password)
            }
            if (obfs.isNotEmpty()) {
                url.addQueryItemPercentEncoded("obfs", LinkCodec.percentEncodeAll(obfs_type))
                url.addQueryItemPercentEncoded("obfs-password", LinkCodec.percentEncodeAll(obfs))
                if (min_packet_size > 0) url.addQueryItem("min_packet_size", min_packet_size.toString())
                if (max_packet_size > 0) url.addQueryItem("max_packet_size", max_packet_size.toString())
            }
            if (hop_interval_max.isNotEmpty()) url.addQueryItem("hop_interval_max", hop_interval_max)
            if (bbr_profile.isNotEmpty()) url.addQueryItem("bbr_profile", bbr_profile)
            if (disable_chrome_parrot) url.addQueryItem("disable_chrome_parrot", "true")
        }
        if (up_mbps > 0) url.addQueryItem("upmbps", up_mbps.toString())
        if (down_mbps > 0) url.addQueryItem("downmbps", down_mbps.toString())
        if (hop_interval.isNotEmpty()) url.addQueryItem("hop_interval", hop_interval)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(quic.exportToLink())
        url.addQueryItems(baseLinkQuery())
        if (server_ports.isEmpty()) {
            url.port = serverPort
            return url.build()
        }
        // hysteria.cpp:314-325: serialised without a port, then ":" + list inserted after the first occurrence of the
        // FullyEncoded authority (QString::insert past the end pads with spaces)
        var result = url.build()
        val authority = fullyEncodedAuthority(linkUser, linkPassword)
        val portStartIndex = result.indexOf(authority) + authority.length
        if (portStartIndex > result.length) result = result.padEnd(portStartIndex, ' ')
        val portList = server_ports.joinToString(",") { it.replace(":", "-") }
        return result.substring(0, portStartIndex) + ":" + portList + result.substring(portStartIndex)
    }

    /** hysteria.cpp:333-369: `tls` is always written, v2 obfs always carries both packet sizes, realm replaces the server keys. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = if (protocol_version == "1") "hysteria" else "hysteria2"
        obj.merge(baseExportToJson())
        if (server_ports.isNotEmpty()) obj["server_ports"] = JsonValues.stringArray(portsToPorts(server_ports))
        if (hop_interval.isNotEmpty()) obj["hop_interval"] = hop_interval
        if (up_mbps > 0) obj["up_mbps"] = up_mbps
        if (down_mbps > 0) obj["down_mbps"] = down_mbps
        if (protocol_version == "1") {
            if (obfs.isNotEmpty()) obj["obfs"] = obfs
            if (auth.isNotEmpty()) {
                if (auth_type == "BASE64") obj["auth"] = auth
                if (auth_type == "STRING") obj["auth_str"] = auth
            }
            if (recv_window_conn > 0) obj["recv_window_conn"] = recv_window_conn
            if (recv_window > 0) obj["recv_window"] = recv_window
            if (disable_mtu_discovery) obj["disable_mtu_discovery"] = disable_mtu_discovery
        } else {
            if (obfs.isNotEmpty()) {
                obj["obfs"] = jsonObjectOf(
                    "type" to obfs_type,
                    "password" to obfs,
                    "min_packet_size" to min_packet_size,
                    "max_packet_size" to max_packet_size,
                )
            }
            if (password.isNotEmpty()) obj["password"] = password
            if (hop_interval_max.isNotEmpty()) obj["hop_interval_max"] = hop_interval_max
            if (bbr_profile.isNotEmpty()) obj["bbr_profile"] = bbr_profile
            if (disable_chrome_parrot) obj["disable_chrome_parrot"] = true
            if (realmActive()) applyRealmObject(obj)
        }
        obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** hysteria.cpp:371-389. */
    override fun exportIdentity(): JsonObject {
        if (realmActive()) {
            // outbound::ExportIdentity() falls back to the whole config when there is no server.
            val obj = jsonObjectOf(
                "protocol_version" to protocol_version,
                "realm_server_url" to realm_server_url,
                "realm_id" to realm_id,
            )
            val t = tls.exportIdentity()
            if (t.isNotEmpty()) obj["tls"] = t
            return obj
        }
        val obj = super.exportIdentity()
        obj["protocol_version"] = protocol_version
        if (server_ports.isNotEmpty()) obj["server_ports"] = server_ports.joinToString(",")
        if (obfs.isNotEmpty()) obj["obfs"] = true
        return obj
    }

    /** hysteria.cpp:391-443. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = if (protocol_version == "1") "hysteria" else "hysteria2"
        obj.merge(baseBuild(ctx))
        if (server_ports.isNotEmpty()) obj["server_ports"] = JsonValues.stringArray(portsToPorts(server_ports))
        if (hop_interval.isNotEmpty()) obj["hop_interval"] = hop_interval
        if (up_mbps > 0) obj["up_mbps"] = up_mbps
        if (down_mbps > 0) obj["down_mbps"] = down_mbps
        if (protocol_version == "1") {
            if (obfs.isNotEmpty()) obj["obfs"] = obfs
            if (auth.isNotEmpty()) {
                if (auth_type == "BASE64") obj["auth"] = auth
                if (auth_type == "STRING") obj["auth_str"] = auth
            }
            if (recv_window_conn > 0) obj["recv_window_conn"] = recv_window_conn
            if (recv_window > 0) obj["recv_window"] = recv_window
            if (disable_mtu_discovery) obj["disable_mtu_discovery"] = disable_mtu_discovery
        } else {
            if (obfs.isNotEmpty()) {
                obj["obfs"] = when (obfs_type) {
                    "salamander" -> jsonObjectOf("type" to obfs_type, "password" to obfs)
                    "gecko" -> jsonObjectOf(
                        "type" to obfs_type,
                        "password" to obfs,
                        "min_packet_size" to min_packet_size,
                        "max_packet_size" to max_packet_size,
                    )
                    else -> jsonObjectOf("type" to "salamander", "password" to obfs)
                }
            }
            if (password.isNotEmpty()) obj["password"] = password
            // sing-box randomizes the hop interval within [hop_interval, hop_interval_max]; it rejects a max without a min.
            if (hop_interval_max.isNotEmpty() && hop_interval.isNotEmpty()) obj["hop_interval_max"] = hop_interval_max
            // An unknown profile makes sing-box refuse to start, so drop anything a subscription made up.
            if (bbr_profile in hysteriaBBRProfiles) obj["bbr_profile"] = bbr_profile
            if (disable_chrome_parrot) obj["disable_chrome_parrot"] = true
            if (realmActive()) applyRealmObject(obj)
        }
        obj["tls"] = tls.build(ctx)
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** hysteria.cpp:445-451: QUrl(realm_server_url).host(), the whole string when that is empty. */
    override fun displayAddress(): String {
        if (!realmActive()) return super.displayAddress()
        var host = prettyHost(LinkParser.parse(realm_server_url).host, bracketed = false)
        if (host.isEmpty()) host = realm_server_url
        return if (realm_id.isEmpty()) host else "$realm_id@$host"
    }

    /** hysteria.cpp:453-456. */
    override fun displayType(): String = if (protocol_version == "2") "Hysteria 2" else "Hysteria"

    /** hysteria.cpp:458-461. */
    override fun security(): SecurityInfo = securityFromTls("QUIC")

    /** hysteria.cpp:26-44: the core only checks realm's required fields when it first dials, so they are emitted unconditionally. */
    private fun buildRealmObject(): JsonObject {
        val realm = JsonObject()
        realm["server_url"] = realm_server_url
        if (realm_token.isNotEmpty()) realm["token"] = realm_token
        realm["realm_id"] = realm_id
        if (realm_stun_servers.isNotEmpty()) realm["stun_servers"] = JsonValues.stringArray(realm_stun_servers)
        if (realm_ip_version == 4 || realm_ip_version == 6) realm["ip_version"] = realm_ip_version
        // Hole punching over IPv6 has no gateway mapping to make; the core rejects the pair.
        if (realm_port_mapping && realm_ip_version != 6) {
            val portMapping = jsonObjectOf("enabled" to true)
            if (realm_port_mapping_timeout.isNotEmpty()) portMapping["timeout"] = realm_port_mapping_timeout
            if (realm_port_mapping_lifetime.isNotEmpty()) portMapping["lifetime"] = realm_port_mapping_lifetime
            realm["port_mapping"] = portMapping
        }
        if (realm_http_client.isNotEmpty()) realm["http_client"] = realm_http_client.copy()
        return realm
    }

    /** hysteria.cpp:46-53: realm replaces the server address; the core refuses a config carrying both. */
    private fun applyRealmObject(obj: JsonObject) {
        obj.remove("server")
        obj.remove("server_port")
        obj.remove("server_ports")
        obj["realm"] = buildRealmObject()
    }

    /** QUrl::authority(QUrl::FullyEncoded) of the export URL, which has no port. */
    private fun fullyEncodedAuthority(linkUser: String?, linkPassword: String?): String {
        val sb = StringBuilder()
        if (linkUser != null || linkPassword != null) {
            sb.append(LinkCodec.encodeUserName(linkUser ?: ""))
            if (linkPassword != null) sb.append(':').append(LinkCodec.encodePassword(linkPassword))
            sb.append('@')
        }
        sb.append(Hosts.formatForUrl(Hosts.normalizeHost(server, percentDecode = false) ?: ""))
        return sb.toString()
    }

    companion object {
        private val PORT_LIST = Regex("^:([\\d,\\-]+)")
        private val PORT_RANGE_SEPARATOR = Regex("[:-]")
        private const val HEX = "0123456789ABCDEF"

        // Escapes QUrl::authority() prints decoded (PrettyDecoded user-info): unreserved, space, the unsafe set except
        // the backtick, and the gen-delims that cannot occur unescaped in an authority. Everything else, in particular
        // ':' '@' '[' ']' '%' '`', the sub-delims and control characters, stays percent-encoded (upper-case hex).
        private const val DECODED_IN_AUTHORITY = " -._~\"<>\\^{|}/?#"

        private val CLASH_PORT_SEPARATOR = Regex("[,/]")
        private val MBPS = Regex("^(\\d+)([KMGT]?)([Bb]?)")

        /** hysteria.cpp:203-231: a plain number is Mbps; otherwise `<n>[K|M|G|T][B|b]`, bytes count eightfold. */
        @JvmStatic
        fun anyToMbps(text: String): Int {
            if (text.isEmpty()) return 0
            text.trim().toIntOrNull()?.let { return it }
            val match = MBPS.find(text) ?: return 0
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0
            var n = when (match.groupValues[2].uppercase()) {
                "K" -> 0.001
                "M" -> 1.0
                "G" -> 1000.0
                "T" -> 1000000.0
                else -> 1.0
            }
            if (match.groupValues[3].uppercase() == "B") n *= 8.0
            return (value * n).toInt()
        }

        /** portsToPorts (hysteria.cpp:10-24): "a-b" and "a:b" become "a:b", anything else "v:v". */
        @JvmStatic
        fun portsToPorts(ports: List<String>): MutableList<String> {
            val result = ArrayList<String>(ports.size)
            for (v in ports) {
                val range = v.split(PORT_RANGE_SEPARATOR)
                result.add(if (range.size == 2) "${range[0]}:${range[1]}" else "$v:$v")
            }
            return result
        }

        /**
         * QUrl::authority() of a link whose only error is the port: the user-info as Qt re-prints it inside an authority,
         * '@' whenever user-info was present, and the host in its Unicode form; no port.
         */
        private fun prettyAuthority(link: String, url: ParsedLink): String {
            val hierStart = if (url.scheme.isNotEmpty()) url.scheme.length + 1 else 0
            val sb = StringBuilder()
            if (link.startsWith("//", hierStart)) {
                var end = link.length
                for (i in hierStart + 2 until link.length) {
                    val c = link[i]
                    if (c == '/' || c == '?' || c == '#') {
                        end = i
                        break
                    }
                }
                val auth = link.substring(hierStart + 2, end)
                val at = auth.indexOf('@')
                if (at >= 0) {
                    val userInfo = auth.substring(0, at)
                    val delim = userInfo.indexOf(':')
                    sb.append(recodeUserInfo(if (delim >= 0) userInfo.substring(0, delim) else userInfo, encodeColon = true))
                    if (delim >= 0) sb.append(':').append(recodeUserInfo(userInfo.substring(delim + 1), encodeColon = false))
                    sb.append('@')
                }
            }
            sb.append(prettyHost(url.host, bracketed = true))
            return sb.toString()
        }

        private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

        private fun escapeByte(s: String, i: Int): Int {
            if (i + 2 >= s.length || s[i] != '%') return -1
            val h = Character.digit(s[i + 1], 16)
            val l = Character.digit(s[i + 2], 16)
            return if (h < 0 || l < 0) -1 else (h shl 4) or l
        }

        private fun appendEscape(sb: StringBuilder, byte: Int) {
            sb.append('%').append(HEX[(byte shr 4) and 0xF]).append(HEX[byte and 0xF])
        }

        // One raw user-info component (TolerantMode) to its PrettyDecoded-in-authority form; the ':' delimiter is
        // encoded in the user name only (userNameInAuthority vs passwordInAuthority in qurl.cpp)
        private fun recodeUserInfo(raw: String, encodeColon: Boolean): String {
            val s = LinkCodec.tolerant(raw)
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val b = escapeByte(s, i)
                if (b < 0) {
                    if (c == '[' || c == ']' || c == '`' || c.code < 0x20 || c.code == 0x7F || (encodeColon && c == ':')) appendEscape(sb, c.code)
                    else sb.append(c)
                    i++
                    continue
                }
                if (b < 0x80) {
                    val ch = b.toChar()
                    if (isAlnum(ch) || DECODED_IN_AUTHORITY.indexOf(ch) >= 0) sb.append(ch) else appendEscape(sb, b)
                    i += 3
                    continue
                }
                val consumed = decodeUtf8Escapes(s, i, b, sb)
                if (consumed > 0) {
                    i += consumed
                } else {
                    appendEscape(sb, b)
                    i += 3
                }
            }
            return sb.toString()
        }

        // A run of consecutive escapes forming one strictly valid UTF-8 sequence is decoded; returns the characters consumed
        private fun decodeUtf8Escapes(s: String, start: Int, lead: Int, sb: StringBuilder): Int {
            val need = when (lead) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> return 0
            }
            val bytes = ByteArray(need)
            for (k in 0 until need) {
                val b = escapeByte(s, start + 3 * k)
                if (b < 0) return 0
                bytes[k] = b.toByte()
            }
            return try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                sb.append(decoder.decode(ByteBuffer.wrap(bytes)))
                3 * need
            } catch (e: CharacterCodingException) {
                0
            }
        }

        /**
         * The host as QUrl::host() / QUrl::authority() print it (PrettyDecoded): reg-names in Unicode (ACE labels
         * decoded), an IPv6 literal with its brackets inside an authority and its zone id decoded outside of one.
         */
        private fun prettyHost(hostFullyEncoded: String, bracketed: Boolean): String {
            if (hostFullyEncoded.contains(':')) {
                return if (bracketed) "[$hostFullyEncoded]" else hostFullyEncoded.replace("%25", "%")
            }
            if (!hostFullyEncoded.contains("xn--")) return hostFullyEncoded
            return hostFullyEncoded.split('.').joinToString(".") { label ->
                if (label.startsWith("xn--")) punycodeDecode(label.substring(4)) ?: label else label
            }
        }

        // RFC 3492 section 6.2; null for input the algorithm rejects, in which case Qt keeps the ACE label
        private fun punycodeDecode(input: String): String? {
            val output = ArrayList<Int>()
            var n = 128
            var i = 0
            var bias = 72
            val delimiter = input.lastIndexOf('-')
            val basicEnd = if (delimiter > 0) delimiter else 0
            for (j in 0 until basicEnd) {
                val c = input[j]
                if (c.code >= 0x80) return null
                output.add(c.code)
            }
            var pos = if (delimiter > 0) delimiter + 1 else 0
            while (pos < input.length) {
                val oldI = i
                var w = 1
                var k = 36
                while (true) {
                    if (pos >= input.length) return null
                    val c = input[pos++]
                    val digit = when (c) {
                        in 'a'..'z' -> c - 'a'
                        in 'A'..'Z' -> c - 'A'
                        in '0'..'9' -> c - '0' + 26
                        else -> return null
                    }
                    if (digit > (Int.MAX_VALUE - i) / w) return null
                    i += digit * w
                    val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
                    if (digit < t) break
                    if (w > Int.MAX_VALUE / (36 - t)) return null
                    w *= 36 - t
                    k += 36
                }
                val count = output.size + 1
                bias = punycodeAdapt(i - oldI, count, oldI == 0)
                if (i / count > Int.MAX_VALUE - n) return null
                n += i / count
                i %= count
                if (n > 0x10FFFF || (n in 0xD800..0xDFFF)) return null
                output.add(i, n)
                i++
            }
            val sb = StringBuilder()
            for (cp in output) sb.appendCodePoint(cp)
            return sb.toString()
        }

        private fun punycodeAdapt(delta0: Int, numPoints: Int, firstTime: Boolean): Int {
            var delta = if (firstTime) delta0 / 700 else delta0 / 2
            delta += delta / numPoints
            var k = 0
            while (delta > ((36 - 1) * 26) / 2) {
                delta /= 36 - 1
                k += 36
            }
            return k + (36 - 1 + 1) * delta / (delta + 38)
        }
    }
}
