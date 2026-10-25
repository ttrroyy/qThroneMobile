package app.qthrone.mobile.outbound.common

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.QtStrings
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.LinkParser
import app.qthrone.mobile.outbound.link.ParsedLink

/** TcpBrutal (multiplex.h:8-20, multiplex.cpp:7-48). */
class TcpBrutal {
    @JvmField var enabled: Boolean = false
    @JvmField var up_mbps: Int = 0
    @JvmField var down_mbps: Int = 0

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** multiplex.cpp:7-17. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("brutal_enabled")) enabled = q.value("brutal_enabled") == "true"
        if (q.has("brutal_up_mbps")) up_mbps = QtStrings.toInt(q.value("brutal_up_mbps"))
        if (q.has("brutal_down_mbps")) down_mbps = QtStrings.toInt(q.value("brutal_down_mbps"))
        return true
    }

    /** multiplex.cpp:18-25. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("up_mbps")) up_mbps = obj.int("up_mbps")
        if (obj.contains("down_mbps")) down_mbps = obj.int("down_mbps")
        return true
    }

    /** multiplex.cpp:26-34. */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("brutal_enabled" to "true")
        if (up_mbps > 0) q.add("brutal_up_mbps" to up_mbps.toString())
        if (down_mbps > 0) q.add("brutal_down_mbps" to down_mbps.toString())
        return q
    }

    /** multiplex.cpp:35-44: sing-box wants both rates, so a missing one is written as 1. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        obj["up_mbps"] = if (up_mbps <= 0) 1 else up_mbps
        obj["down_mbps"] = if (down_mbps <= 0) 1 else down_mbps
        return obj
    }

    /** multiplex.cpp:45-48. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** Multiplex (multiplex.h:22-56, multiplex.cpp:50-137). `unspecified` true means "Keep Default". */
/** A multiplex edited through the Keep default (0) / On (1) / Off (2) / Custom ([MUX_CUSTOM]) menu. */
interface MuxStateModel {
    fun getMuxState(): Int

    /** Every state but Custom drops the own settings, so On means the global preset alone. */
    fun saveMuxState(state: Int)
}

/** "Custom" in the profile's multiplex menu: On with settings of its own. */
const val MUX_CUSTOM = 3

class Multiplex : MuxStateModel {
    @JvmField var enabled: Boolean = false
    @JvmField var unspecified: Boolean = true
    @JvmField var protocol: String = ""
    @JvmField var max_connections: Int = 0
    @JvmField var min_streams: Int = 0
    @JvmField var max_streams: Int = 0
    @JvmField var padding: Boolean = false
    @JvmField var brutal: TcpBrutal = TcpBrutal()

    /** Explicitly enabled with settings of its own is Custom, so profiles saved before keep building as they did. */
    override fun getMuxState(): Int = when {
        unspecified -> 0
        !enabled -> 2
        hasOwnSettings() -> MUX_CUSTOM
        else -> 1
    }

    override fun saveMuxState(state: Int) {
        unspecified = state == 0
        enabled = state == 1 || state == MUX_CUSTOM
        if (state != MUX_CUSTOM) {
            protocol = ""
            max_connections = 0
            min_streams = 0
            max_streams = 0
            padding = false
            brutal = TcpBrutal()
        }
    }

    fun hasOwnSettings(): Boolean =
        protocol.isNotEmpty() || max_connections > 0 || min_streams > 0 || max_streams > 0 || padding || brutal.enabled

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** multiplex.cpp:50-65. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("mux")) {
            enabled = q.value("mux") == "true"
            unspecified = false
        } else {
            unspecified = true
        }
        if (q.has("mux_protocol")) protocol = q.value("mux_protocol")
        if (q.has("mux_max_connections")) max_connections = QtStrings.toInt(q.value("mux_max_connections"))
        if (q.has("mux_min_streams")) min_streams = QtStrings.toInt(q.value("mux_min_streams"))
        if (q.has("mux_max_streams")) max_streams = QtStrings.toInt(q.value("mux_max_streams"))
        if (q.has("mux_padding")) padding = q.value("mux_padding") == "true"
        brutal.parseFromLink(url)
        return true
    }

    /** multiplex.cpp:66-82. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) {
            unspecified = true
            return false
        }
        if (obj.contains("enabled")) {
            enabled = obj.bool("enabled")
            unspecified = false
        } else {
            unspecified = true
        }
        if (obj.contains("protocol")) protocol = obj.string("protocol")
        if (obj.contains("max_connections")) max_connections = obj.int("max_connections")
        if (obj.contains("min_streams")) min_streams = obj.int("min_streams")
        if (obj.contains("max_streams")) max_streams = obj.int("max_streams")
        if (obj.contains("padding")) padding = obj.bool("padding")
        if (obj.contains("brutal")) brutal.parseFromJson(obj.obj("brutal"))
        return true
    }

    /** multiplex.cpp:83-96: `max-streams` wins over the connection pair. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val smux = proxy.obj("smux")
        enabled = smux.bool("enabled")
        unspecified = false
        val smuxProtocol = smux.string("protocol")
        if (smuxProtocol.isNotEmpty()) protocol = smuxProtocol
        val maxStreams = smux.int("max-streams")
        if (maxStreams > 0) {
            max_streams = maxStreams
        } else {
            max_connections = smux.int("max-connections")
            min_streams = smux.int("min-streams")
        }
        padding = smux.bool("padding")
        return true
    }

    /** multiplex.cpp:97-112. */
    fun exportToLink(): List<Pair<String, String>> {
        if (unspecified) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("mux" to if (enabled) "true" else "false")
        if (enabled) {
            if (protocol.isNotEmpty()) q.add("mux_protocol" to protocol)
            if (max_connections > 0) q.add("mux_max_connections" to max_connections.toString())
            if (min_streams > 0) q.add("mux_min_streams" to min_streams.toString())
            if (max_streams > 0) q.add("mux_max_streams" to max_streams.toString())
            if (padding) q.add("mux_padding" to "true")
            q.addAll(brutal.exportToLink())
        }
        return q
    }

    /** multiplex.cpp:113-127: {} for Keep Default, {enabled:false} for Off. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (unspecified) return obj
        obj["enabled"] = enabled
        if (!enabled) return obj
        if (protocol.isNotEmpty()) obj["protocol"] = protocol
        if (max_connections > 0) obj["max_connections"] = max_connections
        if (min_streams > 0) obj["min_streams"] = min_streams
        if (max_streams > 0) obj["max_streams"] = max_streams
        if (padding) obj["padding"] = padding
        if (brutal.enabled) obj["brutal"] = brutal.exportToJson()
        return obj
    }

    /** multiplex.cpp:128-137: starts from ExportToJson, so an unspecified profile switched on by the global keeps only the defaults. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (unspecified && ctx.muxDefaultOn) obj["enabled"] = true
        if (!obj.bool("enabled")) return JsonObject()
        if (protocol.isEmpty()) obj["protocol"] = ctx.muxProtocol
        if (max_streams == 0 && max_connections == 0 && min_streams == 0) obj["max_streams"] = ctx.muxConcurrency
        if (ctx.muxPadding) obj["padding"] = true
        return obj
    }
}
