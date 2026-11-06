package app.rawline.mobile.bg.carrier

import app.rawline.mobile.outbound.json.JsonInput
import org.junit.Assert.*
import org.junit.Test

class CsqttEpochTest {
    @Test fun generationsFollowTheClockAndNeverRepeat() {
        assertEquals(1_800_000_000L, CsqttEpoch.next(1_800_000_000L, 5L))
        assertEquals(1_800_000_001L, CsqttEpoch.next(1_800_000_000L, 1_800_000_000L))
        assertEquals(Long.MAX_VALUE, CsqttEpoch.next(0L, Long.MAX_VALUE))
        val first = CsqttEpoch.reserve()
        assertTrue(CsqttEpoch.reserve() > first)
    }

    @Test fun everyLaunchCarriesItsOwnEpoch() {
        val salt = CsqttEpoch.salt()
        assertTrue(salt.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(salt, CsqttEpoch.salt())
        val config = JsonInput.parseObject(CsqttEpoch.apply("""{"peer":"203.0.113.7:46000"}""", 42L, salt))
        assertEquals(42L, config.integer("generation"))
        assertEquals(salt, config.string("salt"))
        assertEquals("203.0.113.7:46000", config.string("peer"))
    }
}
