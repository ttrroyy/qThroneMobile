package app.qthrone.mobile.bg.test

import app.qthrone.mobile.bg.ProfileValidator
import app.qthrone.mobile.bg.XrayGeoAssets
import app.qthrone.mobile.bg.proto.CoreConfig
import app.qthrone.mobile.bg.proto.CoreConfigs
import app.qthrone.mobile.bg.proto.applyTo
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.completeWith
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.outbound.config.GeneratedConfig
import app.qthrone.mobile.outbound.types.Custom
import app.qthrone.core.mobile.Mobile
import app.qthrone.core.mobile.TestRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** kTestBatchSize (TestRunner.cpp:21): profiles per test build. */
internal const val TEST_BATCH_SIZE = 100

/**
 * Probes per lane running at once. Every probe is a whole sing-box, plus Xray when
 * needed, next to the running VPN instance in :bg, and a custom full config brings its own DNS, routes and rule-sets:
 * three keeps the peak around a few hundred MB on 2-4 GB phones while slow probes still overlap.
 */
internal const val MAX_PARALLEL_PROBES = 3

/** probe.ErrTestAborted: the core's text for a tag a stop kept from starting. */
internal const val ERROR_ABORTED = "test aborted"

internal const val ERROR_NO_RESULT = "no result"

/** classifyTestCandidate's Skip reasons (the generator's texts) and vanished profiles: not tested, never failed. */
internal fun isSkipReason(reason: String): Boolean = reason.startsWith("Skipping ") || reason == "Profile does not exist"

/** Core handlers run on Go threads, where an escaping exception would take the process down. */
internal inline fun guarded(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        Logs.w(e)
    }
}

/**
 * One core test of a test build (TestRunner::Target): the shared box over its candidates' tags, or a custom full
 * config measured through its default outbound.
 */
internal class TestProbe private constructor(
    private val core: CoreConfig,
    private val tagToProfileId: Map<String, Long>,
    private val fullConfigProfileId: Long,
    val carrierPlans:List<app.qthrone.mobile.bg.carrier.CarrierPlan> = emptyList(),
) {
    val profileIds: List<Long> =
        if (fullConfigProfileId > 0) listOf(fullConfigProfileId) else tagToProfileId.values.toList()

    /** A full config reports its default outbound's tag, which stands for its profile (resolveEntID's fallback). */
    fun profileOf(tag: String?): Long? =
        if (fullConfigProfileId > 0) fullConfigProfileId else tag?.let { tagToProfileId[it] }

    /** The tags the test build gave the profiles [ids] in the shared box. */
    fun tagsOf(ids: Set<Long>): List<String> = tagToProfileId.filterValues { it in ids }.keys.toList()

    /** The tags its core test reports: the shared box's candidates, or the full config's default outbound. */
    fun reportedTags(): List<String> =
        if (fullConfigProfileId > 0) listOf(TestTags.defaultOutbound(core.coreConfig)) else tagToProfileId.keys.toList()

    fun request(): TestRequest = TestRequest().apply {
        core.applyTo(this)
        useDefaultOutbound = fullConfigProfileId > 0
    }

    companion object {
        /** The probes of a build (TestRunner.cpp:357-373); the shared box goes first, it carries most of the batch. */
        fun plan(generated: GeneratedConfig): List<TestProbe> = buildList {
            if (generated.outboundTags.isNotEmpty()) {
                add(TestProbe(CoreConfig.from(generated), generated.tagToProfileId, -1L, generated.carrierPlans))
            }
            for ((id, config) in generated.fullConfigs) add(TestProbe(CoreConfig(coreConfig = config), emptyMap(), id))
        }
    }
}

/**
 * Starts one core test and suspends until the core reports it done; throws when the probe box cannot be built. A stop
 * landing while the test starts may have re-armed the core's test context before the test took it, so it is repeated.
 */
internal suspend fun TestSession.awaitCore(start: (done: () -> Unit) -> Unit) {
    suspendCancellableCoroutine<Unit> { continuation ->
        start { continuation.completeWith(Result.success(Unit)) }
        if (cancelled) Mobile.stopTests()
    }
}

/**
 * One URL or IP result; [measured] is false for what the core never measured (skip, stop, probe failure). A URL
 * failure is reported again with [connectOnly] once the core finds the profile's VPN tunnel up.
 */
internal class ProbeResult(
    @JvmField val profileId: Long,
    @JvmField val latency: Int = 0,
    @JvmField val ip: String = "",
    @JvmField val country: String = "",
    @JvmField val error: String = "",
    @JvmField val measured: Boolean = true,
    @JvmField val connectOnly: Boolean = false,
)

/** A candidate kept out of a test build: [invalid] when its config fails the core check, else it cannot be measured. */
internal class Excluded(@JvmField val profileId: Long, @JvmField val reason: String, @JvmField val invalid: Boolean)

