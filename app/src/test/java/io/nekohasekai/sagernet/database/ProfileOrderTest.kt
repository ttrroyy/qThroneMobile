package app.rawline.mobile.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderTest {
    @Test fun transfersCanLandAtTheBeginningMiddleAndEnd() {
        assertEquals(listOf(9L, 1L, 2L, 3L), insertProfilesBefore(listOf(1, 2, 3), listOf(9), 1))
        assertEquals(listOf(1L, 9L, 2L, 3L), insertProfilesBefore(listOf(1, 2, 3), listOf(9), 2))
        assertEquals(listOf(1L, 2L, 3L, 9L), insertProfilesBefore(listOf(1, 2, 3), listOf(9), null))
    }

    @Test fun movingWithinAGroupKeepsEveryProfileExactlyOnce() {
        assertEquals(listOf(3L, 1L, 2L, 4L), insertProfilesBefore(listOf(1, 2, 3, 4), listOf(3, 3), 1))
        assertEquals(listOf(1L, 4L, 2L, 3L), insertProfilesBefore(listOf(1, 2, 3, 4), listOf(2, 3), null))
        assertEquals(listOf(1L, 2L, 9L), insertProfilesBefore(listOf(1, 2), listOf(9), 999))
    }
}
