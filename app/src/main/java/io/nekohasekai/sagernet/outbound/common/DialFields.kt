package app.rawline.mobile.outbound.common

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.outbound.link.ParsedLink

/** DialFields (include/configs/common/DialFields.h, src/configs/common/DialFields.cpp); merged flat into the outbound. */
class DialFields {
    @JvmField var reuse_addr: Boolean = false
    @JvmField var connect_timeout: String = ""
    @JvmField var tcp_fast_open: Boolean = false
    @JvmField var tcp_multi_path: Boolean = false
    @JvmField var udp_fragment: Boolean = false
    @JvmField var bind_interface: String = ""
    @JvmField var inet4_bind_address: String = ""
    @JvmField var inet6_bind_address: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** DialFields.cpp:6-21. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("reuse_addr")) reuse_addr = q.value("reuse_addr") == "true"
        if (q.has("connect_timeout")) connect_timeout = q.value("connect_timeout")
        if (q.has("tcp_fast_open")) tcp_fast_open = q.value("tcp_fast_open") == "true"
        if (q.has("tcp_multi_path")) tcp_multi_path = q.value("tcp_multi_path") == "true"
        if (q.has("udp_fragment")) udp_fragment = q.value("udp_fragment") == "true"
        if (q.has("bind_interface")) bind_interface = q.value("bind_interface")
        if (q.has("inet4_bind_address")) inet4_bind_address = q.value("inet4_bind_address")
        if (q.has("inet6_bind_address")) inet6_bind_address = q.value("inet6_bind_address")
        return true
    }

    /** DialFields.cpp:22-35. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("reuse_addr")) reuse_addr = obj.bool("reuse_addr")
        if (obj.contains("connect_timeout")) connect_timeout = obj.string("connect_timeout")
        if (obj.contains("tcp_fast_open")) tcp_fast_open = obj.bool("tcp_fast_open")
        if (obj.contains("tcp_multi_path")) tcp_multi_path = obj.bool("tcp_multi_path")
        if (obj.contains("udp_fragment")) udp_fragment = obj.bool("udp_fragment")
        if (obj.contains("bind_interface")) bind_interface = obj.string("bind_interface")
        if (obj.contains("inet4_bind_address")) inet4_bind_address = obj.string("inet4_bind_address")
        if (obj.contains("inet6_bind_address")) inet6_bind_address = obj.string("inet6_bind_address")
        return true
    }

    /** DialFields.cpp:36-48. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (reuse_addr) q.add("reuse_addr" to "true")
        if (connect_timeout.isNotEmpty()) q.add("connect_timeout" to connect_timeout)
        if (tcp_fast_open) q.add("tcp_fast_open" to "true")
        if (tcp_multi_path) q.add("tcp_multi_path" to "true")
        if (udp_fragment) q.add("udp_fragment" to "true")
        if (bind_interface.isNotEmpty()) q.add("bind_interface" to bind_interface)
        if (inet4_bind_address.isNotEmpty()) q.add("inet4_bind_address" to inet4_bind_address)
        if (inet6_bind_address.isNotEmpty()) q.add("inet6_bind_address" to inet6_bind_address)
        return q
    }

    /** DialFields.cpp:49-61. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (reuse_addr) obj["reuse_addr"] = true
        if (connect_timeout.isNotEmpty()) obj["connect_timeout"] = connect_timeout
        if (tcp_fast_open) obj["tcp_fast_open"] = true
        if (tcp_multi_path) obj["tcp_multi_path"] = true
        if (udp_fragment) obj["udp_fragment"] = true
        if (bind_interface.isNotEmpty()) obj["bind_interface"] = bind_interface
        if (inet4_bind_address.isNotEmpty()) obj["inet4_bind_address"] = inet4_bind_address
        if (inet6_bind_address.isNotEmpty()) obj["inet6_bind_address"] = inet6_bind_address
        return obj
    }

    /** DialFields.cpp:62-65. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}
