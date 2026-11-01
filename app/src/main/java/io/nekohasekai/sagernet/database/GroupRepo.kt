package app.qthrone.mobile.database

import app.qthrone.mobile.SagerNet
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The group repository (the desktop's GroupsRepo, GroupsRepo.cpp): the `groups` table in tab order
 * (`display_order`), the current group (`current_group`) and the Default group of an empty table.
 * Listeners are in-process; writes of the other process arrive through Room's multi-instance invalidation.
 */
object GroupRepo {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)
        suspend fun groupRemoved(groupId: Long)

        /** The group's members changed (added, removed, reordered, sorted, cleared): reload them. */
        suspend fun groupUpdated(groupId: Long)

        /** The tab order changed. */
        suspend fun groupsReordered() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val dao get() = SagerDatabase.groupDao

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private suspend fun notify(what: suspend Listener.() -> Unit) {
        for (listener in listeners) listener.what()
    }

    // ------------------------------------------------------------------------------------------------ reads

    /** Every group in tab order; an empty table first gets the Default group. */
    fun all(): List<ProxyGroup> {
        val groups = dao.allGroups()
        if (groups.isNotEmpty()) {
            groups.filter { !it.isSubscription && it.name in legacyDefaultNames() }.forEach {
                it.name=SagerDatabase.defaultGroupName();dao.update(it)
            }
            // Once per install the server list goes above every group; a later manual move stays.
            if (!DataStore.serverListRaised) {
                DataStore.serverListRaised = true
                val list = groups.firstOrNull { !it.isSubscription && it.name == SagerDatabase.defaultGroupName() }
                if (list != null && list !== groups.first()) {
                    list.displayOrder = groups.first().displayOrder - 1; dao.update(list)
                    return dao.allGroups()
                }
            }
            return groups
        }
        ensureDefault()
        return dao.allGroups()
    }

    /** The group ids in tab order (GetGroupsTabOrder). */
    fun ids(): List<Long> {
        val ids = dao.allIds()
        if (ids.isNotEmpty()) return ids
        ensureDefault()
        return dao.allIds()
    }

    fun get(id: Long): ProxyGroup? = if (id > 0) dao.getById(id) else null

    /** current_group when it names a group, else the first group, which then becomes current. */
    fun currentId(): Long {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored > 0 && dao.getById(stored) != null) return stored
        val first = ids().first()
        setCurrent(first)
        return first
    }

    fun current(): ProxyGroup {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored > 0) dao.getById(stored)?.let { return it }
        val first = all().first()
        setCurrent(first.id)
        return first
    }

    fun setCurrent(id: Long) {
        val store = DataStore.configurationStore
        if (SettingsRegistry.CURRENT_GROUP.read(store) != id) SettingsRegistry.CURRENT_GROUP.write(store, id)
    }

    /** Configs.cpp:35-39: the "Default" group of an empty table. */
    fun ensureDefault() {
        dao.insertDefaultIfEmpty(SagerDatabase.defaultGroupName())
    }

    private fun legacyDefaultNames() = setOf("", "Default", "По умолчанию", "默认", "پیش فرض", SagerNet.application.getString(app.qthrone.mobile.R.string.group_default_name))

    /** Plain pasted or scanned profiles never become members of the selected subscription. */
    fun serverListId(): Long = SagerDatabase.instance.runInTransaction(Callable {
        val names=legacyDefaultNames()+setOf(SagerDatabase.defaultGroupName(),"Server list","Список серверов")
        val group=dao.allGroups().firstOrNull { !it.isSubscription && it.name in names }
        if(group!=null) {
            if(group.name!=SagerDatabase.defaultGroupName()) { group.name=SagerDatabase.defaultGroupName();dao.update(group) }
            group.id
        } else dao.insert(ProxyGroup(name=SagerDatabase.defaultGroupName(),
            // A new server list starts above every group and subscription.
            displayOrder=(dao.allGroups().minOfOrNull { it.displayOrder } ?: 1L) - 1))
    })

    // ------------------------------------------------------------------------------------------------ writes

    /** GroupsRepo::AddGroup: a new id and the last tab (display_order = MAX + 1). */
    suspend fun add(group: ProxyGroup): ProxyGroup {
        SagerDatabase.instance.runInTransaction {
            group.id = 0L
            group.displayOrder = dao.nextDisplayOrder()
            group.id = dao.insert(group)
        }
        notify { groupAdd(group) }
        return group
    }

    suspend fun save(group: ProxyGroup) {
        dao.update(group)
        notify { groupUpdated(group) }
    }

    /**
     * Stops a running member before deleting the group and its profiles, including the last group.
     */
    suspend fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids) return@Callable null
            ids - id
        }) ?: return false
        val running = ProfileManager.runningProfileId()
        if (running > 0 && ProfileManager.getProfile(running)?.groupId == id) SagerNet.stopService()
        dao.deleteById(id)
        if (SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore) == id) setCurrent(remaining.firstOrNull() ?: 0L)
        if (DataStore.selectedProxy > 0 && ProfileManager.getProfile(DataStore.selectedProxy) == null) {
            DataStore.selectedProxy = 0L
        }
        notify { groupRemoved(id) }
        return true
    }

    /** SetGroupsTabOrder: [ids] become the tabs 0..n-1; groups missing from [ids] follow in their current order. */
    suspend fun setDisplayOrder(ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val existing = dao.allIds()
            val ordered = ids.distinct().filter { it in existing } + existing.filter { it !in ids }
            ordered.forEachIndexed { index, groupId -> dao.setDisplayOrder(groupId, index.toLong()) }
        }
        notify { groupsReordered() }
    }

    /** Tells the listeners to reload the members of [groupId]. */
    suspend fun postReload(groupId: Long) {
        notify { groupUpdated(groupId) }
    }

    /** Tells the listeners that [group] changed (name, options...) without writing it. */
    suspend fun postUpdate(group: ProxyGroup) {
        notify { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(get(groupId) ?: return)
    }
}