/**
 * What the build of [profiles] must leave out: the custom Xray full configs among them first get the geo assets their
 * rules read, then IsValid's core check (generate.cpp:2493-2510), as one config that cannot start takes the whole
 * probe box down with it. A missing asset leaves its profile unmeasured, never failed: it says nothing about the
 * server, and failed results feed auto_clear_unavailable.
 */
internal suspend fun TestSession.screenXrayFullConfigs(profiles: List<ProxyEntity>): List<Excluded> {
    val originals = profiles.mapNotNull { profile ->
        (profile.outbound as? Custom)?.takeIf { it.isXrayFullConfig() }?.let { profile to it }
    }
    if (originals.isEmpty() || cancelled) return emptyList()
    val excluded = ArrayList<Excluded>()
    val candidates = withContext(Dispatchers.IO) {
        val fallback = app.qthrone.mobile.route.ProviderRouting.stored(app.qthrone.mobile.database.RouteManager.current())?.second
            ?: app.qthrone.mobile.outbound.json.JsonObject()
        originals.mapNotNull { (profile, _) ->
            try {
                profile to (CoreConfigs.prepareOutbound(profile, fallback) as Custom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                excluded.add(Excluded(profile.id, e.readableMessage, invalid = false))
                null
            }
        }
    }
    val needs = withContext(Dispatchers.IO) {
        candidates.associate { (profile, custom) -> profile.id to XrayGeoAssets.needed(listOf(custom.config)) }
    }
    val missing = withContext(Dispatchers.IO) { XrayGeoAssets.missing(needs.values.flatten().toSet()) }
    val failures = if (missing.isEmpty()) emptyMap<String, String>() else ensureAssets(missing)
    val checked = candidates.filter { (profile, _) ->
        val failure = needs[profile.id].orEmpty().firstNotNullOfOrNull { failures[it] } ?: return@filter true
        excluded.add(Excluded(profile.id, failure, invalid = false))
        false
    }
    if (cancelled) return excluded
    val permits = Semaphore(CHECK_PARALLELISM)
    val verdicts = coroutineScope {
        checked.map { (profile, custom) ->
            async(Dispatchers.IO) { permits.withPermit { ProfileValidator.xrayFullConfigError(custom.config) } }
        }.awaitAll()
    }
    for ((index, error) in verdicts.withIndex()) {
        if (error == null) continue
        val (profile, custom) = checked[index]
        val geo = XrayGeoAssets.describeFailure(error, profile.displayName())
        if (geo != null) {
            assetProblem(geo)
            excluded.add(Excluded(profile.id, geo, invalid = false))
        } else {
            excluded.add(Excluded(profile.id, "Invalid Xray ent ${custom.name}: $error", invalid = true))
        }
    }
    return excluded
}

/**
 * The probe box's own Xray config reads geo assets too when a group's front proxy is a custom Xray full config; a
 * failure is left to the probe, whose start then fails and is split.
 */
internal suspend fun TestSession.ensureSharedXrayAssets(xrayConfig: String?) {
    if (xrayConfig == null || cancelled) return
    val missing = withContext(Dispatchers.IO) { XrayGeoAssets.missing(XrayGeoAssets.needed(listOf(xrayConfig))) }
    if (missing.isNotEmpty()) ensureAssets(missing)
}

/** Parallel core checks of Xray full configs (ProfileValidator's parallelism). */
private const val CHECK_PARALLELISM = 4

/**
 * runLatencyGroup (TestRunner.cpp:307-406) for URL and IP tests: batches of [TEST_BATCH_SIZE] profiles, one test build
 * each, its probes at most [MAX_PARALLEL_PROBES] at a time; results are reported in arrival order, a chunk per wake-up.
 */
internal abstract class LatencySweep(protected val session: TestSession) {
    private val probeLanes = ProbeLanes(MAX_PARALLEL_PROBES)

    /** Starts the core test of [probe], one of [batch]'s; [emit] and [done] are called on Go threads. */
    protected abstract fun start(
        probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit,
    )

    /** Persists the measured results and reports every one. */
    protected abstract fun report(results: List<ProbeResult>)

    protected open suspend fun measure(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit) {
        session.awaitCore { done -> start(probe, batch, emit, done) }
    }

    /** A candidate the generator rejected (BuildTestConfig: "Skipping invalid config"). */
    protected abstract fun invalid(profileId: Long, reason: String): ProbeResult

    open suspend fun run(profiles: List<ProxyEntity>) {
        for (slice in profiles.chunked(TEST_BATCH_SIZE)) {
            if (session.cancelled) break
            runBatch(slice)
        }
    }

    private suspend fun runBatch(batch: List<ProxyEntity>) = coroutineScope {
        val excluded = session.screenXrayFullConfigs(batch)
        if (excluded.isNotEmpty()) {
            report(excluded.map {
                if (it.invalid) invalid(it.profileId, it.reason) else ProbeResult(it.profileId, error = it.reason, measured = false)
            })
        }
        val left = excluded.mapTo(HashSet()) { it.profileId }
        val ids = batch.map { it.id }.filter { it !in left }
        if (ids.isEmpty()) return@coroutineScope
        val results = Channel<ProbeResult>(Channel.UNLIMITED)
        launch {
            try {
                runBuild(ids, batch, results)
            } finally {
                results.close()
            }
        }
        while (true) {
            val chunk = arrayListOf(results.receiveCatching().getOrNull() ?: break)
            while (true) chunk.add(results.tryReceive().getOrNull() ?: break)
            report(chunk)
        }
    }

    /**
     * One test build over [ids] and its probes. A probe of several profiles whose box cannot start (one config that
     * fails takes the others down) is split in halves, each built and run on its own, down to the profile at fault; a
     * probe holds its [TestTags], then a lane permit, only while it runs, so the halves never wait on what their
     * parent holds. The halves' tags never meet; full configs that share a default outbound tag take turns, and since
     * the tags come first, one waiting for its turn holds no permit.
     */
    private suspend fun runBuild(
        ids: List<Long>,
        batch: List<ProxyEntity>,
        results: Channel<ProbeResult>,
    ) {
        val generated = try {
            // A native profile with automatic hashes fetches them from VK while building; a stop must interrupt that.
            runInterruptible(Dispatchers.IO) { CoreConfigs.buildTest(ids) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GeneratedConfig.failure(e.readableMessage)
        }
        if (!generated.ok) {
            val error = generated.error ?: "config generation failed"
            Logs.w("Failed to build test config for batch: $error")
            ids.forEach { results.trySend(ProbeResult(it, error = error, measured = false)) }
            return
        }
        for ((id, reason) in generated.skipped) {
            results.trySend(if (isSkipReason(reason)) ProbeResult(id, error = reason, measured = false) else invalid(id, reason))
        }
        session.ensureSharedXrayAssets(generated.xrayConfig)
        coroutineScope {
            for (probe in TestProbe.plan(generated)) launch {
                val failure = TestTags.holding(probe.reportedTags()) {
                    // Cold native authorization must never consume the ordinary profiles' slots.
                    probeLanes.run(probe.carrierPlans.isNotEmpty()) { runProbe(probe, batch, results) }
                } ?: return@launch
                val profileIds = probe.profileIds
                if (profileIds.size > 1 && !session.cancelled) {
                    Logs.w("Test probe of ${profileIds.size} profiles could not start, testing it in halves: $failure")
                    val half = (profileIds.size + 1) / 2
                    launch { runBuild(profileIds.subList(0, half), batch, results) }
                    launch { runBuild(profileIds.subList(half, profileIds.size), batch, results) }
                } else {
                    for (id in profileIds) {
                        val name = batch.firstOrNull { it.id == id }?.displayName().orEmpty()
                        // A carrier a restart stopped mid-start failed only because of the stop.
                        val error = if (session.cancelled) ERROR_ABORTED else session.startFailure(name, failure)
                        results.trySend(ProbeResult(id, error = error, measured = false))
                    }
                }
            }
        }
    }

    /** Runs [probe] and reports its profiles; the core's error, nothing reported, when its box could not start. */
    private suspend fun runProbe(probe: TestProbe, batch: List<ProxyEntity>, results: Channel<ProbeResult>): String? {
        val reported: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val emit: (ProbeResult) -> Unit = { if (reported.add(it.profileId) || it.connectOnly) results.trySend(it) }
        if (session.cancelled) {
            probe.profileIds.forEach { emit(ProbeResult(it, error = ERROR_ABORTED, measured = false)) }
            return null
        }
        try {
            val status=if(session.kind==TestSpec.KIND_URL && probe.carrierPlans.isNotEmpty())
                app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Writer(probe.profileIds.first(),testing=true) else null
            try {
            status?.update(app.qthrone.mobile.bg.carrier.NativeConnectionStage.PREPARING)
            // CSQTT must obtain its tunnel config before the short HTTP latency deadline starts.
            // CarrierProcess owns the startup deadline; coroutine cancellation still stops preparation.
            withPreparedTransports(probe.carrierPlans,{ plan -> app.qthrone.mobile.bg.carrier.CarrierPool.acquire(plan,
                session.interactive && session.kind!=TestSpec.KIND_URL,onStatus={ status?.update(it) }).closedInBackground() }) {
                status?.update(app.qthrone.mobile.bg.carrier.NativeConnectionStage.CONNECTED)
                measure(probe, batch, emit)
            }
            } finally { status?.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w("Test probe failed to start: ${e.readableMessage}")
            return e.readableMessage
        }
        // Unanswered because a restart stopped the probe, not because the server failed.
        val missing = if (session.cancelled) ERROR_ABORTED else ERROR_NO_RESULT
        probe.profileIds.forEach { emit(ProbeResult(it, error = missing, measured = false)) }
        return null
    }
}
