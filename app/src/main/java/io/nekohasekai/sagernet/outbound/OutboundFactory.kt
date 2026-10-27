package app.qthrone.mobile.outbound

import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.LinkParser
import app.qthrone.mobile.outbound.types.DnsTunnel
import app.qthrone.mobile.outbound.types.Http
import app.qthrone.mobile.outbound.types.S3Bypass
import app.qthrone.mobile.outbound.types.Sstp
import app.qthrone.mobile.outbound.types.WdttPlus
import app.qthrone.mobile.outbound.types.Shadowsocks
import app.qthrone.mobile.outbound.types.Socks
import java.util.concurrent.ConcurrentHashMap

/**
 * NewOutboundByType (src/configs/common/OutboundFactory.cpp:33-65), the link scheme table and link dispatch of
 * src/configs/sub/SubscriptionParser.cpp:48-67 / 510-559, and useXrayVless (src/configs/common/utils.cpp:50-72).
 *
 * Extension point for the per-type work packages: a new type lives in one file,
 * `app.qthrone.mobile.outbound.types.<ClassName>` with a public no-argument constructor, where ClassName is
 * the entry of [CONVENTION_CLASSES] for its factory string. [newByType] finds it by name, so the factory itself
 * never changes; [register] can override any entry explicitly (an app initialiser may do that to avoid reflection
 * once every type exists). Release builds shrink with R8, so a keep rule for that package is required.
 */
object OutboundFactory {

    /** Const.hpp:70-74 Xray::XrayVlessPreference; the default is SettingsRepo.h:303. */
    enum class XrayVlessPreference { XhttpOnly, XhttpAndReality, AllVLESS }

    @JvmField
    val DEFAULT_XRAY_VLESS_PREFERENCE: XrayVlessPreference = XrayVlessPreference.XhttpAndReality

    /** Every factory string of OutboundFactory.cpp:35-61 (R2 Р В РІР‚в„ўР вЂ™Р’В§A). */
    @JvmField
    val ALL_TYPES: List<String> = listOf(
        "qwdtt", "csqtt", "wdttplus", "openflux", "openfluxkey", "olcrtc", "dnstt", "vaydns", "slipstream", "masterdns", "cottendns", "soar", "s3bypass", "sstp", "socks", "http", "shadowsocks", "chain", "autoselector", "vmess", "trojan", "vless", "xrayvless", "hysteria",
        "tuic", "juicity", "trusttunnel", "anytls", "mieru", "snell", "shadowtls", "wireguard", "masque", "openvpn",
        "openconnect", "tailscale", "ssh", "custom", "extracore", "naive", "direct",
    )

    /** OutboundFactory.cpp:44 and the JSON alias of openvpn.cpp:157-158. */
    private val TYPE_ALIASES = mapOf("wdtt" to "qwdtt", "hysteria2" to "hysteria", "openvpn-client" to "openvpn")

    /** Not ported (tailscale) or desktop-only (extracore); always an [InvalidOutbound]. */
    @JvmField
    val UNSUPPORTED_TYPES: Set<String> = setOf("tailscale", "extracore")

    /** Factory string to the class expected in app.qthrone.mobile.outbound.types for the types other packages add. */
    @JvmField
    val CONVENTION_CLASSES: Map<String, String> = mapOf(
        "vmess" to "Vmess", "trojan" to "Trojan", "vless" to "Vless", "xrayvless" to "XrayVless",
        "hysteria" to "Hysteria", "tuic" to "Tuic", "juicity" to "Juicity", "trusttunnel" to "TrustTunnel",
        "anytls" to "AnyTls", "mieru" to "Mieru", "snell" to "Snell", "shadowtls" to "ShadowTls",
        "wireguard" to "WireGuard", "masque" to "Masque", "openvpn" to "OpenVpn", "openconnect" to "OpenConnect",
        "ssh" to "Ssh", "custom" to "Custom", "naive" to "Naive", "direct" to "Direct", "chain" to "Chain",
        "autoselector" to "AutoSelector", "qwdtt" to "Qwdtt", "csqtt" to "Csqtt", "openflux" to "OpenFlux", "olcrtc" to "OlcRtc", "sstp" to "Sstp",
    )

