package app.qthrone.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class MobileGroupDragTest {
    @Test fun browsingSeveralTargetsKeepsTheOriginalGroupAndScroll() {
        val drag = MobileGroupDrag()
        drag.start(7, 320)
        drag.hover(9)
        assertTrue(drag.previewHovered())
        drag.hover(12)
        assertTrue(drag.previewHovered())
        assertEquals(12L, drag.previewGroupId)
        assertEquals(MobileGroupDrag.Origin(7, 320), drag.finish())
        assertNull(drag.previewGroupId)
        assertNull(drag.origin)
    }

    @Test fun quickDropAndCancelledHoverCannotOpenAnotherGroupLater() {
        val drag = MobileGroupDrag()
        drag.start(7, 40)
        drag.hover(9)
        assertEquals(MobileGroupDrag.Origin(7, 40), drag.finish())
        assertFalse(drag.previewHovered())
        assertFalse(drag.hover(12))
        drag.start(7, 40)
        drag.hover(9)
        drag.hover(null)
        assertFalse(drag.previewHovered())
        assertNull(drag.previewGroupId)
    }
}
