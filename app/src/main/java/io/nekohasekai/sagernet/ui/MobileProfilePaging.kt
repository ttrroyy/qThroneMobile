package app.rawline.mobile.ui

/** Bound the dashboard's view hierarchy independently of subscription size. */
internal object MobileProfilePaging {
    const val PAGE_SIZE = 100

    data class Window(val page: Int, val start: Int, val end: Int, val total: Int) {
        val hasPrevious get() = start > 0
        val hasNext get() = end < total
    }

    /** First open shows the selected server; later refreshes keep the user's current page. */
    fun window(ids: List<Long>, requestedPage: Int?, selectedId: Long): Window {
        val selectedPage = ids.indexOf(selectedId).coerceAtLeast(0) / PAGE_SIZE
        val page = (requestedPage ?: selectedPage).coerceIn(0, ((ids.size - 1).coerceAtLeast(0)) / PAGE_SIZE)
        val start = page * PAGE_SIZE
        return Window(page, start, minOf(start + PAGE_SIZE, ids.size), ids.size)
    }

    /** Dragging a page changes only those positions, preserving servers on every other page. */
    fun reorder(all: List<Long>, visible: List<Long>, reordered: List<Long>): List<Long> {
        if (visible.size != reordered.size || visible.toSet() != reordered.toSet()) return all
        val positions = visible.toSet()
        val remaining = reordered.iterator()
        return all.map { if (it in positions) remaining.next() else it }
    }
}
