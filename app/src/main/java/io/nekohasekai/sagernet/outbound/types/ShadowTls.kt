package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.common.Tls
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser

/** shadowtls (include/configs/outbounds/shadowtls.h, src/configs/outbounds/shadowtls.cpp). */
class ShadowTls : Outbound("shadowtls") {
    @JvmField var version: Int = 1
    @JvmField var password: String = ""
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** shadowtls.cpp:9-28: the password is the URL password (`shadowtls://:password@host`). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("version")) version = QtStrings.toInt(q.value("version"))
        password = url.password
        tls.parseFromLink(url)
        tls.enabled = true
        if (serverPort == 0) serverPort = 443
        return server.isNotEmpty()
    }

    /** shadowtls.cpp:30-38. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "shadowtls") return false
        super.parseFromJson(obj)
        if (obj.contains("version")) version = obj.int("version")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** shadowtls.cpp:40-57: `version` is always written. */
    override fun exportToLink(): String {
        val url = LinkBuilder("shadowtls")
        if (version > 1 && password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItem("version", version.toString())
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** shadowtls.cpp:59-68. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "shadowtls"
        obj.merge(baseExportToJson())
        obj["version"] = version
        if (version > 1 && password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** shadowtls.cpp:70-75. */
    override fun exportIdentity(): JsonObject {
        val obj = super.exportIdentity()
        obj["version"] = version
        return obj
    }

    /** shadowtls.cpp:77-86. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "shadowtls"
        obj.merge(baseBuild(ctx))
        obj["version"] = version
        if (version > 1 && password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** shadowtls.cpp:88-91. */
    override fun displayType(): String = "ShadowTLS"
}
