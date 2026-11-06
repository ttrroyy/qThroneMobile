package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.common.Tls
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser

/** anyTLS (include/configs/outbounds/anyTLS.h, src/configs/outbounds/anyTLS.cpp). */
class AnyTls : Outbound("anytls") {
    @JvmField var password: String = ""
    @JvmField var idle_session_check_interval: String = "30s"
    @JvmField var idle_session_timeout: String = "30s"
    @JvmField var min_idle_session: Int = 5
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** anyTLS.cpp:9-28. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        password = url.userName
        if (serverPort == 0) serverPort = 443
        if (q.has("idle_session_check_interval")) idle_session_check_interval = q.value("idle_session_check_interval")
        if (q.has("idle_session_timeout")) idle_session_timeout = q.value("idle_session_timeout")
        if (q.has("min_idle_session")) min_idle_session = QtStrings.toInt(q.value("min_idle_session"))
        tls.parseFromLink(url)
        tls.enabled = true
        return true
    }

    /** anyTLS.cpp:42-54: the Clash intervals are seconds. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "anytls") return false
        baseParseFromClash(proxy)
        password = proxy.string("password")
        val checkInterval = proxy.int("idle-session-check-interval")
        if (checkInterval > 0) idle_session_check_interval = "${checkInterval}s"
        val timeout = proxy.int("idle-session-timeout")
        if (timeout > 0) idle_session_timeout = "${timeout}s"
        val minIdle = proxy.int("min-idle-session")
        if (minIdle > 0) min_idle_session = minIdle
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /** anyTLS.cpp:30-40. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "anytls") return false
        super.parseFromJson(obj)
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("idle_session_check_interval")) idle_session_check_interval = obj.string("idle_session_check_interval")
        if (obj.contains("idle_session_timeout")) idle_session_timeout = obj.string("idle_session_timeout")
        if (obj.contains("min_idle_session")) min_idle_session = obj.int("min_idle_session")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** anyTLS.cpp:56-75. */
    override fun exportToLink(): String {
        val url = LinkBuilder("anytls")
        // a password missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (password.isNotEmpty()) url.setUserName(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (idle_session_check_interval.isNotEmpty()) url.addQueryItem("idle_session_check_interval", idle_session_check_interval)
        if (idle_session_timeout.isNotEmpty()) url.addQueryItem("idle_session_timeout", idle_session_timeout)
        if (min_idle_session > 0) url.addQueryItem("min_idle_session", min_idle_session.toString())
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** anyTLS.cpp:77-88: `tls` is always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "anytls"
        obj.merge(baseExportToJson())
        if (password.isNotEmpty()) obj["password"] = password
        if (idle_session_check_interval.isNotEmpty()) obj["idle_session_check_interval"] = idle_session_check_interval
        if (idle_session_timeout.isNotEmpty()) obj["idle_session_timeout"] = idle_session_timeout
        if (min_idle_session > 0) obj["min_idle_session"] = min_idle_session
        obj["tls"] = tls.exportToJson()
        return obj
    }

    /** anyTLS.cpp:90-101. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "anytls"
        obj.merge(baseBuild(ctx))
        if (password.isNotEmpty()) obj["password"] = password
        if (idle_session_check_interval.isNotEmpty()) obj["idle_session_check_interval"] = idle_session_check_interval
        if (idle_session_timeout.isNotEmpty()) obj["idle_session_timeout"] = idle_session_timeout
        if (min_idle_session > 0) obj["min_idle_session"] = min_idle_session
        obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** anyTLS.cpp:103-106. */
    override fun displayType(): String = "AnyTLS"
}
