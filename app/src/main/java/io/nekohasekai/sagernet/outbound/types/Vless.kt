package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.vPacketEncoding
import app.qthrone.mobile.outbound.common.Multiplex
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.common.Transport
import app.qthrone.mobile.outbound.common.XrayStreamSetting
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** vless, the sing-box one (include/configs/outbounds/vless.h, src/configs/outbounds/vless.cpp). */
class Vless : Outbound("vless") {
    @JvmField var uuid: String = ""
    @JvmField var flow: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var packet_encoding: String = "xudp"
    @JvmField var multiplex: Multiplex = Multiplex()
    @JvmField var transport: Transport = Transport()

    override fun hasTls(): Boolean = true
    override fun hasMux(): Boolean = true
    override fun hasTransport(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getMux(): Multiplex = multiplex
    override fun getTransport(): Transport = transport
    override fun isXray(): Boolean = transport.runsOnXray(tls)
    override fun getXrayStream(): XrayStreamSetting = XrayStreamSetting().also { it.parseFromLink(exportToLink()) }

    override fun buildXray(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["protocol"] = "vless"
        val settings = JsonObject()
        settings["address"] = server
        settings["port"] = serverPort
        settings["id"] = uuid
        settings["encryption"] = "none"
        if (flow.isNotEmpty() && flow != "none") settings["flow"] = flow
        obj["settings"] = settings
        val streamObj = getXrayStream().build(ctx)
        if (streamObj.isNotEmpty()) obj["streamSettings"] = streamObj
        return BuildResult(obj)
    }

    /** vless.cpp:9-34. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        uuid = url.userName
        if (serverPort == 0) serverPort = 443
        flow = q.valueOr("flow", "")
        transport.parseFromLink(url)
        tls.parseFromLink(url)
        if (tls.server_name.isNotEmpty()) tls.enabled = true
        packet_encoding = q.valueOr("packetEncoding", "xudp")
        if (packet_encoding !in vPacketEncoding) packet_encoding = ""
        multiplex.parseFromLink(url)
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** vless.cpp:49-61: `packet-encoding` is copied even when absent, like the desktop. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "vless") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        val clashFlow = proxy.string("flow")
        if (clashFlow.isNotEmpty()) flow = clashFlow
        packet_encoding = proxy.string("packet-encoding")
        tls.parseFromClash(proxy)
        transport.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        return true
    }

    /** vless.cpp:36-47. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "vless") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("flow")) flow = obj.string("flow")
        if (obj.contains("packet_encoding")) packet_encoding = obj.string("packet_encoding")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("transport")) transport.parseFromJson(obj.obj("transport"))
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** vless.cpp:63-84: no dial-field query items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("vless")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItem("encryption", "none")
        if (flow.isNotEmpty()) url.addQueryItem("flow", flow)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(transport.exportToLink(tls.enabled))
        url.addQueryItems(multiplex.exportToLink())
        url.addQueryItem("packetEncoding", packet_encoding.ifEmpty { "none" })
        return url.build()
    }

    /** vless.cpp:86-98: `packet_encoding` is always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "vless"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (flow.isNotEmpty()) obj["flow"] = flow
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = transport.tlsFor(tlsObj)
        val transportObj = transport.exportToJson()
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** vless.cpp:100-112. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "vless"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (flow.isNotEmpty()) obj["flow"] = flow
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.build(ctx)
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** vless.cpp:114-117. */
    override fun displayType(): String = "VLESS"
}