    private const val TYPES_PACKAGE = "app.qthrone.mobile.outbound.types."

    private val builtin: Map<String, () -> Outbound> = mapOf<String, () -> Outbound>(
        "socks" to { Socks() },
        "http" to { Http() },
        "shadowsocks" to { Shadowsocks() },
        "dnstt" to { DnsTunnel("dnstt") },
        "vaydns" to { DnsTunnel("vaydns") },
        "slipstream" to { DnsTunnel("slipstream") },
        "masterdns" to { DnsTunnel("masterdns") },
        "cottendns" to { DnsTunnel("cottendns") },
        "soar" to { DnsTunnel("soar") },
        "s3bypass" to { S3Bypass() },
        "openfluxkey" to { OpenFluxKey() },
        "sstp" to { Sstp() },
        "wdttplus" to { WdttPlus() },
    )

    private val registry = ConcurrentHashMap<String, () -> Outbound>()

    @JvmStatic
    fun register(type: String, constructor: () -> Outbound) {
        registry[canonicalType(type)] = constructor
    }

    @JvmStatic
    fun canonicalType(type: String): String = TYPE_ALIASES[type] ?: type

    /** NewOutboundByType: an [InvalidOutbound] (invalid=true) for unknown, unsupported or not yet ported types. */
    @JvmStatic
    fun newByType(type: String): Outbound {
        val canonical = canonicalType(type)
        registry[canonical]?.let { return it() }
        builtin[canonical]?.let { return it() }
        if (canonical !in UNSUPPORTED_TYPES) {
            CONVENTION_CLASSES[canonical]?.let { className -> instantiateByName(className)?.let { return it } }
        }
        return InvalidOutbound(canonical)
    }

    private fun instantiateByName(className: String): Outbound? = try {
        Class.forName(TYPES_PACKAGE + className).getDeclaredConstructor().newInstance() as? Outbound
    } catch (e: Throwable) {
        null
    }

    /** One row of kProtocols (SubscriptionParser.cpp:41-67). */
    class Protocol(
        @JvmField val type: String,
        @JvmField val schemes: List<String>,
        @JvmField val singbox: List<String>,
        @JvmField val clash: List<String>,
    )

