package app.rawline.mobile.outbound.common

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkParser
import app.rawline.mobile.outbound.link.ParsedLink

/**
 * QUICFields (include/configs/common/QUICFields.h, src/configs/common/QUICFields.cpp): built flat into the
 * hysteria/tuic/masque outbounds; empty / 0 means "no override".
 */
class QuicFields {
    @JvmField var idle_timeout: String = ""
    @JvmField var keep_alive_period: String = ""
    @JvmField var stream_receive_window: String = ""
    @JvmField var connection_receive_window: String = ""
    @JvmField var max_concurrent_streams: Int = 0
    @JvmField var initial_packet_size: Int = 0
    @JvmField var disable_path_mtu_discovery: Boolean = false
    @JvmField var disable_path_mtu_discovery_unspecified: Boolean = true

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** QUICFields.cpp:6-26; a hysteria port-hopping link has an invalid port and is still read. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("quic_idle_timeout")) idle_timeout = q.value("quic_idle_timeout")
        if (q.has("quic_keep_alive_period")) keep_alive_period = q.value("quic_keep_alive_period")
        if (q.has("quic_stream_receive_window")) stream_receive_window = q.value("quic_stream_receive_window")
        if (q.has("quic_connection_receive_window")) connection_receive_window = q.value("quic_connection_receive_window")
        if (q.has("quic_max_concurrent_streams")) max_concurrent_streams = QtStrings.toInt(q.value("quic_max_concurrent_streams"))
        if (q.has("quic_initial_packet_size")) initial_packet_size = QtStrings.toInt(q.value("quic_initial_packet_size"))
        if (q.has("quic_disable_path_mtu_discovery")) {
            disable_path_mtu_discovery = q.value("quic_disable_path_mtu_discovery") == "true"
            disable_path_mtu_discovery_unspecified = false
        } else {
            disable_path_mtu_discovery_unspecified = true
        }
        return true
    }

    /** QUICFields.cpp:27-44. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("idle_timeout")) idle_timeout = obj.string("idle_timeout")
        if (obj.contains("keep_alive_period")) keep_alive_period = obj.string("keep_alive_period")
        if (obj.contains("stream_receive_window")) stream_receive_window = obj.string("stream_receive_window")
        if (obj.contains("connection_receive_window")) connection_receive_window = obj.string("connection_receive_window")
        if (obj.contains("max_concurrent_streams")) max_concurrent_streams = obj.int("max_concurrent_streams")
        if (obj.contains("initial_packet_size")) initial_packet_size = obj.int("initial_packet_size")
        if (obj.contains("disable_path_mtu_discovery")) {
            disable_path_mtu_discovery = obj.bool("disable_path_mtu_discovery")
            disable_path_mtu_discovery_unspecified = false
        } else {
            disable_path_mtu_discovery_unspecified = true
        }
        return true
    }

    /** QUICFields.cpp:45-56. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (idle_timeout.isNotEmpty()) q.add("quic_idle_timeout" to idle_timeout)
        if (keep_alive_period.isNotEmpty()) q.add("quic_keep_alive_period" to keep_alive_period)
        if (stream_receive_window.isNotEmpty()) q.add("quic_stream_receive_window" to stream_receive_window)
        if (connection_receive_window.isNotEmpty()) q.add("quic_connection_receive_window" to connection_receive_window)
        if (max_concurrent_streams > 0) q.add("quic_max_concurrent_streams" to max_concurrent_streams.toString())
        if (initial_packet_size > 0) q.add("quic_initial_packet_size" to initial_packet_size.toString())
        if (!disable_path_mtu_discovery_unspecified) q.add("quic_disable_path_mtu_discovery" to if (disable_path_mtu_discovery) "true" else "false")
        return q
    }

    /** QUICFields.cpp:57-69. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (idle_timeout.isNotEmpty()) obj["idle_timeout"] = idle_timeout
        if (keep_alive_period.isNotEmpty()) obj["keep_alive_period"] = keep_alive_period
        if (stream_receive_window.isNotEmpty()) obj["stream_receive_window"] = stream_receive_window
        if (connection_receive_window.isNotEmpty()) obj["connection_receive_window"] = connection_receive_window
        if (max_concurrent_streams > 0) obj["max_concurrent_streams"] = max_concurrent_streams
        if (initial_packet_size > 0) obj["initial_packet_size"] = initial_packet_size
        if (!disable_path_mtu_discovery_unspecified) obj["disable_path_mtu_discovery"] = disable_path_mtu_discovery
        return obj
    }

    /** QUICFields.cpp:70-86: the global h2_/quic_ settings fill in whatever the profile leaves empty. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        val idleTimeout = ctx.h2IdleTimeout.trim()
        val keepAlivePeriod = ctx.h2KeepAlivePeriod.trim()
        val streamReceiveWindow = ctx.h2StreamReceiveWindow.trim()
        val connectionReceiveWindow = ctx.h2ConnectionReceiveWindow.trim()
        if (idle_timeout.isEmpty() && idleTimeout.isNotEmpty()) obj["idle_timeout"] = idleTimeout
        if (keep_alive_period.isEmpty() && keepAlivePeriod.isNotEmpty()) obj["keep_alive_period"] = keepAlivePeriod
        if (stream_receive_window.isEmpty() && streamReceiveWindow.isNotEmpty()) obj["stream_receive_window"] = streamReceiveWindow
        if (connection_receive_window.isEmpty() && connectionReceiveWindow.isNotEmpty()) obj["connection_receive_window"] = connectionReceiveWindow
        if (max_concurrent_streams <= 0 && ctx.h2MaxConcurrentStreams > 0) obj["max_concurrent_streams"] = ctx.h2MaxConcurrentStreams
        if (initial_packet_size <= 0 && ctx.quicInitialPacketSize > 0) obj["initial_packet_size"] = ctx.quicInitialPacketSize
        if (disable_path_mtu_discovery_unspecified && ctx.quicDisablePathMtuDiscovery) obj["disable_path_mtu_discovery"] = true
        return obj
    }
}
