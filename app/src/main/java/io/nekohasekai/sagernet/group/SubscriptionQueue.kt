package app.qthrone.mobile.group

import android.net.Uri
import android.os.RemoteCallbackList
import app.qthrone.mobile.R
import app.qthrone.mobile.aidl.ISubscriptionCallback
import app.qthrone.mobile.bg.test.TestEngine
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.SubscriptionOptions
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The :bg subscription job queue behind ICoreService (GroupUpdater, GroupUpdater.cpp:321-446): jobs run one at a
 * time in FIFO order; a group is queued at most once; a batch refuses to start while the previous one runs.
 * Jobs requested over AIDL report their failures through [callbacks].
 */
object SubscriptionQueue {

    const val STATE_IDLE = 0
    const val STATE_QUEUED = 1
    const val STATE_RUNNING = 2

    const val ACTION_REMOVE_INVALID = "remove_invalid"
    const val ACTION_RESOLVE_DOMAINS = "resolve_domains"

    /** Suffix of the file next to an [importFile] path that receives the imported count. */
    const val IMPORT_COUNT_SUFFIX = ".count"

    /** The directory of [importFile] payloads, under the app's cache directory. */
    const val IMPORT_DIR = "import"


    val callbacks = RemoteCallbackList<ISubscriptionCallback>()

    /** SubscriptionGroupChanged: [disturbed] profiles were deleted or changed in place (auto selectors re-plan). */
    fun interface GroupChangedListener {
        fun onGroupChanged(gid: Long, disturbed: List<Long>)
    }

    private val groupChangedListeners = CopyOnWriteArrayList<GroupChangedListener>()

    fun addGroupChangedListener(listener: GroupChangedListener) {
        groupChangedListeners.addIfAbsent(listener)
    }

    private class Job(val gid: Long, val batch: Boolean, val foreground: Boolean, val run: suspend () -> Unit) {
    }

    // Guards the queue and the state events: a callback registered meanwhile never sees them out of order.
    private val lock = Any()
    private val queue = ArrayDeque<Job>()
    private val pending = HashSet<Long>()
    private var pendingBatch = 0
    private var draining = false
    private var runningGid = -1L

    /** Queued or running jobs plus URL-test follow-ups not yet queued. */
    private val outstanding = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------------------------------------ callbacks

    fun register(callback: ISubscriptionCallback) {
        synchronized(lock) {
            callbacks.register(callback)
            for (gid in pending) {
                runCatching { callback.onState(gid, if (gid == runningGid) STATE_RUNNING else STATE_QUEUED) }
            }
        }
    }

    fun unregister(callback: ISubscriptionCallback) {
        callbacks.unregister(callback)
    }

