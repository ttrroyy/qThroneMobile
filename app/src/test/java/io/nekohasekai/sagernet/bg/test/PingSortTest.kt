package app.rawline.mobile.bg.test

import app.rawline.mobile.database.*
import org.junit.Assert.*
import org.junit.Test

class PingSortTest {
    @Test fun unmeasuredProfilesStayBelowEvenVerySlowWorkingProfiles() {
        val rows=listOf(0,-1,200000,10,100).mapIndexed { i,ping -> ProxyEntity(id=i.toLong(),latency=ping) }
        val sorted=rows.sortedWith(GroupSort.comparator(GroupSortAction(GroupSortMethod.BY_LATENCY),ProxyGroup()))
        assertEquals(listOf(10,100,200000,0,-1),sorted.map { it.latency })
    }
    @Test fun automaticSortingIsPerGroupAndOffByDefault() {
        val first=SubscriptionOptions(autoSortByPing=true)
        val encoded=first.toJson()
        assertFalse(SubscriptionOptions().autoSortByPing)
        assertTrue(encoded.bool("auto_sort_by_ping"))
        assertTrue(SubscriptionOptions.fromJson(encoded).autoSortByPing)
    }
}
