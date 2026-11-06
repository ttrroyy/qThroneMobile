package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.import.ClashProxy
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.SecurityLevel
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser

/** snell (include/configs/outbounds/snell.h, src/configs/outbounds/snell.cpp). */
class Snell : Outbound("snell") {
    @JvmField var version: Int = 4
    @JvmField var psk: String = ""
    @JvmField var userkey: String = ""
    @JvmField var reuse: Boolean = false
    @JvmField var network: String = ""

    /** v4 only */
    @JvmField var obfs_mode: String = ""
    @JvmField var obfs_host: String = ""

    /** v6 only */
    @JvmField var mode: String = ""

    /** snell.cpp:21-45. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid || url.host.isEmpty()) return false
        val q = url.query
        super.parseFromLink(url)
        psk = url.userName
        if (psk.isEmpty() && q.has("psk")) psk = q.valueFully("psk")

        if (q.has("version")) version = QtStrings.toInt(q.value("version"))
        if (!supportedVersion(version)) return false

        if (q.has("userkey")) userkey = q.valueFully("userkey")
        if (q.has("reuse")) {
            val raw = q.value("reuse")
            reuse = raw.isEmpty() || (raw != "0" && !raw.equals("false", ignoreCase = true))
        }
        if (q.has("network")) network = q.value("network")
        if (q.has("obfs")) obfs_mode = q.value("obfs")
        if (q.has("obfs-host")) obfs_host = q.valueFully("obfs-host")
        if (q.has("mode")) mode = q.value("mode")
        return true
    }

    /** snell.cpp:63-77: legacy entries without a version keep the default; unsupported obfs modes are rejected. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "snell") return false
        baseParseFromClash(proxy)
        psk = proxy.string("psk")
        val clashVersion = proxy.int("version")
        if (clashVersion > 0) version = clashVersion
        if (!supportedVersion(version)) return false
        val obfsOpts = proxy.obj("obfs-opts")
        obfs_mode = obfsOpts.string("mode")
        if (!supportedObfsMode(obfs_mode)) return false
        obfs_host = obfsOpts.string("host")
        if (!proxy.bool("udp")) network = "tcp"
        return true
    }

    /** snell.cpp:47-61. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "snell") return false
        super.parseFromJson(obj)
        if (obj.contains("version")) version = obj.int("version")
        if (!supportedVersion(version)) return false
        if (obj.contains("psk")) psk = obj.string("psk")
        if (obj.contains("userkey")) userkey = obj.string("userkey")
        if (obj.contains("reuse")) reuse = obj.bool("reuse")
        if (obj.contains("network")) network = obj.string("network")
        if (obj.contains("obfs_mode")) obfs_mode = obj.string("obfs_mode")
        if (obj.contains("obfs_host")) obfs_host = obj.string("obfs_host")
        if (obj.contains("mode")) mode = obj.string("mode")
        return true
    }

    /** snell.cpp:79-104; the psk goes into the user name (see Mieru.exportToLink for the empty-value rule). */
    override fun exportToLink(): String {
        val url = LinkBuilder("snell")
        if (psk.isNotEmpty()) url.setUserName(psk)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name

        url.addQueryItem("version", version.toString())
        if (userkey.isNotEmpty()) url.addQueryItem("userkey", userkey)
        if (reuse) url.addQueryItem("reuse", "1")
        if (network.isNotEmpty()) url.addQueryItem("network", network)
        if (version == 6) {
            if (mode.isNotEmpty()) url.addQueryItem("mode", mode)
        } else {
            if (obfs_mode.isNotEmpty()) url.addQueryItem("obfs", obfs_mode)
            if (obfs_host.isNotEmpty()) url.addQueryItem("obfs-host", obfs_host)
        }
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** snell.cpp:106-124. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "snell"
        obj.merge(baseExportToJson())
        writeFields(obj)
        return obj
    }

    /** snell.cpp:126-143. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "snell"
        obj.merge(baseBuild(ctx))
        writeFields(obj)
        return BuildResult(obj)
    }

    // The core unmarshals strictly: a key that belongs to the other version fails the whole outbound.
    private fun writeFields(obj: JsonObject) {
        obj["version"] = version
        if (psk.isNotEmpty()) obj["psk"] = psk
        if (userkey.isNotEmpty()) obj["userkey"] = userkey
        if (reuse) obj["reuse"] = true
        if (network.isNotEmpty()) obj["network"] = network
        if (version == 6) {
            if (mode.isNotEmpty()) obj["mode"] = mode
        } else {
            if (obfs_mode.isNotEmpty()) obj["obfs_mode"] = obfs_mode
            if (obfs_host.isNotEmpty()) obj["obfs_host"] = obfs_host
        }
    }

    /** snell.cpp:145-148. */
    override fun displayType(): String = "Snell"

    /** snell.cpp:150-153. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)

    companion object {
        /** snell.cpp:11-13: the core registers a v4 and a v6 client only; no outbound exists for v1-v3 / v5. */
        @JvmStatic
        fun supportedVersion(version: Int): Boolean = version == 4 || version == 6

        /** snell.h:8 snellObfsModes. */
        @JvmField
        val OBFS_MODES: List<String> = listOf("", "none", "http", "tls")

        /** snell.cpp:16-18. */
        @JvmStatic
        fun supportedObfsMode(mode: String): Boolean = mode in OBFS_MODES
    }
}
