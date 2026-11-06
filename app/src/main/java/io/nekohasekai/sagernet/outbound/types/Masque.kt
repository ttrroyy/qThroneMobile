package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.SecurityLevel
import app.rawline.mobile.outbound.common.QuicFields
import app.rawline.mobile.outbound.common.Tls
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues
import app.rawline.mobile.outbound.link.Hosts
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser

/**
 * masque (include/configs/outbounds/masque.h, src/configs/outbounds/masque.cpp): a sing-box endpoint.
 * Shares use masque://private-key@server:port?publickey=...&address=...&network=h2.
 */
class Masque : Outbound("masque") {
    @JvmField var private_key: String = ""
    @JvmField var peer_public_key: String = ""
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1280
    /** 0 = HTTP/3 falling back to HTTP/2, 2 = HTTP/2, 3 = HTTP/3 (masque.h:14-15). */
    @JvmField var http_version: Int = 0
    @JvmField var disable_version_fallback: Boolean = false
    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** masque.h:21-26. */
    init {
        serverPort = 443
        tls.enabled = true
        tls.server_name = "consumer-masque.cloudflareclient.com"
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    /** masque.cpp:39-63: mihomo never falls back between carriers, so `h2` pins HTTP/2 and anything else HTTP/3. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "masque") return false
        baseParseFromClash(proxy)
        if (serverPort == 0) serverPort = 443
        private_key = proxy.string("private-key")
        peer_public_key = proxy.string("public-key")
        withPrefix(proxy.string("ip"), "/32")?.let { address.add(it) }
        withPrefix(proxy.string("ipv6"), "/128")?.let { address.add(it) }
        val clashMtu = proxy.int("mtu")
        if (clashMtu > 0) mtu = clashMtu
        val sni = proxy.string("sni")
        if (sni.isNotEmpty()) tls.server_name = sni
        tls.insecure = proxy.bool("skip-cert-verify")
        tls.enabled = true
        if (proxy.string("network") == "h2") {
            http_version = 2
            disable_version_fallback = false
        } else {
            http_version = 3
            disable_version_fallback = true
        }
        return private_key.isNotEmpty()
    }

    /** masque.cpp:16-37. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "masque") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peer_public_key")) peer_public_key = obj.string("peer_public_key")
        // Listable: the core accepts a bare string as well as an array.
        if (obj.isArray("address")) address = obj.array("address").strings()
        else if (obj.contains("address")) address = mutableListOf(obj.string("address"))
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("http_version")) http_version = obj.int("http_version")
        if (obj.contains("disable_version_fallback")) disable_version_fallback = obj.bool("disable_version_fallback")
        if (obj.contains("tls")) {
            val tlsObject = obj.obj("tls")
            tls.parseFromJson(tlsObject)
            // An absent SNI means the server address (as in sing-box), not the constructor's WARP default.
            if (!tlsObject.contains("server_name")) tls.server_name = ""
        }
        tls.enabled = true
        quic.parseFromJson(obj)
        return true
    }

    override fun parseFromLink(link: String): Boolean {
        val url=LinkParser.parse(link.trim())
        if(!url.isValid || url.scheme!="masque" || url.host.isEmpty() || url.port(443) !in 1..65535 ||
            url.password.isNotEmpty() || url.path !in listOf("","/")) return false
        val q=url.query
        if(q.items.map { it.first }.distinct().size!=q.items.size) return false
        private_key=url.userName
        peer_public_key=q.valueFully("publickey")
        fun validKey(key:String)=key.length in 1..8192 && key.matches(Regex("[A-Za-z0-9+/_=-]+"))
        if(!validKey(private_key) || (peer_public_key.isNotEmpty() && !validKey(peer_public_key))) return false
        val addresses=q.valueFully("address").split(',').map { it.trim() }
        if(addresses.isEmpty() || addresses.any { raw ->
            val ip=raw.substringBefore('/')
            !Hosts.isIpAddress(ip) || (raw.contains('/') &&
                raw.substringAfter('/').toIntOrNull()?.let { it in 0..if(Hosts.isIpv6Address(ip)) 128 else 32 }!=true)
        }) return false
        address=addresses.map { withPrefix(it,if(it.contains(':')) "/128" else "/32")!! }.toMutableList()
        if(q.has("mtu")) mtu=q.valueFully("mtu").toIntOrNull()?.takeIf { it in 576..65535 } ?: return false
        http_version=if(q.has("http_version")) q.valueFully("http_version").toIntOrNull() ?: return false
            else when(q.valueFully("network")) { "" -> 0; "h2" -> 2; "h3" -> 3; else -> return false }
        if(http_version !in listOf(0,2,3)) return false
        disable_version_fallback=if(q.has("disable_version_fallback")) when(q.valueFully("disable_version_fallback")) {
            "true","1" -> true; "false","0" -> false; else -> return false
        } else http_version==3
        super.parseFromLink(url)
        serverPort=url.port(443)
        tls.parseFromLink(url);tls.enabled=true
        quic.parseFromLink(url)
        return true
    }

    override fun exportToLink(): String = LinkBuilder("masque").apply {
        host=server;port=serverPort;fragment=name.takeIf(String::isNotEmpty);plusAsEscape=true
        setUserName(private_key)
        if(peer_public_key.isNotEmpty()) addQueryItem("publickey",peer_public_key)
        addQueryItem("address",address.joinToString(","))
        addQueryItem("mtu",mtu.toString())
        addQueryItem("http_version",http_version.toString())
        addQueryItem("disable_version_fallback",disable_version_fallback.toString())
        addQueryItems(baseLinkQuery())
        addQueryItems(tls.exportToLink());addQueryItems(quic.exportToLink())
    }.build()

    /** masque.cpp:71-85. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "masque"
        obj.merge(baseExportToJson())
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (peer_public_key.isNotEmpty()) obj["peer_public_key"] = peer_public_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (http_version != 0) obj["http_version"] = http_version
        if (disable_version_fallback) obj["disable_version_fallback"] = true
        obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** masque.cpp:87-93: WARP identities share one server address; the key is what tells them apart. */
    override fun exportIdentity(): JsonObject {
        val obj = super.exportIdentity()
        obj["private_key"] = private_key
        return obj
    }

    /** masque.cpp:95-116. */
    override fun build(ctx: BuildContext): BuildResult {
        if (private_key.isEmpty()) return BuildResult(JsonObject(), "${displayTypeAndName()}: the private key is empty")
        if (address.isEmpty()) return BuildResult(JsonObject(), "${displayTypeAndName()}: no address is set")

        tls.enabled = true
        val obj = JsonObject()
        obj["type"] = "masque"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(baseBuild(ctx))
        obj["private_key"] = private_key
        if (peer_public_key.isNotEmpty()) obj["peer_public_key"] = peer_public_key
        obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (http_version != 0) obj["http_version"] = http_version
        if (disable_version_fallback) obj["disable_version_fallback"] = true
        val tlsObject = tls.build(ctx)
        tlsObject.remove("reality")
        obj["tls"] = tlsObject
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** masque.cpp:118-121. */
    override fun displayType(): String = "MASQUE"

    /** masque.cpp:123-128: the peer key pins the server certificate, which the core enforces even when insecure is set. */
    override fun security(): SecurityInfo {
        if (peer_public_key.isNotEmpty()) return SecurityInfo("TLS", "", SecurityLevel.Secure)
        return securityFromTls("")
    }

    /** masque.cpp:130-133. */
    override fun isEndpoint(): Boolean = true
}

// masque.cpp:9-14: a bare address gets the host prefix; null when empty.
private fun withPrefix(address: String, prefix: String): String? {
    val result = address.trim()
    if (result.isEmpty()) return null
    return if (result.contains('/')) result else result + prefix
}
