package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.BuildResult
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.json.JsonValues
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.Hosts
import app.qthrone.mobile.outbound.link.LinkBuilder
import app.qthrone.mobile.outbound.link.LinkCodec
import app.qthrone.mobile.outbound.link.LinkParser

/** socks (include/configs/outbounds/socks.h, src/configs/outbounds/socks.cpp). */
class Socks : Outbound("socks") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var version: Int = 5
    @JvmField var uot: Boolean = false

    /** Phobos SOCKS5 mode: the SOCKS5 server sits behind the obfuscator's client role; an empty key is plain SOCKS. */
    @JvmField var phobos_key: String = ""
    /** NONE, STUN, MEDIA or TLS. */
    @JvmField var phobos_masking: String = ""
    /** Decimal, empty when the SSRC is random. */
    @JvmField var phobos_media_ssrc: String = ""

    /** The [instance] (mode = socks5) and [socks5] sections of a Phobos .conf. */
    fun parsePhobosConf(conf: String): Boolean {
        var section = ""
        var target = ""
        for (line in conf.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                continue
            }
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            val key = trimmed.substring(0, equals).trim().lowercase()
            val value = trimmed.substring(equals + 1).trim()
            if (value.equals("none", ignoreCase = true)) continue
            if (section == "instance") when (key) {
                "target" -> target = value
                "key" -> phobos_key = value
                "masking" -> phobos_masking = value.uppercase().takeIf { it != "AUTO" }.orEmpty()
                "media-ssrc" -> phobos_media_ssrc = WireGuard.ssrcText(value)
            } else if (section == "socks5") when (key) {
                "login" -> username = value
                "password" -> password = value
            }
        }
        val colon = target.lastIndexOf(':')
        if (colon <= 0 || phobos_key.isEmpty()) return false
        server = target.substring(0, colon).trim().removeSurrounding("[", "]")
        serverPort = QtStrings.toInt(target.substring(colon + 1))
        version = 5
        return server.isNotEmpty() && serverPort in 1..65535
    }

    /** socks.cpp:9-44. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("version")) {
            version = QtStrings.toInt(q.value("version"))
        } else {
            if (url.scheme == "socks4") version = 4
            if (url.scheme == "socks5") version = 5
        }
        if (url.password.isNotEmpty() || url.userName.isNotEmpty()) {
            username = url.userName
            password = url.password
            // v2rayN base64-encodes "user:pass" into the username
            if (password.isEmpty() && username.isNotEmpty()) {
                val decoded = Base64Strict.decodeToString(username)
                if (decoded.isNotEmpty()) {
                    username = QtStrings.substrBefore(decoded, ":")
                    password = QtStrings.substrAfter(decoded, ":")
                }
            }
        }
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        if (serverPort == 0) serverPort = 1080
        return server.isNotEmpty()
    }

    /** socks.cpp:57-65. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "socks5") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        return true
    }

    /** socks.cpp:46-55. `version` is read with QJsonValue::toInt(), which yields 0 for the "4" string ExportToJson writes. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "socks") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("version")) version = obj.int("version")
        if (obj.contains("uot")) uot = obj.bool("uot")
        phobos_key = obj.string("phobos_key")
        phobos_masking = obj.string("phobos_masking")
        phobos_media_ssrc = WireGuard.ssrcText(JsonValues.toInteger(obj["phobos_media_ssrc"]).toString())
        return true
    }

    /** socks.cpp:67-89. */
    override fun exportToLink(): String {
        if (phobos_key.isNotEmpty()) {
            val conf = arrayListOf("[instance]", "mode = socks5", "role = client", "source-if = 127.0.0.1", "source-lport = 1080",
                "target = " + Hosts.displayAddress(server, serverPort), "key = $phobos_key", "masking = " + phobos_masking.ifEmpty { "NONE" })
            if (phobos_media_ssrc.isNotEmpty()) conf += "media-ssrc = $phobos_media_ssrc"
            conf += listOf("", "[socks5]", "login = " + username.ifEmpty { "none" }, "password = " + password.ifEmpty { "none" })
            return "phobos://" + Base64Strict.encode((conf.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8), urlSafe = true, padding = false) +
                "#" + (if (name.isEmpty()) "none" else LinkCodec.percentEncodeAll(name))
        }
        val url = LinkBuilder(if (version == 4) "socks4" else "socks5")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** socks.cpp:91-101. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = "4"
        if (uot) obj["uot"] = uot
        if (phobos_key.isNotEmpty()) {
            obj["phobos_key"] = phobos_key
            if (phobos_masking.isNotEmpty()) obj["phobos_masking"] = phobos_masking
            if (phobos_media_ssrc.isNotEmpty()) obj["phobos_media_ssrc"] = WireGuard.parseSsrc(phobos_media_ssrc)
        }
        return obj
    }

    /** socks.cpp:103-113. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        if (phobos_key.isNotEmpty()) {
            // The core's Phobos outbound: SOCKS5 inside the obfuscated stream, TCP only.
            obj["type"] = "phobos"
            obj.merge(baseBuild(ctx))
            if (username.isNotEmpty()) obj["username"] = username
            if (password.isNotEmpty()) obj["password"] = password
            obj["key"] = phobos_key
            if (phobos_masking.isNotEmpty() && phobos_masking != "NONE") obj["masking"] = phobos_masking.lowercase()
            if (WireGuard.parseSsrc(phobos_media_ssrc) > 0) obj["media_ssrc"] = WireGuard.parseSsrc(phobos_media_ssrc)
            return BuildResult(obj)
        }
        obj["type"] = "socks"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = "4"
        if (uot) obj["uot"] = uot
        return BuildResult(obj)
    }

    /** socks.cpp:115-118. */
    override fun displayType(): String = if (phobos_key.isEmpty()) "Socks" else "Phobos"
}
