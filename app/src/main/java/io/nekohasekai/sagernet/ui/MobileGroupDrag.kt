package app.rawline.mobile.ui

internal class MobileGroupDrag {
    data class Origin(val groupId: Long, val scrollY: Int)

    var origin: Origin? = null
        private set
    var previewGroupId: Long? = null
        private set
    var hoveredGroupId: Long? = null
        private set

    fun start(groupId: Long, scrollY: Int) {
        origin = Origin(groupId, scrollY)
        previewGroupId = null
        hoveredGroupId = null
    }

    fun hover(groupId: Long?): Boolean {
        if (origin == null || hoveredGroupId == groupId) return false
        hoveredGroupId = groupId
        return true
    }

    fun previewHovered(): Boolean {
        val target = hoveredGroupId ?: return false
        if (origin == null || previewGroupId == target) return false
        previewGroupId = target
        return true
    }

    fun finish(): Origin? = origin.also {
        origin = null
        previewGroupId = null
        hoveredGroupId = null
    }
}
