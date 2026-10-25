package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.SecurityInfo
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.jsonObjectOf
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** trusttunnel (include/configs/outbounds/trusttunnel.h, src/configs/outbounds/trusttunnel.cpp). */
class TrustTunnel : Outbound("trusttunnel") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var congestion_control: String = ""
    @JvmField var custom_sni: String = ""
    @JvmField var client_random: String = ""
    @JvmField var health_check: Boolean = false
    @JvmField var quic: Boolean = false
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /**
     * trusttunnel.cpp:51-105: the official deep link `tt://?<base64url TLV payload>` (DEEP_LINK.md in
     * TrustTunnel/TrustTunnel). Tags: 0x00 version (at most 1), 0x01 SNI, 0x02 first `host[:port]`, 0x03 custom SNI,
     * 0x05/0x06 credentials, 0x07 insecure flag, 0x08 DER certificate chain, 0x09 QUIC when the byte is 2,
     * 0x0A anti-DPI (TLS fragment on), 0x0B client random, 0x0C name; has_ipv6, dns_upstreams and unknown tags are skipped.
     */
    fun parseFromDeepLink(payload: String): Boolean {
        val data = Base64Strict.decodeLenient(payload, urlSafe = true)
        if (data.isEmpty()) return false
        var haveAddress = false
        val cursor = ByteCursor(data)
        while (cursor.pos < data.size) {
            val tag = cursor.readVarInt() ?: return false
            val length = cursor.readVarInt() ?: return false
            if (length > cursor.remaining.toLong()) return false
            val value = data.copyOfRange(cursor.pos, cursor.pos + length.toInt())
            cursor.pos += length.toInt()
            val text = String(value, Charsets.UTF_8)
            val flag = value.isNotEmpty() && value[0].toInt() != 0
            when (tag) {
                0x00L -> {
                    val version = ByteCursor(value).readVarInt()
                    if (version == null || version > 2) return false
                }
                0x01L -> tls.server_name = text
                0x02L -> {
                    // only the first address is kept: a profile has one server
                    if (!haveAddress) {
                        val address = LinkParser.parse("tt://$text")
                        if (!address.isValid || address.host.isEmpty()) return false
                        server = address.host
                        serverPort = address.port(443)
                        haveAddress = true
                    }
                }
                0x03L -> custom_sni = text.trim()
                0x05L -> username = text
                0x06L -> password = text
                0x07L -> tls.insecure = flag
                0x08L -> {
                    tls.certificate = derChainToPem(value)
                    if (tls.certificate.isEmpty()) return false
                }
                0x09L -> quic = value.isNotEmpty() && value[0].toInt() == 2
                // anti_dpi slows the handshake writes down so the ClientHello spans several segments
                0x0AL -> if (flag) tls.saveFragmentState(1)
                0x0BL -> client_random = text.trim()
                0x0CL -> name = text
                // v2 subscription URL: must be https; the static fields stay required, so a subscription-only link fails
                0x0EL -> if (!text.startsWith("https://")) return false
            }
        }
        tls.enabled = true
        return haveAddress && tls.server_name.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
    }

    /** trusttunnel.cpp:107-132: a link without a host but with a query is the deep link. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        if (url.host.isEmpty()) {
            val payload = rawQuery(link)
            if (payload != null) return parseFromDeepLink(payload)
        }
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password

        if (q.has("health_check")) health_check = q.value("health_check") == "true"
        if (q.has("congestion_control")) {
            quic = true
            congestion_control = importCongestion(q.value("congestion_control"))
        }
        if (q.has("custom_sni")) custom_sni = q.value("custom_sni").trim()
        if (q.has("client_random")) client_random = q.value("client_random").trim()

        tls.parseFromLink(url)
        tls.enabled = true

        if (serverPort == 0) serverPort = 443
        return !(username.isEmpty() || password.isEmpty() || server.isEmpty())
    }

    /** trusttunnel.cpp:134-147. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "trusttunnel") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("health_check")) health_check = obj.bool("health_check")
        if (obj.contains("quic")) quic = obj.bool("quic")
        if (obj.contains("quic_congestion_control")) congestion_control = importCongestion(obj.string("quic_congestion_control"))
        if (obj.contains("custom_sni")) custom_sni = obj.string("custom_sni")
        if (obj.contains("client_random")) client_random = obj.string("client_random")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** trusttunnel.cpp:149-171 (see Mieru.exportToLink for the empty user-info rule). */
    override fun exportToLink(): String {
        val url = LinkBuilder("tt")
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name

        if (health_check) url.addQueryItem("health_check", "true")
        // the link carries QUIC only through congestion_control; sing-trusttunnel treats "" and "bbr" the same
        if (quic) url.addQueryItem("congestion_control", congestion_control.ifEmpty { "bbr" })
        if (custom_sni.isNotEmpty()) url.addQueryItem("custom_sni", custom_sni)
        if (client_random.isNotEmpty()) url.addQueryItem("client_random", client_random)

        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** trusttunnel.cpp:173-189. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "trusttunnel"
        obj.merge(baseExportToJson())
        writeFields(obj, congestion_control)
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** trusttunnel.cpp:191-219. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "trusttunnel"
        obj.merge(baseBuild(ctx))
        writeFields(obj, canonicalCongestion(congestion_control))
        if (tls.enabled) {
            val tlsObject = tls.build(ctx)
            // QUIC dials through qtls, which needs a std TLS config: uTLS and Reality fail there on every connection
            if (quic) {
                tlsObject.remove("utls")
                tlsObject.remove("reality")
            }
            // the official client mimics Chrome by default
            if (!quic && !tlsObject.contains("utls")) {
                tlsObject["utls"] = jsonObjectOf("enabled" to true, "fingerprint" to "chrome")
            }
            obj["tls"] = tlsObject
        }
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject, congestion: String) {
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (health_check) obj["health_check"] = health_check
        if (quic) {
            obj["quic"] = quic
            if (congestion.isNotEmpty()) obj["quic_congestion_control"] = congestion
        }
        if (custom_sni.isNotEmpty()) obj["custom_sni"] = Hosts.toAceHost(custom_sni)
        if (client_random.isNotEmpty()) obj["client_random"] = client_random
    }

    /** trusttunnel.cpp:221-224. */
    override fun displayType(): String = "TrustTunnel"

    /** trusttunnel.cpp:226-229. */
    override fun security(): SecurityInfo = securityFromTls(if (quic) "QUIC" else "")

    /** A byte reader for the TLV payload; [readVarInt] is an RFC 9000 variable-length integer, null when truncated. */
    private class ByteCursor(private val data: ByteArray) {
        var pos = 0
        val remaining: Int get() = data.size - pos

        fun readVarInt(): Long? {
            if (pos >= data.size) return null
            val first = data[pos].toInt() and 0xFF
            val length = 1 shl (first shr 6)
            if (pos + length > data.size) return null
            var value = (first and 0x3F).toLong()
            for (i in 1 until length) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += length
            return value
        }
    }

    companion object {
        /** sing-trusttunnel runs cubic and reno as such and anything else as BBR. */
        private val CONGESTION_CONTROLS = listOf("", "bbr", "cubic", "reno", "bbr_standard", "bbr2", "bbr_variant")

        /** Older builds saved menu text ("CUBIC", "Reno"), which the core silently ran as bbr. */
        private fun canonicalCongestion(value: String): String = value.trim().lowercase().replace(' ', '_')

        /** An imported unknown name is dropped rather than run as BBR under another label. */
        private fun importCongestion(value: String): String =
            canonicalCongestion(value).takeIf { it in CONGESTION_CONTROLS }.orEmpty()

        /** QUrl::query() of the link: the text after the first '?' of the hierarchical part, up to '#'; null without a '?'. */
        private fun rawQuery(link: String): String? {
            val hash = link.indexOf('#')
            val head = if (hash >= 0) link.substring(0, hash) else link
            val question = head.indexOf('?')
            return if (question >= 0) head.substring(question + 1) else null
        }

        /** trusttunnel.cpp:24-47: splits concatenated DER certificates and returns them as PEM lines; empty when malformed. */
        private fun derChainToPem(chain: ByteArray): MutableList<String> {
            val lines = ArrayList<String>()
            var pos = 0
            while (pos + 2 <= chain.size && (chain[pos].toInt() and 0xFF) == 0x30) {
                var headerLength = 2
                var length = chain[pos + 1].toLong() and 0xFF
                if ((length and 0x80L) != 0L) {
                    val lengthBytes = (length and 0x7FL).toInt()
                    if (lengthBytes == 0 || lengthBytes > 4 || pos + 2 + lengthBytes > chain.size) return ArrayList()
                    length = 0
                    for (i in 0 until lengthBytes) length = (length shl 8) or (chain[pos + 2 + i].toLong() and 0xFF)
                    headerLength += lengthBytes
                }
                val total = headerLength.toLong() + length
                if (pos + total > chain.size) return ArrayList()
                lines.add("-----BEGIN CERTIFICATE-----")
                val encoded = Base64Strict.encode(chain.copyOfRange(pos, pos + total.toInt()))
                var i = 0
                while (i < encoded.length) {
                    lines.add(encoded.substring(i, minOf(i + 64, encoded.length)))
                    i += 64
                }
                lines.add("-----END CERTIFICATE-----")
                pos += total.toInt()
            }
            return if (pos == chain.size) lines else ArrayList()
        }
    }
}
