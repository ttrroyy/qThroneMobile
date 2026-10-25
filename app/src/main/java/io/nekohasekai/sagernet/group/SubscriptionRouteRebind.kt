package app.qthrone.mobile.group

import app.qthrone.mobile.R
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app

/**
 * Route rules and default outbounds name a server by profile id, so a refresh that replaces a node (a new identity,
 * "clear servers before updating", a filter) would leave them pointing at nothing. They follow the group's member of
 * the same name instead, when exactly one member has it; ambiguous or blank names are never guessed.
 */
internal object SubscriptionRouteRebind {

    /** The names of the [members] some route profile references, taken before the refresh deletes any of them. */
    fun referencedNames(members: List<ProxyEntity>): Map<Long, String> {
        val referenced = SagerDatabase.routeDao.referencedOutboundIds().toHashSet()
        if (referenced.isEmpty()) return emptyMap()
        val names = HashMap<Long, String>()
        for (profile in members) {
            if (profile.id in referenced) names[profile.id] = profile.displayName()
        }
        return names
    }

    /** Re-points the references to the [removed] ids of group [gid]; [names] is [referencedNames]' result. */
    fun rebind(gid: Long, removed: Collection<Long>, names: Map<Long, String>) {
        val orphans = removed.filter { !names[it].isNullOrBlank() }
        if (orphans.isEmpty()) return
        val byName = HashMap<String, Long>()
        val ambiguous = HashSet<String>()
        for (profile in ProfileManager.members(gid)) {
            if (profile.type == SubscriptionRefresh.AUTOSELECTOR) continue
            val name = profile.displayName()
            if (byName.put(name, profile.id) != null) ambiguous.add(name)
        }
        val dao = SagerDatabase.routeDao
        var rebound = 0
        SagerDatabase.instance.runInTransaction {
            for (old in orphans) {
                val name = names.getValue(old)
                if (name in ambiguous) continue
                val target = byName[name] ?: continue
                rebound += dao.replaceRuleOutbound(old, target) + dao.replaceDefaultOutbound(old, target)
            }
        }
        if (rebound > 0) Logs.i(app.getString(R.string.subs_routes_rebound, rebound))
    }
}
