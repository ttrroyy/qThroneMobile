package app.qthrone.mobile.database

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupRemovalTest {
    @Test fun deletionPrefersThePreviousTabAndHandlesTheFirstOrLastGroup() {
        assertEquals(9L, groupAfterRemoval(listOf(7, 9, 12, 15), 12))
        assertEquals(12L, groupAfterRemoval(listOf(7, 9, 12, 15), 15))
        assertEquals(9L, groupAfterRemoval(listOf(7, 9, 12, 15), 7))
        assertEquals(0L, groupAfterRemoval(listOf(7), 7))
    }
}
