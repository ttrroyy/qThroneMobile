package app.rawline.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class MobileProfilePagingTest {
    @Test fun largeSubscriptionKeepsEveryServerReachableWithBoundedWindows() {
        val ids=(1L..2200L).toList()
        val visited=mutableListOf<Long>()
        for(page in 0 until 22) {
            val window=MobileProfilePaging.window(ids,page,1)
            assertTrue(window.end-window.start<=MobileProfilePaging.PAGE_SIZE)
            visited.addAll(ids.subList(window.start,window.end))
            assertEquals(page>0,window.hasPrevious)
            assertEquals(page<21,window.hasNext)
        }
        assertEquals(ids,visited)
    }

    @Test fun firstPageFindsSelectionButRefreshKeepsExplicitPage() {
        val ids=(1L..2200L).toList()
        assertEquals(21,MobileProfilePaging.window(ids,null,2200).page)
        assertEquals(3,MobileProfilePaging.window(ids,3,2200).page)
        assertEquals(0,MobileProfilePaging.window(ids,null,9999).page)
    }

    @Test fun shrinkingOrEmptySubscriptionsClampSavedPage() {
        assertEquals(MobileProfilePaging.Window(0,0,0,0),MobileProfilePaging.window(emptyList(),22,10))
        assertEquals(MobileProfilePaging.Window(1,100,101,101),MobileProfilePaging.window((1L..101L).toList(),22,10))
        assertEquals(0,MobileProfilePaging.window((1L..101L).toList(),-1,10).page)
    }

    @Test fun draggingMiddlePagePreservesEveryOffscreenPosition() {
        val ids=(1L..301L).toList()
        val visible=ids.subList(100,200)
        val actual=MobileProfilePaging.reorder(ids,visible,visible.reversed())
        assertEquals(ids.take(100),actual.take(100))
        assertEquals(visible.reversed(),actual.subList(100,200))
        assertEquals(ids.drop(200),actual.drop(200))
        assertEquals(ids.toSet(),actual.toSet())
        assertEquals(ids,MobileProfilePaging.reorder(ids,visible,listOf(1)))
    }
}
