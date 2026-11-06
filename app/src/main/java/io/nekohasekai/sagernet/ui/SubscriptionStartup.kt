package app.rawline.mobile.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import app.rawline.mobile.aidl.ISubscriptionCallback
import app.rawline.mobile.bg.CoreServiceClient
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.GroupSort
import app.rawline.mobile.database.GroupSortAction
import app.rawline.mobile.database.GroupSortMethod
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.ServerSortRule
import app.rawline.mobile.group.SubscriptionQueue
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ui.test.TestSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Retain in-flight startup actions across rotation; connect through the currently resumed activity. */
internal object SubscriptionStartup {
    fun run(activity: MainActivity) {
        val coordinator = ViewModelProvider(activity)[SubscriptionStartupViewModel::class.java]
        coordinator.start()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                coordinator.pendingConnection.collect { id ->
                    if (id <= 0) return@collect
                    coordinator.consumeConnection()
                    if (!DataStore.serviceState.canStop) {
                        DataStore.selectedProxy = id
                        activity.toggleService()
                    }
                }
            }
        }
    }
}

class SubscriptionStartupViewModel : ViewModel() {
    val pendingConnection = MutableStateFlow(0L)
    private var started = false

    fun consumeConnection() { pendingConnection.value = 0L }

    fun start() {
        if (started) return
        started = true
        if (!DataStore.subUpdateOnOpen && !DataStore.subPingOnOpen && !DataStore.subConnectOnOpen) return
        viewModelScope.launch {
            try {
                val groups = withContext(Dispatchers.IO) { GroupRepo.all().filter { it.isSubscription && !it.archive } }
                if (DataStore.subUpdateOnOpen) refreshAndWait(groups.filterNot { it.skipAutoUpdate })
                if (DataStore.subPingOnOpen && !TestSessionClient.state.value.running) {
                    val ids = withContext(Dispatchers.IO) { groups.flatMap { ProfileManager.memberIds(it.id) } }
                    if (ids.isNotEmpty() && TestSessionClient.start(TestSpec(TestSpec.KIND_URL, ids.toLongArray()))) {
                        val result = TestSessionClient.state.first { !it.running }
                        if (!result.cancelled) withContext(Dispatchers.IO) {
                            for (group in groups) {
                                if (GroupRepo.get(group.id)?.subOptions?.effectiveSortRule == ServerSortRule.PING)
                                    GroupSort.sortProfiles(group.id, GroupSortAction(GroupSortMethod.BY_LATENCY))
                            }
                        }
                    }
                }
                if (DataStore.subConnectOnOpen && !DataStore.serviceState.canStop) {
                    val target = withContext(Dispatchers.IO) {
                        val requested = if (DataStore.subConnectTarget == "last") DataStore.currentProfile else DataStore.selectedProxy
                        ProfileManager.getProfile(requested) ?: ProfileManager.getProfile(DataStore.selectedProxy)
                    }
                    if (target != null && !DataStore.serviceState.canStop) {
                        pendingConnection.value = target.id
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("Subscription startup failed", e)
            }
        }
    }

    /** Keep the binder alive until all requested groups finish, including groups already being refreshed. */
    private suspend fun refreshAndWait(groups: List<ProxyGroup>) = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext
        val remaining = groups.mapTo(mutableSetOf()) { it.id }
        val done = CompletableDeferred<Unit>()
        val lock = Any()
        var requestsSent = false
        val callback = object : ISubscriptionCallback.Stub() {
            override fun onState(gid: Long, state: Int) = synchronized(lock) {
                if (groups.none { it.id == gid }) return@synchronized
                if (state == SubscriptionQueue.STATE_IDLE) remaining.remove(gid) else remaining.add(gid)
                if (requestsSent && remaining.isEmpty()) done.complete(Unit)
                Unit
            }
            override fun onReport(gid: Long, title: String?, text: String?, popup: Boolean) = Unit
            override fun onError(gid: Long, message: String?) = Unit
        }
        val timeout = groups.size.toLong() * (DataStore.subRequestTimeoutSeconds * 2L + 120L) * 1000L
        withTimeout(timeout) {
            CoreServiceClient.call { service ->
                service.registerSubscriptionCallback(callback)
                try {
                    for (group in groups) service.refreshGroup(group.id, false)
                    synchronized(lock) {
                        requestsSent = true
                        if (remaining.isEmpty()) done.complete(Unit)
                    }
                    done.await()
                } finally {
                    runCatching { service.unregisterSubscriptionCallback(callback) }
                }
            }
        }
    }
}
