package app.rawline.mobile.bg.proto

import app.rawline.core.mobile.Instance

/** Per-tag traffic of the running box, read once per tick. */
class TrafficUpdater(private val box: Instance, tags: Collection<String>) {

    class TagStat(val tag: String) {
        /** Bytes moved since the previous tick. */
        var tx = 0L
        var rx = 0L
        var txRate = 0L
        var rxRate = 0L
        var lastUpdate = 0L
    }

    val stats: Map<String, TagStat> = tags.associateWith { TagStat(it) }

    fun updateAll() {
        val now = System.currentTimeMillis()
        for (stat in stats.values) {
            // trafficcontrol.Manager.TotalOutbound swaps its counters to 0: every read is already a delta.
            stat.tx = box.queryOutboundStats(stat.tag, "uplink").coerceAtLeast(0)
            stat.rx = box.queryOutboundStats(stat.tag, "downlink").coerceAtLeast(0)
            val interval = now - stat.lastUpdate
            if (stat.lastUpdate > 0L && interval > 0L) {
                stat.txRate = stat.tx * 1000 / interval
                stat.rxRate = stat.rx * 1000 / interval
            } else {
                stat.txRate = 0
                stat.rxRate = 0
            }
            stat.lastUpdate = now
        }
    }
}
