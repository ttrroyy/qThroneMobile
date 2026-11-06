package app.rawline.mobile.ui.profiles

import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import java.util.concurrent.Callable

/** Single-field writes of the profiles screen (scroll position, sort sub-criteria, shown test items). */
internal object GroupFields {

    /**
     * Applies [change] to a fresh copy of group [groupId] inside one transaction and writes it only when something
     * changed, so a concurrent write of the other process (a subscription refresh) is not overwritten with stale
     * columns. Listeners are not notified. Returns the stored group, null when it no longer exists.
     */
    fun update(groupId: Long, change: (ProxyGroup) -> Unit): ProxyGroup? =
        SagerDatabase.instance.runInTransaction(Callable {
            val dao = SagerDatabase.groupDao
            val group = dao.getById(groupId) ?: return@Callable null
            val before = group.copy()
            change(group)
            if (group != before) dao.update(group)
            group
        })
}
