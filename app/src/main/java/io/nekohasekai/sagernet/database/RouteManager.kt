package app.qthrone.mobile.database

import androidx.room.InvalidationTracker
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.runOnIoDispatcher
import app.qthrone.mobile.route.RouteDns
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RuleSetCatalog
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/** The route profile repository (the desktop's RoutesRepo) over `route_profiles` / `route_rules`. */
object RouteManager {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private var observing = false

    private val dao get() = SagerDatabase.routeDao

    /** Every profile in id order; an empty table is given the Default profile first. */
    fun all(): List<RouteProfile> {
        val profiles = load()
        if (profiles.isNotEmpty()) return profiles
        ensureDefault()
        return load()
    }

    fun get(id: Long): RouteProfile? = SagerDatabase.instance.runInTransaction(Callable {
        dao.getProfile(id)?.toModel(dao.rulesOf(id))
    })

    /**
     * GetCustomDns (RoutesRepo.cpp): the profile's ActiveDns without loading its rules; null when it follows the
     * global DNS settings or does not exist.
     */
    fun customDns(id: Long): RouteDns? = RouteProfileEntity.dnsFromJson(dao.customDnsJson(id))

    /**
     * The profile named by current_route_id; a missing or raw one falls back to the first profile Android can use
     * (a new Default one when only raw profiles exist) and fixes the setting.
     */
    fun current(): RouteProfile {
        get(DataStore.currentRouteId)?.takeIf { !it.is_raw }?.let { return it }
        val first = usable().firstOrNull() ?: RouteProfile.defaultProfile().also { save(it) }
        DataStore.currentRouteId = first.id
        return first
    }

    /** Every profile but the desktop's raw ones, which Android keeps read-only and never uses. */
    fun usable(): List<RouteProfile> = all().filterNot { it.is_raw }

    /** Inserts when [p] has no id (and assigns it), else upserts the row; the rules are replaced in list order. */
    fun save(p: RouteProfile): Long {
        SagerDatabase.instance.runInTransaction {
            val entity = RouteProfileEntity.of(p)
            if (p.id <= 0L) {
                entity.id = 0L
                p.id = dao.insertProfile(entity)
            } else if (dao.updateProfile(entity) == 0) {
                dao.insertProfile(entity)
            }
            dao.deleteRules(p.id)
            if (p.rules.isNotEmpty()) {
                dao.insertRules(p.rules.mapIndexed { index, rule -> RouteRuleEntity.of(p.id, index, rule) })
            }
        }
        return p.id
    }

    /** Refuses to delete the last profile; deleting the current one makes the first remaining profile current. */
    fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids || ids.size <= 1) return@Callable null
            dao.deleteProfile(id)
            ids - id
        }) ?: return false
        if (DataStore.currentRouteId == id) DataStore.currentRouteId = remaining.first()
        return true
    }

    fun ensureDefault() {
        SagerDatabase.instance.runInTransaction {
            if (dao.count() == 0L) save(RouteProfile.defaultProfile())
        }
    }

    /**
     * [l] runs on a background thread after any change to the route tables, including changes made by the
     * other process (the database has multi-instance invalidation).
     */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
        observe()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** The name of a server profile as shares carry it (the desktop's DisplayName). */
    fun profileName(id: Long): String? = ProfileManager.getProfile(id)?.displayName()

    /**
     * A share-import resolver for rule outbound names: the first profile whose name matches exactly
     * (ProfilesRepo::GetProfileByName), then one whose display name does. The profiles are read on first use.
     */
    fun profileIdResolver(): (String) -> Long? {
        val profiles by lazy { SagerDatabase.proxyDao.getAll().sortedBy { it.id } }
        return { name ->
            if (name.isBlank()) null
            else profiles.firstOrNull { it.outbound.name == name }?.id
                ?: profiles.firstOrNull { it.displayName() == name }?.id
        }
    }

    @Volatile
    private var catalogCache: Pair<Long, RuleSetCatalog>? = null

    /** The rule-set list: the runtime copy of srslist.h, else the bundled asset, else empty; cached per source. */
    fun catalog(): RuleSetCatalog {
        val file = RouteRepo.srsListFile()
        val stamp = if (file.isFile) file.lastModified() else ASSET_STAMP
        catalogCache?.let { if (it.first == stamp) return it.second }
        var catalog = if (stamp != ASSET_STAMP) readCatalog(file) else null
        if (catalog == null || catalog.size == 0) {
            catalog = RouteRepo.readAsset(RouteRepo.SRS_LIST)?.let { RuleSetCatalog.parseSrsList(it) }
                ?: RuleSetCatalog(emptyList())
        }
        catalogCache = stamp to catalog
        return catalog
    }

    fun invalidateCatalog() {
        catalogCache = null
    }

    private const val ASSET_STAMP = -1L

    private fun readCatalog(file: File): RuleSetCatalog? = try {
        RuleSetCatalog.parseSrsList(file.readText())
    } catch (e: Exception) {
        Logs.w("read ${file.name}", e)
        null
    }

    private fun load(): List<RouteProfile> = SagerDatabase.instance.runInTransaction(Callable {
        val rules = dao.allRules().groupBy { it.routeProfileId }
        dao.allProfiles().map { it.toModel(rules[it.id].orEmpty()) }
    })

    private fun observe() {
        synchronized(this) {
            if (observing) return
            observing = true
        }
        runOnIoDispatcher {
            SagerDatabase.instance.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(arrayOf(RouteProfileEntity.TABLE, RouteRuleEntity.TABLE)) {
                    override fun onInvalidated(tables: Set<String>) {
                        for (l in listeners) l()
                    }
                }
            )
        }
    }
}