    private inline fun broadcast(action: (ISubscriptionCallback) -> Unit) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        action(callbacks.getBroadcastItem(i))
                    } catch (_: Exception) {
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun stateLocked(gid: Long, state: Int) = broadcast { it.onState(gid, state) }

    internal fun report(gid: Long, title: String, text: String, popup: Boolean) =
        broadcast { it.onReport(gid, title, text, popup) }

    internal fun error(gid: Long, message: String) = broadcast { it.onError(gid, message) }

    internal fun groupChanged(gid: Long, disturbed: List<Long>) {
        for (listener in groupChangedListeners) {
            runCatching { listener.onGroupChanged(gid, disturbed) }.onFailure { Logs.w(it) }
        }
    }

    // ------------------------------------------------------------------------------------------------ entry points

    /** RefreshGroup (GroupUpdater.cpp:331-345); [showDiff] asks for the change window (manual refreshes). */
    fun refreshGroup(gid: Long, showDiff: Boolean) = refreshGroup(gid, showDiff, foreground = true)

    fun refreshGroup(gid: Long, showDiff: Boolean, foreground: Boolean) {
        val queued = synchronized(lock) {
            if (gid in pending) return@synchronized false
            pending.add(gid)
            enqueueLocked(Job(gid, batch = false, foreground) { SubscriptionRefresh.refresh(gid, showDiff, foreground) })
            true
        }
        if (!queued) Logs.i(app.getString(R.string.subs_already_queued, groupLabel(gid)))
    }

    /**
     * RefreshAll (GroupUpdater.cpp:347-365): every subscription group in tab order except archived ones and, with
     * [onlyAllowed] (the automatic update), those that skip automatic updates.
     */
    fun refreshAll(onlyAllowed: Boolean) = refreshAll(onlyAllowed, foreground = true)

    fun refreshAll(onlyAllowed: Boolean, foreground: Boolean) {
        val groups = GroupRepo.all()
        val refused = synchronized(lock) {
            if (pendingBatch > 0) return@synchronized true
            for (group in groups) {
                if (group.url.isEmpty() || group.archive || (onlyAllowed && group.skipAutoUpdate)) continue
                val gid = group.id
                if (gid in pending) continue
                pending.add(gid)
                pendingBatch++
                enqueueLocked(Job(gid, batch = true, foreground) { SubscriptionRefresh.refresh(gid, false, foreground) })
            }
            false
        }
        if (refused) {
            val message = app.getString(R.string.subs_batch_running)
            Logs.i(message)
            if (foreground) error(-1L, message)
        }
    }

    /**
     * SubscribeUrl (GroupUpdater.cpp:367-379) and the addsub deep link: a subscription group named [name] (the URL
     * host when blank) that skips automatic updates unless [autoUpdate]; its first refresh is queued without the
     * change window. Returns the group id, -1 on failure.
     */
    fun subscribeUrl(url: String, name: String, autoUpdate: Boolean, allowInsecureRequests: Boolean = false): Long {
        val wrapped = try { ProviderLinks.decodeChecked(url) } catch (_: Exception) { return -1L }
        val content = wrapped?.data ?: url.trim()
        if (!SubscriptionCrypto.isHttpUrl(content) && (wrapped != null || !content.startsWith("content://", true))) return -1L
        val group = try {
            runBlocking {
                GroupRepo.add(
                    ProxyGroup(
                        name = name.trim().ifEmpty { wrapped?.name.orEmpty().ifEmpty { runCatching { Uri.parse(content).host }.getOrNull().orEmpty() } },
                        // Retain the original encrypted wire format for sharing and backup, never its decoded URL.
                        url = if (wrapped?.encrypted == true) url.trim() else content,
                        skipAutoUpdate = !autoUpdate,
                        subOptions = SubscriptionOptions(routingProvider = wrapped?.provider.orEmpty(),
                            allowInsecureRequests = allowInsecureRequests),
                    )
                )
            }
        } catch (e: Exception) {
            Logs.w(e)
            return -1L
        }
        refreshGroup(group.id, showDiff = false, foreground = true)
        return group.id
    }

    /** ImportUrl (GroupUpdater.cpp:381-390): one fetch with the global identity into group [gid] (<= 0: current). */
    fun importUrl(url: String, gid: Long) {
        val content = url.trim()
        synchronized(lock) {
            enqueueLocked(Job(-1L, batch = false, foreground = true) {
                val fetched = SubscriptionFetch.fetch(content, content, RequestIdentity.resolve(null))
                if (fetched.error != null) {
                    error(gid, fetched.error)
                } else {
                    val result = SubscriptionRefresh.importDocuments(gid, listOf(fetched.body))
                    report(result.gid, "", app.getString(R.string.subs_imported, result.count), false)
                }
            })
        }
    }

    /**
     * ImportText (GroupUpdater.cpp:392-399) for text the main process wrote to [path] (under cacheDir/[IMPORT_DIR]);
     * the file is deleted. Returns once the job ran, with the imported count written to [path] + [IMPORT_COUNT_SUFFIX].
     */
    fun importFile(path: String, gid: Long) {
        val file = File(path).canonicalFile
        if (file.parentFile != File(app.cacheDir, IMPORT_DIR).canonicalFile) {
            Logs.w("Not an import file: $path")
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Logs.w(e)
            null
        } finally {
            file.delete()
        }
        var count = 0
        if (text != null) {
            val done = CompletableDeferred<Int>()
            synchronized(lock) {
                enqueueLocked(Job(-1L, batch = false, foreground = true) {
                    try {
                        done.complete(SubscriptionRefresh.importDocuments(gid, listOf(text)).count)
                    } finally {
                        done.complete(0)
                    }
                })
            }
            count = runBlocking { done.await() }
        }
        runCatching { File(file.path + IMPORT_COUNT_SUFFIX).writeText(count.toString()) }.onFailure { Logs.w(it) }
    }

    /**
     * The desktop's manual group actions that need the core: [ACTION_REMOVE_INVALID] ("Remove Invalid") and
     * [ACTION_RESOLVE_DOMAINS] ("Resolve Domain for group"). Queued like a refresh so they never race one over the
     * group; returns the report text once done.
     */
    fun groupAction(gid: Long, action: String): String {
        if (action != ACTION_REMOVE_INVALID && action != ACTION_RESOLVE_DOMAINS) {
            return app.getString(R.string.subs_unknown_action, action)
        }
        val result = CompletableDeferred<String>()
        synchronized(lock) {
            enqueueLocked(Job(-1L, batch = false, foreground = true) {
                try {
                    result.complete(
                        if (action == ACTION_REMOVE_INVALID) SubscriptionFilters.removeInvalid(gid)
                        else SubscriptionFilters.resolveDomains(gid)
                    )
                } catch (e: Throwable) {
                    result.complete(e.readableMessage)
                    throw e
                }
            })
        }
        return runBlocking { result.await() }
    }

    /** Resolve Domain for chosen profiles, queued like [groupAction] so it never races a refresh. */
    fun resolveProfiles(ids: List<Long>): String {
        val result = CompletableDeferred<String>()
        synchronized(lock) {
            enqueueLocked(Job(-1L, batch = false, foreground = true) {
                try {
                    result.complete(SubscriptionFilters.resolveProfiles(ids))
                } catch (e: Throwable) {
                    result.complete(e.readableMessage)
                    throw e
                }
            })
        }
        return runBlocking { result.await() }
    }

    /** Suspends until no job is queued or running and no URL-test follow-up is pending. */
    suspend fun awaitIdle() {
        outstanding.first { it == 0 }
    }

    /**
     * requestUrlTest (GroupUpdater.cpp:658-667): the URL test waits for any running test session; the follow-up
     * goes back through the queue, so it never races another job over the group.
     */
    internal fun requestUrlTest(gid: Long, profileIds: List<Long>) {
        outstanding.update { it + 1 }
        scope.launch {
            try {
                try {
                    TestEngine.queueUrlTests(profileIds)
                } catch (e: Exception) {
                    if (e is CancellationException) ensureActive() else Logs.w(e)
                }
                synchronized(lock) {
                    enqueueLocked(Job(-1L, batch = false, foreground = false) { SubscriptionRefresh.afterUrlTest(gid) })
                }
            } finally {
                outstanding.update { it - 1 }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ worker

    private fun enqueueLocked(job: Job) {
        queue.addLast(job)
        outstanding.update { it + 1 }
        if (job.gid >= 0) stateLocked(job.gid, STATE_QUEUED)
        if (draining) return
        draining = true
        if(job.foreground) SubscriptionScheduler.holdQueue()
        scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    draining = false
                } else if (next.gid >= 0) {
                    runningGid = next.gid
                    stateLocked(next.gid, STATE_RUNNING)
                }
                next
            } ?: return
            try {
                job.run()
            } catch (e: Throwable) {
                // Anything thrown here would otherwise take the whole :bg process (and the VPN) down.
                val message = app.getString(R.string.subs_task_failed, e.readableMessage)
                Logs.w(message, e)
                if (job.foreground) error(job.gid, message)
            } finally {
                synchronized(lock) {
                    runningGid = -1L
                    if (job.gid >= 0) {
                        pending.remove(job.gid)
                        stateLocked(job.gid, STATE_IDLE)
                    }
                    if (job.batch) pendingBatch--
                }
                outstanding.update { it - 1 }
            }
        }
    }

    private fun groupLabel(gid: Long): String = GroupRepo.get(gid)?.name ?: gid.toString()
}
