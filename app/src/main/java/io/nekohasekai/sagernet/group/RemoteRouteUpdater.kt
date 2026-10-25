package app.qthrone.mobile.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.database.RouteRepo
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.fetchText
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.ktx.runOnIoDispatcher
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RouteShare
import app.qthrone.mobile.route.RuleSets
import app.qthrone.mobile.route.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Remote route profiles (RouteUpdater.cpp) and their periodic auto update (route_auto_update). */
object RemoteRouteUpdater {

    /** route_auto_update is sign-encoded minutes and only takes effect from 30 (mainwindow_setup.cpp:1095-1096). */
    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "RemoteRouteUpdater"

    /**
     * RouteUpdate::UpdateProfile (RouteUpdater.cpp:12-51) without saving: fetches [p]'s URL through the rule-set
     * mirror and replaces its rules and default outbound, keeping a non-empty local name. [p] is left unchanged on
     * error, which is returned.
     */
    fun fetchInto(p: RouteProfile, warnings: MutableList<String>? = null): String? {
        if (!p.is_remote) return "not a remote routing profile"
        val url = p.remote_url.trim()
        if (url.isEmpty()) return "remote URL is empty"
        val body = try {
            fetchText(RuleSets.mirrorLink(url, DataStore.rulesetMirror)).body
        } catch (e: Exception) {
            return e.readableMessage
        }
        return applyContent(p, body, warnings, fetched = true)
    }

    /** [fetchInto] and save; returns the error, or null. A saved profile deleted meanwhile is not recreated. */
    fun update(p: RouteProfile): String? {
        val warnings = ArrayList<String>()
        fetchInto(p, warnings)?.let { return it }
        if (p.id > 0L && RouteManager.get(p.id) == null) return "the routing profile no longer exists"
        RouteManager.save(p)
        if (warnings.isNotEmpty()) Logs.w("Remote routing profile ${p.name}: ${warnings.joinToString("; ")}")
        return null
    }

    /** UI_update_all_remote_routes (RouteUpdater.cpp:54-78): (updated count, "name: error" failures). */
    fun updateAll(onlyAutoUpdate: Boolean): Pair<Int, List<String>> {
        var updated = 0
        val failures = ArrayList<String>()
        for (id in RouteManager.all().map { it.id }) {
            val p = RouteManager.get(id) ?: continue
            if (!p.is_remote || p.remote_url.isBlank()) continue
            if (onlyAutoUpdate && !p.auto_update) continue
            Logs.i("Updating remote routing profile: ${p.name}")
            val error = update(p)
            if (error != null) {
                failures.add("${p.name}: $error")
                Logs.w("Remote routing profile ${p.name} failed: $error")
                continue
            }
            updated++
        }
        Logs.i("Remote routing profiles: $updated updated, ${failures.size} failed")
        return updated to failures
    }

    /**
     * handle_add_remote_routes (mainwindow_deeplink.cpp:179-219): each entry is saved at once, prefilled from the
     * routeprofiles snapshot when it has the URL so it works offline, then fetched online in the background.
     * Returns the new profile ids; current_route_id is not changed.
     */
    fun addRemote(entries: List<RouteShare.RemoteEntry>, autoUpdate: Boolean): List<Long> {
        val ids = entries.map { entry ->
            val p = RouteProfile().apply {
                name = entry.name
                is_remote = true
                remote_url = entry.url
                auto_update = autoUpdate
            }
            RouteRepo.snapshot(entry.url)?.let { applyContent(p, it, null, fetched = false) }
            RouteManager.save(p)
        }
        if (ids.isNotEmpty()) runOnIoDispatcher {
            var fetched = 0
            for (id in ids) {
                val p = RouteManager.get(id) ?: continue
                val error = update(p)
                if (error == null) fetched++ else Logs.w("Remote routing profile ${p.name} failed: $error")
            }
            Logs.i("Added remote routing profiles: $fetched of ${ids.size} fetched")
        }
        return ids
    }

    /**
     * The swap of UpdateProfile: rules, default outbound and an empty name are taken; a fetched `dns` is not, so [p]
     * keeps its own DNS settings. Rules switched off locally stay off, matched by name.
     */
    private fun applyContent(p: RouteProfile, content: String, warnings: MutableList<String>?, fetched: Boolean): String? {
        val imported = RouteShare.fromShareInput(content, RouteManager.profileIdResolver())
        val remote = imported.profile
            ?: return imported.fatal.ifEmpty { "could not parse a routing profile from the response" }
        val disabledNames = p.rules.filter { it.disabled && it.type != RuleType.ENDPOINT_PREFERRED_BY.id }
            .mapTo(HashSet()) { it.name }
        for (rule in remote.rules) if (rule.name in disabledNames) rule.disabled = true
        p.rules = remote.rules
        p.default_outbound_id = remote.default_outbound_id
        if (p.name.isBlank() && remote.name.isNotBlank()) p.name = remote.name
        if (fetched) p.remote_last_update = System.currentTimeMillis() / 1000
        warnings?.addAll(imported.warnings)
        return null
    }

    /**
     * (Re)schedules the periodic update from route_auto_update, or cancels it below [MIN_INTERVAL_MINUTES]. Call it
     * after the interval changes; [keepExisting] (app start) leaves an already scheduled update alone.
     */
    fun schedule(keepExisting: Boolean = false) {
        runOnDefaultDispatcher {
            val workManager = RemoteWorkManager.getInstance(app)
            val interval = DataStore.routeAutoUpdate
            try {
                if (interval < MIN_INTERVAL_MINUTES) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val period = interval * 60L
                val now = System.currentTimeMillis() / 1000
                val initialDelay = (DataStore.routeAutoUpdateLast + period - now).coerceIn(0L, period)
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, interval.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process, like the subscription updater.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putInt(KEY_INTERVAL, interval)
                            .build()
                    )
                    .build()
                val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
                workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request).await()
            } catch (e: Throwable) {
                Logs.w("RemoteRouteUpdater: scheduling failed", e)
            }
        }
    }

    private const val KEY_INTERVAL = "route_auto_update"

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            val interval = DataStore.routeAutoUpdate
            // The setting changed without a reschedule (e.g. a restored backup): follow it from now on.
            if (inputData.getInt(KEY_INTERVAL, interval) != interval) schedule()
            if (interval < MIN_INTERVAL_MINUTES) return Result.success()
            val now = System.currentTimeMillis() / 1000
            // A run within half an interval of the last one is a re-enqueue, not a new period.
            if (now - DataStore.routeAutoUpdateLast < interval * 60L / 2) return Result.success()
            DataStore.routeAutoUpdateLast = now
            withContext(Dispatchers.IO) { updateAll(true) }
            return Result.success()
        }
    }
}
