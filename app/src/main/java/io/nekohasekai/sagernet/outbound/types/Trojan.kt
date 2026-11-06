package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.common.Multiplex
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.common.Transport
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** Trojan (include/configs/outbounds/trojan.h, src/configs/outbounds/trojan.cpp). */
class Trojan : Outbound("trojan") {
    @JvmField var password: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var multiplex: Multiplex = Multiplex()
    @JvmField var transport: Transport = Transport()

    override fun hasTls(): Boolean = true
    override fun hasMux(): Boolean = true
    override fun hasTransport(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getMux(): Multiplex = multiplex
    override fun getTransport(): Transport = transport

    /** trojan.cpp:9-20: no default port, TLS is not forced on, always true for a parsable URL. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        super.parseFromLink(url)
        password = url.userName
        tls.parseFromLink(url)
        transport.parseFromLink(url)
        multiplex.parseFromLink(url)
        return true
    }

    /** trojan.cpp:31-42. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "trojan") return false
        baseParseFromClash(proxy)
        password = proxy.string("password")
        tls.parseFromClash(proxy)
        tls.enabled = true
        transport.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        return true
    }

    /** trojan.cpp:21-30. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "trojan") return false
        super.parseFromJson(obj)
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("transport")) transport.parseFromJson(obj.obj("transport"))
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** trojan.cpp:43-57: an explicit multiplex Off is not written (only an enabled one is), no dial-field items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("trojan")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        // a password missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (password.isNotEmpty()) url.setUserName(password)
        if (tls.enabled) url.addQueryItems(tls.exportToLink())
        if (transport.type.isNotEmpty()) url.addQueryItems(transport.exportToLink(tls.enabled))
        if (multiplex.enabled) url.addQueryItems(multiplex.exportToLink())
        return url.build()
    }

    /** trojan.cpp:58-68. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "trojan"
        obj.merge(baseExportToJson())
        if (password.isNotEmpty()) obj["password"] = password
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = transport.tlsFor(tlsObj)
        val transportObj = transport.exportToJson()
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** trojan.cpp:69-79. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "trojan"
        obj.merge(baseBuild(ctx))
        if (password.isNotEmpty()) obj["password"] = password
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.build(ctx)
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** trojan.cpp:397-400. */
    override fun displayType(): String = "Trojan"
}
