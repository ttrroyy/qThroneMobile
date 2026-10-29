package app.qthrone.mobile.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LatencyColorTest {
    @Test fun mainScreenUsesTheSameDesktopThresholdsAsProfileRows() {
        assertEquals(ProxyEntity.latencyColor(1), ProxyEntity.latencyColor(100))
        assertNotEquals(ProxyEntity.latencyColor(100), ProxyEntity.latencyColor(101))
        assertEquals(ProxyEntity.latencyColor(101), ProxyEntity.latencyColor(300))
        assertNotEquals(ProxyEntity.latencyColor(300), ProxyEntity.latencyColor(301))
        assertEquals(ProxyEntity(latency = 301).latencyColor(), ProxyEntity.latencyColor(301))
    }
}
