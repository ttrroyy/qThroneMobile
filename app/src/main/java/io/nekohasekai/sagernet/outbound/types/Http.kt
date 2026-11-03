package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.common.Tls
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.JsonValues
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkParser

/** http (include/configs/outbounds/http.h, src/configs/outbounds/http.cpp). */
class Http : Outbound("http") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var path: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** http.cpp:9-42. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password
        // v2rayN base64s "user:pass" into the username; without ':' both halves are the whole string
        if (password.isEmpty() && username.isNotEmpty()) {
            val decoded = Base64Strict.decodeToString(username)
            if (decoded.isNotEmpty()) {
                username = QtStrings.substrBefore(decoded, ":")
                password = QtStrings.substrAfter(decoded, ":")
            }
        }
        // some providers set only the password (http://:token@host); the username must equal it
        if (username.isEmpty() && password.isNotEmpty()) username = password
        if (q.has("path")) path = q.value("path")
        if (q.has("headers")) headers = QtStrings.split(q.value("headers"), ",")
        if (url.scheme == "https" || q.value("security") == "tls") {
            tls.parseFromLink(url)
            tls.enabled = true
        }
        if (serverPort == 0) serverPort = if (tls.enabled) 443 else 80
        return true
    }

    /** http.cpp:54-63. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "http") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        tls.parseFromClash(proxy)
        return true
    }

    /** http.cpp:43-53. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "http") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("path")) path = obj.string("path")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** http.cpp:64-80. */
    override fun exportToLink(): String {
        val url = LinkBuilder(if (tls.enabled) "https" else "http")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (path.isNotEmpty()) url.addQueryItem("path", path)
        if (headers.isNotEmpty()) url.addQueryItem("headers", headers.joinToString(","))
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** http.cpp:81-92. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "http"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (path.isNotEmpty()) obj["path"] = path
        val headerObj = JsonValues.pairListToObject(headers)
        if (headerObj.isNotEmpty()) obj["headers"] = headerObj
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        return obj
    }

    /** http.cpp:93-104. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "http"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (path.isNotEmpty()) obj["path"] = path
        val headerObj = JsonValues.pairListToObject(headers)
        if (headerObj.isNotEmpty()) obj["headers"] = headerObj
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        return BuildResult(obj)
    }

    /** http.cpp:106-109. */
    override fun displayType(): String = if (tls.enabled) "HTTPS" else "HTTP"
}
