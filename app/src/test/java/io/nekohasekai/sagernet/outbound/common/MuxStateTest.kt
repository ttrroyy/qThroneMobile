package app.qthrone.mobile.outbound.common

import app.qthrone.mobile.outbound.BuildContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MuxStateTest {
    @Test fun customKeepsOwnSettingsAndOnUsesThePreset() {
        val mux = Multiplex().apply {
            enabled = true
            unspecified = false
            max_connections = 4
            min_streams = 2
        }
        // a profile saved before the Custom state existed opens as Custom
        assertEquals(MUX_CUSTOM, mux.getMuxState())
        mux.saveMuxState(MUX_CUSTOM)
        assertEquals(4, (mux.build(BuildContext())["max_connections"] as Number).toInt())

        mux.saveMuxState(1)
        assertEquals(1, mux.getMuxState())
        assertFalse(mux.hasOwnSettings())
        val preset = mux.build(BuildContext(muxProtocol = "h2mux", muxConcurrency = 8))
        assertEquals("h2mux", preset["protocol"])
        assertEquals(8, (preset["max_streams"] as Number).toInt())

        mux.saveMuxState(0)
        assertEquals(0, mux.getMuxState())
        mux.saveMuxState(2)
        assertFalse(mux.build(BuildContext()).isEmpty().not())
    }
}
