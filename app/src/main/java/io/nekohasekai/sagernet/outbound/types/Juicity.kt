package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.SecurityInfo
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** juicity (include/configs/outbounds/juicity.h, src/configs/outbounds/juicity.cpp). */
class Juicity : Outbound("juicity") {
    @JvmField var uuid: String = ""
    @JvmField var password: String = ""
    @JvmField var tls: Tls = Tls()

    /** juicity.h:14-17: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** juicity.cpp:9-25. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        super.parseFromLink(url)
        val (linkUuid, linkPassword) = url.userInfoPair()
        uuid = linkUuid
        password = linkPassword
        tls.parseFromLink(url)
        tls.enabled = true
        if (serverPort == 0) serverPort = 443
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** juicity.cpp:27-35. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "juicity") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** juicity.cpp:37-53. */
    override fun exportToLink(): String {
        val url = LinkBuilder("juicity")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** juicity.cpp:55-64. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "juicity"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** juicity.cpp:66-75. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "juicity"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** juicity.cpp:77-80. */
    override fun displayType(): String = "Juicity"

    /** juicity.cpp:82-85. */
    override fun security(): SecurityInfo = securityFromTls("QUIC")
}
