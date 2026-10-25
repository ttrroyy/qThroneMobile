package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.SecurityInfo
import app.qthrone.mobile.outbound.common.QuicFields
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** tuic (include/configs/outbounds/tuic.h, src/configs/outbounds/tuic.cpp). */
class Tuic : Outbound("tuic") {
    @JvmField var uuid: String = ""
    @JvmField var password: String = ""
    @JvmField var congestion_control: String = ""
    @JvmField var udp_relay_mode: String = ""
    @JvmField var udp_over_stream: Boolean = false
    @JvmField var zero_rtt_handshake: Boolean = false
    @JvmField var heartbeat: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** tuic.h:24-27: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    /** tuic.cpp:9-32. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        val (linkUuid, linkPassword) = url.userInfoPair()
        uuid = linkUuid
        password = linkPassword
        if (q.has("congestion_control")) congestion_control = q.value("congestion_control")
        if (q.has("udp_relay_mode")) udp_relay_mode = q.value("udp_relay_mode")
        if (q.has("udp_over_stream")) udp_over_stream = q.value("udp_over_stream") == "true"
        if (q.has("zero_rtt_handshake")) zero_rtt_handshake = q.value("zero_rtt_handshake") == "true"
        if (q.has("heartbeat")) heartbeat = q.value("heartbeat")
        tls.parseFromLink(url)
        tls.enabled = true
        quic.parseFromLink(url)
        if (serverPort == 0) serverPort = 443
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** tuic.cpp:50-65: `ip` overrides the server, `heartbeat-interval` is milliseconds. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "tuic") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        password = proxy.string("password")
        val congestion = proxy.string("congestion-controller")
        if (congestion.isNotEmpty()) congestion_control = congestion
        val relayMode = proxy.string("udp-relay-mode")
        if (relayMode.isNotEmpty()) udp_relay_mode = relayMode
        zero_rtt_handshake = proxy.bool("reduce-rtt")
        val heartbeatInterval = proxy.int("heartbeat-interval")
        if (heartbeatInterval > 0) heartbeat = "${heartbeatInterval}ms"
        val ip = proxy.string("ip")
        if (ip.isNotEmpty()) server = ip
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /** tuic.cpp:34-48: the QUIC keys are flat. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "tuic") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("congestion_control")) congestion_control = obj.string("congestion_control")
        if (obj.contains("udp_relay_mode")) udp_relay_mode = obj.string("udp_relay_mode")
        if (obj.contains("udp_over_stream")) udp_over_stream = obj.bool("udp_over_stream")
        if (obj.contains("zero_rtt_handshake")) zero_rtt_handshake = obj.bool("zero_rtt_handshake")
        if (obj.contains("heartbeat")) heartbeat = obj.string("heartbeat")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        quic.parseFromJson(obj)
        return true
    }

    /** tuic.cpp:67-90. */
    override fun exportToLink(): String {
        val url = LinkBuilder("tuic")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (congestion_control.isNotEmpty()) url.addQueryItem("congestion_control", congestion_control)
        if (udp_relay_mode.isNotEmpty() && !udp_over_stream) url.addQueryItem("udp_relay_mode", udp_relay_mode)
        if (udp_over_stream) url.addQueryItem("udp_over_stream", "true")
        if (zero_rtt_handshake) url.addQueryItem("zero_rtt_handshake", "true")
        if (heartbeat.isNotEmpty()) url.addQueryItem("heartbeat", heartbeat)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(quic.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** tuic.cpp:92-107: `udp_relay_mode` only without `udp_over_stream`, `tls` only when enabled. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "tuic"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (congestion_control.isNotEmpty()) obj["congestion_control"] = congestion_control
        if (udp_relay_mode.isNotEmpty() && !udp_over_stream) obj["udp_relay_mode"] = udp_relay_mode
        if (udp_over_stream) obj["udp_over_stream"] = udp_over_stream
        if (zero_rtt_handshake) obj["zero_rtt_handshake"] = zero_rtt_handshake
        if (heartbeat.isNotEmpty()) obj["heartbeat"] = heartbeat
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** tuic.cpp:109-124. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "tuic"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (congestion_control.isNotEmpty()) obj["congestion_control"] = congestion_control
        if (udp_relay_mode.isNotEmpty() && !udp_over_stream) obj["udp_relay_mode"] = udp_relay_mode
        if (udp_over_stream) obj["udp_over_stream"] = udp_over_stream
        if (zero_rtt_handshake) obj["zero_rtt_handshake"] = zero_rtt_handshake
        if (heartbeat.isNotEmpty()) obj["heartbeat"] = heartbeat
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** tuic.cpp:126-129. */
    override fun displayType(): String = "TUIC"

    /** tuic.cpp:131-134. */
    override fun security(): SecurityInfo = securityFromTls("QUIC")
}