    @JvmField
    val PROTOCOLS: List<Protocol> = listOf(
        // The wlruscfd/openflux-server fork's links, before the original's openflux://v1/.
        Protocol("openfluxkey", listOf("openflux://import"), listOf("openfluxkey"), emptyList()),
        Protocol("openflux", listOf("openflux://"), listOf("openflux"), emptyList()),
        Protocol("olcrtc", listOf("olcrtc://"), listOf("olcrtc"), emptyList()),
        // One model reads every DNS tunnel link and takes its transport from it.
        Protocol("dnstt", listOf("dnst://", "slipnet://", "masterdns://", "stormdns://", "cottendns://", "soar://"), listOf("dnstt"), emptyList()),
        Protocol("vaydns", emptyList(), listOf("vaydns"), emptyList()),
        Protocol("slipstream", emptyList(), listOf("slipstream"), emptyList()),
        Protocol("masterdns", emptyList(), listOf("masterdns"), emptyList()),
        Protocol("cottendns", emptyList(), listOf("cottendns"), emptyList()),
        Protocol("soar", emptyList(), listOf("soar"), emptyList()),
        Protocol("s3bypass", emptyList(), listOf("s3bypass"), emptyList()),
        Protocol("sstp", listOf("sstp://"), listOf("sstp"), emptyList()),
        Protocol("csqtt", listOf("csqtt://"), listOf("csqtt"), emptyList()),
        // wdtt://connect?v=1 is WDTT Plus; the older wdtt://host:dtls:wg:local:pass form is qWDTT, as before 1.9.0.
        Protocol("wdttplus", listOf("wdtt://connect"), listOf("wdttplus"), emptyList()),
        Protocol("qwdtt", listOf("qwdtt://", "qwdtt:config", "wdtt://"), listOf("qwdtt"), emptyList()),
        Protocol("socks", listOf("socks5://", "socks4://", "socks4a://", "socks://"), listOf("socks"), listOf("socks5")),
        Protocol("http", listOf("http://", "https://"), listOf("http"), listOf("http")),
        Protocol("shadowsocks", listOf("ss://"), listOf("shadowsocks"), listOf("ss")),
        Protocol("vmess", listOf("vmess://"), listOf("vmess"), listOf("vmess")),
        Protocol("vless", listOf("vless://"), listOf("vless"), listOf("vless")),
        Protocol("trojan", listOf("trojan://"), listOf("trojan"), listOf("trojan")),
        Protocol("anytls", listOf("anytls://"), listOf("anytls"), listOf("anytls")),
        Protocol("mieru", listOf("mierus://", "mieru://"), listOf("mieru"), emptyList()),
        Protocol("snell", listOf("snell://"), listOf("snell"), listOf("snell")),
        Protocol("hysteria", listOf("hysteria://", "hysteria2://", "hy2://"), listOf("hysteria", "hysteria2"), listOf("hysteria", "hysteria2")),
        Protocol("tuic", listOf("tuic://"), listOf("tuic"), listOf("tuic")),
        Protocol("juicity", listOf("juicity://"), listOf("juicity"), emptyList()),
        Protocol("trusttunnel", listOf("tt://"), listOf("trusttunnel"), emptyList()),
        Protocol("shadowtls", listOf("shadowtls://"), listOf("shadowtls"), emptyList()),
        Protocol("wireguard", listOf("awg://", "wg://", "wireguard://"), listOf("wireguard"), listOf("wireguard")),
        Protocol("masque", listOf("masque://"), listOf("masque"), listOf("masque")),
        Protocol("ssh", listOf("ssh://"), listOf("ssh"), listOf("ssh")),
        Protocol("naive", listOf("naive+https://", "naive+quic://"), listOf("naive"), emptyList()),
    )

    /** typeForScheme (SubscriptionParser.cpp:69-76): case-sensitive prefix match. */
    @JvmStatic
    fun typeForScheme(link: String): String? {
        // The desktop reads qWDTT and CSQTT schemes in any letter case.
        for (p in PROTOCOLS) for (scheme in p.schemes)
            if (link.startsWith(scheme, ignoreCase = p.type in listOf("qwdtt", "csqtt", "wdttplus", "sstp"))) return p.type
        return null
    }

    /** typeForSingBox (SubscriptionParser.cpp:78-85): the factory string for a sing-box outbound `type`. */
    @JvmStatic
    fun typeForSingBox(type: String): String? {
        for (p in PROTOCOLS) for (name in p.singbox) if (type == name) return p.type
        return null
    }

    /** typeForClash (SubscriptionParser.cpp:87-94): the factory string for a Clash proxy `type`. */
    @JvmStatic
    fun typeForClash(type: String): String? {
        for (p in PROTOCOLS) for (name in p.clash) if (type == name) return p.type
        return null
    }

