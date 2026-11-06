package app.rawline.mobile.ui.test

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.rawline.mobile.R
import app.rawline.mobile.aidl.ICoreService
import app.rawline.mobile.aidl.ITestSessionCallback
import app.rawline.mobile.bg.CoreServiceClient
import app.rawline.mobile.bg.proto.SpeedTestSnapshot
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Main-process side of a test session: binds the :bg CoreService for the session, starts it with
 * `ICoreService.startTest` and mirrors every [ITestSessionCallback] event in [state] (at most every 100 ms).
 * :bg persists the results; this only mirrors them. The object outlives activities, so the panel survives rotation.
 */
object TestSessionClient {

    const val RANK_LIMIT = 10

    private const val PUBLISH_INTERVAL_MS = 100L
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val STOP_TIMEOUT_MS = 2_000L
    private const val PRUNE_DELAY_MS = 2_000L
    private const val BUSY_WAIT_MS = 10_000L
    private const val BUSY_RETRY_MS = 300L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Logs.w(e) }
    )
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val mutableState = MutableStateFlow(TestUiState())

    val state: StateFlow<TestUiState> get() = mutableState

    /** The panel's expanded flag, kept here so it survives the fragment. */
    @Volatile
    var panelExpanded = false

    private var model: TestSessionModel? = null
    private val pendingPings=java.util.ArrayDeque<Pair<TestSpec,Long>>()
    private val mutableQueuedProfiles=MutableStateFlow<Set<Long>>(emptySet())
    val queuedProfiles: StateFlow<Set<Long>> get()=mutableQueuedProfiles
    private var queueJob: Job? = null
    private var queueEpoch=0L

    private fun publishQueueLocked() {
        mutableQueuedProfiles.value=pendingPings.flatMap { it.first.profileIds.toList() }.toSet()
    }
    private fun advanceQueueLocked() {
        if(pendingPings.isEmpty() || queueJob?.isActive==true) return
        val epoch=queueEpoch
        queueJob=scope.launch {
            delay(200)
            synchronized(lock) {
                queueJob=null
                if(epoch!=queueEpoch || model?.running==true || pendingPings.isEmpty()) return@synchronized
                val waiting=pendingPings.toList()
                pendingPings.clear()
                val ids=waiting.flatMap { it.first.profileIds.toList() }.distinct().toLongArray()
                val group=waiting.map { it.second }.distinct().singleOrNull() ?: 0L
                start(waiting.first().first.copy(profileIds=ids),group)
                publishQueueLocked()
            }
        }
    }
    private var publishJob: Job? = null
    private var lastPublish = 0L
    private val clearedResults = mutableSetOf<Long>()
    fun clearResults(ids: List<Long>) { synchronized(lock) { clearedResults.addAll(ids); publishLocked() } }


    /** Starts [spec]; false when another session is running. */
    fun start(spec: TestSpec): Boolean = start(spec, 0L)

    /**
     * Starts [spec]; [groupId] names the tested group for the panel's sort action. With 0 the group is inferred
     * when every profile of the spec belongs to one group.
     */
    fun start(spec: TestSpec, groupId: Long): Boolean {
        var restart: LongArray? = null
        synchronized(lock) {
            clearedResults.removeAll(spec.profileIds.toSet())
            if(model?.running==true && spec.kind==TestSpec.KIND_URL && !spec.testCurrent) {
                val active=mutableState.value.rows.filterValues { it.phase!=RowPhase.DONE }.keys
                val waiting=mutableQueuedProfiles.value
                val ping=model?.let { it.kind==TestSpec.KIND_URL && !it.testCurrent }==true
                if(!ping) {
                    // Speed tests stay exclusive: a ping waits for them.
                    val ids=spec.profileIds.filter { it !in active && it !in waiting }.toLongArray()
                    if(ids.isNotEmpty()) pendingPings.addLast(spec.copy(profileIds=ids) to groupId)
                    publishQueueLocked()
                    return true
                }
                if(spec.profileIds.any { it in active || it in waiting }) {
                    // Pressing ping again restarts it: the running sweep stops, its unfinished profiles start over.
                    restart=(spec.profileIds.toList()+active+waiting).distinct().toLongArray()
                } else {
                    pendingPings.addLast(spec to groupId)
                    publishQueueLocked()
                    return true
                }
            }
        }
        restart?.let { ids ->
            stop()
            synchronized(lock) {
                pendingPings.addLast(spec.copy(profileIds=ids) to groupId)
                publishQueueLocked()
                advanceQueueLocked()
            }
            return true
        }
        val m = begin(spec.kind, spec.scopeLabel, groupId, spec.testCurrent) ?: return false
        synchronized(lock) { m.setIds(spec.profileIds); publishLocked() }
        prepare(m) {
            if (groupId <= 0 && !spec.testCurrent && spec.profileIds.isNotEmpty()) {
                val gid = ProfileManager.getProfile(spec.profileIds[0])?.groupId ?: 0L
                if (gid > 0) {
                    val members = ProfileManager.memberIds(gid).toHashSet()
                    if (spec.profileIds.all { it in members }) synchronized(lock) { m.groupId = gid }
                }
            }
            spec
        }
        return true
    }

    /** Tests every member of group [groupId] in list order. */
    fun startGroup(kind: Int, groupId: Long): Boolean {
        if (kind == TestSpec.KIND_URL && synchronized(lock) { model?.let { it.running && it.kind == TestSpec.KIND_URL && !it.testCurrent } == true }) {
            scope.launch(Dispatchers.IO) {
                val group = GroupRepo.get(groupId) ?: return@launch
                start(TestSpec(kind, ProfileManager.memberIds(groupId).toLongArray(), scopeLabel = group.displayName(), sortGroupId = groupId), groupId)
            }
            return true
        }
        val m = begin(kind, "", groupId, false) ?: return false
        prepare(m) {
            val group = GroupRepo.get(groupId)
            val ids = if (group == null) emptyList() else ProfileManager.memberIds(groupId)
            TestSpec(kind, ids.toLongArray(), scopeLabel = group?.displayName().orEmpty(),sortGroupId=groupId)
        }
        return true
    }

    /** Tests [profileIds] (a selection or one profile); pass their [groupId] to offer "Sort" afterwards. */
    fun startProfiles(kind: Int, profileIds: List<Long>, groupId: Long = 0L): Boolean {
        if (kind == TestSpec.KIND_URL && synchronized(lock) { model?.let { it.running && it.kind == TestSpec.KIND_URL && !it.testCurrent } == true })
            return start(TestSpec(kind, profileIds.distinct().toLongArray()), groupId)
        val m = begin(kind, "", groupId, false) ?: return false
        prepare(m) {
            val ids = profileIds.distinct()
            val label = if (ids.size == 1) {
                ProfileManager.getProfile(ids[0])?.let(::profileLabel).orEmpty()
            } else {
                app.getString(R.string.test_panel_scope_selected, ids.size)
            }
            TestSpec(kind, ids.toLongArray(), scopeLabel = label)
        }
        return true
    }

    /** Tests the running connection (URL or speed). */
    fun startCurrent(kind: Int): Boolean {
        val label = app.getString(R.string.test_panel_scope_current)
        val m = begin(kind, label, 0L, true) ?: return false
        prepare(m) {
            // The engine ignores profileIds of a test-current spec; the id only keys the row.
            val runningId = ProfileManager.runningProfileId()
            TestSpec(
                kind, if (runningId > 0) longArrayOf(runningId) else LongArray(0), testCurrent = true,
                scopeLabel = label,
            )
        }
        return true
    }

    /** Stops the running session; the engine reports the remaining profiles as aborted. */
    fun stop() {
        synchronized(lock) {
            queueEpoch++;queueJob?.cancel();queueJob=null
            pendingPings.clear();publishQueueLocked()
        }
        var target: TestSessionModel? = null
        var service: ICoreService? = null
        synchronized(lock) {
            val m = model ?: return
            if (!m.running || m.stopRequested) return
            m.stopRequested = true
            m.stopping = true
            if (m.service == null && m.unbind == null) {
                // Still resolving the scope: nothing reached :bg yet.
                finishLocked(m, cancelled = true, failure = TestFailure.NONE)
                return
            }
            // Without a session id startTest is still in flight; its completion sends the stop.
            service = if (m.session != 0) m.service else null
            target = m
            publishLocked()
        }
        val m = target ?: return
        service?.let(::stopRemote)
        scope.launch {
            delay(STOP_TIMEOUT_MS)
            synchronized(lock) { if (m.running) finishLocked(m, cancelled = true, failure = TestFailure.NONE) }
        }
    }

    /** Stops whatever session :bg runs (one this process does not own, e.g. after the UI process restarted). */
    fun stopBackgroundSession() {
        scope.launch(Dispatchers.IO) {
            runCatching { CoreServiceClient.call { it.stopTests() } }.onFailure { Logs.w(it) }
        }
    }

    /** Hides a finished session. */
    fun dismiss() {
        synchronized(lock) {
            if (model?.running == true) return
            model = null
            publishJob?.cancel()
            publishJob = null
            mutableState.value = TestUiState()
        }
    }

    /** Records profiles deleted through the panel so the remove action is not offered again. */
    fun markRemoved(profileIds: Collection<Long>) {
        synchronized(lock) {
            val m = model ?: return
            m.markRemoved(profileIds)
            publishLocked()
        }
    }

    // ------------------------------------------------------------------------------------------------ session

    private fun begin(kind: Int, label: String, groupId: Long, testCurrent: Boolean): TestSessionModel? {
        synchronized(lock) {
            if (model?.running == true) return null
            val m = TestSessionModel(kind, label, groupId, testCurrent)
            model = m
            publishJob?.cancel()
            publishJob = null
            publishLocked()
            return m
        }
    }

    private fun prepare(m: TestSessionModel, resolve: suspend () -> TestSpec) {
        scope.launch(Dispatchers.IO) {
            val spec = try {
                val resolved = resolve()
                val speedMode = if (resolved.speedMode >= 0) resolved.speedMode else DataStore.speedTestMode
                val speedTimeoutMs = resolved.speedTimeoutMs.takeIf { it > 0 } ?: DataStore.speedTestTimeoutMs
                val concurrency = resolved.concurrency.takeIf { it > 0 } ?: DataStore.testConcurrent
                synchronized(lock) {
                    if (!m.running) return@launch
                    m.speedMode = speedMode
                    m.speedTimeoutMs = speedTimeoutMs
                    m.concurrency = concurrency
                    if (resolved.scopeLabel.isNotEmpty()) m.scopeLabel = resolved.scopeLabel
                    clearedResults.removeAll(resolved.profileIds.toSet())
                    m.setIds(resolved.profileIds)
                    publishLocked()
                }
                resolved
            } catch (e: Exception) {
                Logs.w(e)
                synchronized(lock) { finishLocked(m, cancelled = true, failure = TestFailure.UNREACHABLE) }
                return@launch
            }
            if (!spec.testCurrent && spec.profileIds.isEmpty()) {
                synchronized(lock) { finishLocked(m, cancelled = false, failure = TestFailure.EMPTY) }
                return@launch
            }
            connect(m, spec)
        }
    }

    private fun connect(m: TestSessionModel, spec: TestSpec) {
        val callback = SessionCallback(m)
        val unbind = try {
            CoreServiceClient.bindPersistent(
                onConnected = { service -> onConnected(m, spec, callback, service) },
                onDisconnected = {
                    synchronized(lock) {
                        if (m.running) finishLocked(m, cancelled = true, failure = TestFailure.CORE_DIED) else release(m)
                    }
                },
            )
        } catch (e: Exception) {
            Logs.w(e)
            synchronized(lock) { finishLocked(m, cancelled = true, failure = TestFailure.UNREACHABLE) }
            return
        }
        synchronized(lock) {
            m.unbind = unbind
            if (!m.running) release(m)
        }
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            synchronized(lock) {
                if (m.running && m.service == null) finishLocked(m, cancelled = true, failure = TestFailure.UNREACHABLE)
            }
        }
    }

    private fun onConnected(m: TestSessionModel, spec: TestSpec, callback: SessionCallback, service: ICoreService) {
        synchronized(lock) {
            // A reconnection after :bg restarted: the session already ended with CORE_DIED.
            if (m.service != null || !m.running) return
            m.service = service
            if (m.stopRequested) {
                finishLocked(m, cancelled = true, failure = TestFailure.NONE)
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            fun startRemote() = try {
                service.startTest(spec, callback)
            } catch (e: Exception) {
                Logs.w(e)
                -2
            }
            var session = startRemote()
            // :bg may still be closing the last sweep's probes; a connection check waits for it instead of failing.
            var waited = 0L
            while (spec.testCurrent && session == -1 && waited < BUSY_WAIT_MS &&
                synchronized(lock) { m.running && !m.stopRequested }) {
                delay(BUSY_RETRY_MS)
                waited += BUSY_RETRY_MS
                session = startRemote()
            }
            var stopNow = false
            synchronized(lock) {
                if (!m.running) {
                    stopNow = session > 0
                } else when {
                    session > 0 -> {
                        if (m.session == 0) m.session = session
                        m.preparing = false
                        stopNow = m.stopRequested
                        publishLocked()
                    }

                    session == -1 -> {
                        if(spec.kind==TestSpec.KIND_URL && !spec.testCurrent && !m.stopRequested) {
                            pendingPings.addFirst(spec to m.groupId);publishQueueLocked()
                            finishLocked(m,cancelled=false,failure=TestFailure.NONE)
                        } else finishLocked(m, cancelled = false, failure = TestFailure.BUSY)
                    }
                    else -> finishLocked(m, cancelled = true, failure = TestFailure.UNREACHABLE)
                }
            }
            if (stopNow) stopRemote(service)
        }
    }

    private fun stopRemote(service: ICoreService) {
        scope.launch(Dispatchers.IO) {
            runCatching { service.stopTests() }.onFailure { Logs.w(it) }
        }
    }

    private fun finishLocked(m: TestSessionModel, cancelled: Boolean, failure: TestFailure) {
        if (!m.running) return
        m.finish(cancelled || m.stopRequested, failure)
        if (model === m) {
            publishJob?.cancel()
            publishJob = null
            publishLocked()
        }
        release(m)
        if (m.kind != TestSpec.KIND_IP && !m.testCurrent) prune(m)
        advanceQueueLocked()
    }

    // :bg may delete the failed profiles itself (auto_clear_unavailable); keep the remove action's count honest.
    private fun prune(m: TestSessionModel) {
        scope.launch(Dispatchers.IO) {
            delay(PRUNE_DELAY_MS)
            val failed = synchronized(lock) { if (model === m) m.failedIds() else emptyList() }
            if (failed.isEmpty()) return@launch
            val existing = try {
                ProfileManager.getProfiles(failed).mapTo(HashSet()) { it.id }
            } catch (e: Exception) {
                Logs.w(e)
                return@launch
            }
            val gone = failed.filter { it !in existing }
            if (gone.isEmpty()) return@launch
            synchronized(lock) {
                if (model !== m) return@launch
                m.markRemoved(gone)
                publishLocked()
            }
        }
    }

    private fun release(m: TestSessionModel) {
        val unbind = m.unbind ?: return
        m.unbind = null
        m.service = null
        main.post(unbind)
    }

    // ------------------------------------------------------------------------------------------------ publishing

    private fun schedulePublishLocked() {
        if (publishJob != null) return
        val wait = PUBLISH_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastPublish)
        if (wait <= 0) {
            publishLocked()
            return
        }
        publishJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                publishJob = null
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        val m = model ?: return
        lastPublish = SystemClock.elapsedRealtime()
        mutableState.value = m.snapshot().let { snapshot -> snapshot.copy(rows = snapshot.rows.filterKeys { it !in clearedResults }) }
        val missing = m.takeMissingNames()
        if (missing.isNotEmpty()) resolveNames(m, missing)
    }

    private fun resolveNames(m: TestSessionModel, ids: List<Long>) {
        scope.launch(Dispatchers.IO) {
            val found = try {
                ProfileManager.getProfiles(ids).associate { it.id to profileLabel(it) }
            } catch (e: Exception) {
                Logs.w(e)
                emptyMap()
            }
            synchronized(lock) {
                m.putNames(ids.associateWith { found[it].orEmpty() })
                if (model === m) schedulePublishLocked()
            }
        }
    }

    private fun profileLabel(profile: ProxyEntity): String =
        profile.name?.takeIf { it.isNotBlank() } ?: profile.displayName()

    private fun event(m: TestSessionModel, block: TestSessionModel.() -> Unit) {
        synchronized(lock) {
            if (!m.running) return
            m.block()
            if (model === m) schedulePublishLocked()
        }
    }

    private class SessionCallback(private val m: TestSessionModel) : ITestSessionCallback.Stub() {

        override fun onStarted(session: Int, kind: Int, total: Int) {
            synchronized(lock) {
                if (!m.running) return
                m.onStarted(session, total)
                if (model === m) publishLocked()
            }
        }

        override fun onUrlResult(profileId: Long, latency: Int, error: String?) =
            event(m) { onUrlResult(profileId, latency, error.orEmpty()) }

        override fun onIpResult(profileId: Long, ip: String?, country: String?, error: String?) =
            event(m) { onIpResult(profileId, ip.orEmpty(), country.orEmpty(), error.orEmpty()) }

        override fun onSpeedProgress(snapshot: SpeedTestSnapshot?) {
            if (snapshot != null) event(m) { onSpeedProgress(snapshot) }
        }

        override fun onSpeedResult(
            profileId: Long, dl: String?, ul: String?, latency: Int, country: String?, error: String?,
        ) = event(m) { onSpeedResult(profileId, dl.orEmpty(), ul.orEmpty(), latency, country.orEmpty(), error.orEmpty()) }

        override fun onDone(session: Int, cancelled: Boolean) {
            synchronized(lock) { finishLocked(m, cancelled, TestFailure.NONE) }
        }
    }
}