    /** useXrayVless (utils.cpp:50-72): whether a vless:// link needs the Xray core. */
    @JvmStatic
    @JvmOverloads
    fun useXrayVless(link: String, preference: XrayVlessPreference = DEFAULT_XRAY_VLESS_PREFERENCE): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val query = url.query
        val transport = query.value("type")
        val security = query.value("security")
        // sing-box's http transport speaks the raw HTTP header only in plaintext; TLS (which a bare sni also enables) turns it into h2
        val rawHttpOverTls = (transport.isEmpty() || transport == "tcp" || transport == "raw") &&
            query.value("headerType") == "http" &&
            ((security.isNotEmpty() && security != "none") ||
                query.value("sni").isNotEmpty() ||
                query.value("peer").isNotEmpty())
        val encryption = query.value("encryption")
        return preference == XrayVlessPreference.AllVLESS ||
            rawHttpOverTls ||
            // splithttp: XHTTP's former name (Android only, XrayStreamSetting reads it as xhttp)
            transport == "xhttp" || transport == "splithttp" ||
            query.has("fm") ||
            query.has("finalmask") ||
            // sing-box has no counterpart to verifyPeerCertByName
            query.has("vcn") ||
            (security == "reality" && preference == XrayVlessPreference.XhttpAndReality) ||
            (encryption != "none" && encryption.isNotEmpty()) ||
            query.value("extra").isNotEmpty()
    }

    /**
     * The type resolution of Parser::jsonLink (SubscriptionParser.cpp:550-559): an object with `protocol` is an
     * Xray outbound ("xray" + protocol), otherwise `type` with the hysteria2 alias. Null for an invalid type. The
     * data is parsed into the result; like the desktop, the parse result itself does not reject the profile.
     */
    @JvmStatic
    fun fromJson(data: JsonObject): Outbound? {
        val outbound = if (data.contains("protocol")) newByType("xray" + data.string("protocol"))
        else newByType(data.string("type"))
        if (outbound.invalid) return null
        outbound.parseFromJson(data)
        return outbound
    }

    /** Parser::jsonLink (SubscriptionParser.cpp:539-560): `throne://add/<base64 of ExportToJson>` or `json://#<base64url>`. */
    @JvmStatic
    fun decodeJsonLink(link: String, throneAdd: Boolean): String? {
        val url = LinkParser.parse(link)
        if (!url.isValid) return null
        var dataBytes = if (throneAdd) Base64Strict.decode(url.path.drop(1)) else Base64Strict.decode(url.fragment ?: "", urlSafe = true)
        // ExportJsonLink emits the url-safe alphabet, which the standard decoder rejects whenever '-' or '_' occurs
        if ((dataBytes == null || dataBytes.isEmpty()) && throneAdd) dataBytes = Base64Strict.decode(url.path.drop(1), urlSafe = true)
        if (dataBytes == null || dataBytes.isEmpty()) return null
        return String(dataBytes, Charsets.UTF_8)
    }

    @JvmStatic
    fun parseJsonLink(link: String, throneAdd: Boolean): Outbound? =
        decodeJsonLink(link,throneAdd)?.let { app.qthrone.mobile.outbound.`import`.ProfileImport.parse(it).outbounds.singleOrNull() }

    /**
     * Parser::link (SubscriptionParser.cpp:510-537) for one subscription line: comments and short lines are skipped,
     * json:// and throne://add/ carry JSON, vless:// goes to xrayvless when [useXrayVless] says so, everything else
     * follows the scheme table. `vpn://` (desktop credential links) is not supported. Null when nothing was produced.
     */
    @JvmStatic
    @JvmOverloads
    fun parseLink(line: String, preference: XrayVlessPreference = DEFAULT_XRAY_VLESS_PREFERENCE): Outbound? {
        if (line.startsWith("//") || line.startsWith("#") || line.length < 2) return null
        if (line.startsWith("json://")) return parseJsonLink(line, false)
        if (line.startsWith("throne://add/", ignoreCase = true)) return parseJsonLink(line, true)
        if (line.startsWith("vpn://", ignoreCase = true)) return null
        val profileType = typeForScheme(line) ?: return null
        val outbound = if (line.startsWith("vless://") && useXrayVless(line, preference)) newByType("xrayvless")
        else newByType(profileType)
        // QWDTT:// or Csqtt:// reach the model with the scheme it parses.
        val scheme = PROTOCOLS.first { it.type == profileType }.schemes.firstOrNull { line.startsWith(it, ignoreCase = true) }
        val link = if (scheme != null && profileType in listOf("qwdtt", "csqtt", "wdttplus", "sstp")) scheme + line.substring(scheme.length) else line
        if (!outbound.parseFromLink(link)) return null
        return outbound
    }
}
