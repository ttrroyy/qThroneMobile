package app.qthrone.mobile.bg.test

import app.qthrone.mobile.bg.CoreRuntime
import app.qthrone.mobile.bg.proto.CoreConfig
import app.qthrone.mobile.bg.proto.exitsThroughVpn
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.bg.carrier.NativeCarrier
import app.qthrone.mobile.bg.carrier.QwdttPing
import app.qthrone.core.mobile.Mobile
import app.qthrone.core.mobile.TestRequest
import app.qthrone.core.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import app.qthrone.mobile.outbound.types.Qwdtt

/**
 * URL latency tests (runUrlProbe, TestRunner.cpp:159-232): results stream as the core reports them, then a failed
 * OpenVPN/OpenConnect profile whose tunnel the core reports up is reported again as Connect OK.
 */
internal class UrlTestRunner(session: TestSession) : LatencySweep(session) {

    override suspend fun run(profiles:List<ProxyEntity>) = coroutineScope {
        val (all,regular)=profiles.partition { it.type in NATIVE_TYPES }
        // qWDTT and CSQTT both ask VK for credentials: one budget for the two, so a sweep stays below VK's limits.
        val (vk,native)=all.partition { it.type in listOf("qwdtt","csqtt","wdttplus") }
        launch { super.run(regular) }
        launch {
            runIndependentProbes(vk,MAX_PARALLEL_PROBES,{ session.cancelled },{ (it.outbound as? Qwdtt)?.probeSessionKey() },{ (it.outbound as? Qwdtt)?.vkCallKey() }) { profile ->
                if(profile.type=="qwdtt") report(listOf(ping(profile))) else super.run(listOf(profile))
            }
        }
        // Each carrier gets its own HTTP probe: one slow start cannot stall a batch,
        // and one unreachable room cannot fail the shared box of its neighbours.
        runIndependentProbes(native,MAX_PARALLEL_PROBES,{ session.cancelled }) { profile ->
            super.run(listOf(profile))
        }
    }

    override suspend fun measure(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit) {
        val accepting = AtomicBoolean(true)
        val timeout = session.options.timeoutMs
        try {
            awaitScopedUrlProbe(urlProbeBudgetMs(probe.profileIds.size, session.options.concurrency, timeout)) { done ->
                startScoped(probe, batch, { if (accepting.get()) emit(it) }, done)
            }
        } catch (_: ProbeDeadlineExceeded) {
            accepting.set(false)
            Logs.w("HTTP probe deadline exceeded for ${probe.profileIds.size} profiles")
            probe.profileIds.forEach { emit(ProbeResult(it, error = "HTTP test timed out")) }
        } finally {
            accepting.set(false)
        }
    }

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        startScoped(probe, batch, emit, done)
    }

    private fun startScoped(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit): AutoCloseable {
        val request = probe.request().apply {
            url = session.options.url
            // As on the desktop: the configured timeout, and the core gives the unmeasured first request 5 s more for the tunnel.
            timeoutMs = session.options.timeoutMs
            maxConcurrency = session.options.concurrency
        }
        // The probe box closes with the test, so the verdict is asked for up front (TestRunner.cpp:171-176).
        probe.tagsOf(batch.filter { it.isVpnProfile() }.mapTo(HashSet()) { it.id }).forEach(request::addVPNEndpointTag)
        val task = Mobile.startScopedURLTest(null, CoreRuntime.platform, request, Handler(probe::profileOf, emit, done))
        return AutoCloseable { task.cancel() }
    }

    override fun report(results: List<ProbeResult>) = session.reportUrl(results)

    /** qWDTT is measured by its own TURN/DTLS handshake, like the original app and the desktop client. */
    private suspend fun ping(profile: ProxyEntity): ProbeResult {
        if (session.cancelled) return ProbeResult(profile.id, error = ERROR_ABORTED, measured = false)
        // As on the desktop: VK access has its own budget, the TURN/DTLS handshake the test timeout but at least 15 s.
        val handshake = QWDTT_HANDSHAKE_MS.coerceAtLeast(session.options.timeoutMs.toLong())
        // Detached so that a ping stuck in a blocking call cannot hold its probe slot past the deadline.
        val attempt = PING_SCOPE.async { QwdttPing.measure(profile.id, profile.outbound as NativeCarrier, QWDTT_AUTH_BUDGET_MS, handshake) }
        val outcome = try {
            withTimeoutOrNull(QWDTT_AUTH_BUDGET_MS + handshake + PING_OVERRUN_MS) { attempt.await() } ?: run {
                Logs.w("qWDTT ping of profile ${profile.id} overran its deadline: ${QwdttPing.stuckThreads()}")
                attempt.cancel()
                QwdttPing.Outcome(0, "HTTP test timed out")
            }
        } catch (e: CancellationException) {
            attempt.cancel()
            throw e
        } catch (e: Exception) {
            QwdttPing.Outcome(0, e.readableMessage)
        }
        return if (outcome.error.isEmpty()) ProbeResult(profile.id, latency = outcome.latency)
        // The handshake a restart killed says nothing about the server.
        else if (session.cancelled) ProbeResult(profile.id, error = ERROR_ABORTED, measured = false)
        else ProbeResult(profile.id, error = outcome.error)
    }

    /** BuildTestConfig marks it SetLatency(-1): a failed test with the generator's reason. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason)

    /** url_test_current (mainwindow_view.cpp:405-433): the running instance's `proxy` exit, shown and never stored. */
    suspend fun runCurrent(running: CoreRuntime.RunningCore?) {
        if (running == null) {
            session.reportCurrentUrl(ProbeResult(0L, error = ERROR_NOT_RUNNING))
            return
        }
        val nativeCurrent=ProfileManager.getProfile(running.profileId)?.type in NATIVE_TYPES
        val request = TestRequest().apply {
            testCurrent = true
            url = session.options.url
            timeoutMs = session.options.timeoutMs
            maxConcurrency = 1
            if (exitsThroughVpn(ProfileManager.getProfile(running.profileId))) addVPNEndpointTag(CoreConfig.TAG_PROXY)
        }
        // The last emitted result wins: the verdict comes after the failure it replaces.
        val result = AtomicReference(ProbeResult(running.profileId, error = ERROR_NO_RESULT))
        try {
            // Sessions never overlap, so only the stats bar's test can report this tag meanwhile.
            TestTags.holding(listOf(TestTags.CURRENT)) {
                // Match the desktop native-current path: warm one HTTP connection,
                // then report the second request. Neither request reauthorizes VK.
                if(nativeCurrent) {
                    awaitScopedUrlProbe(urlProbeBudgetMs(1, 1, request.timeoutMs)) { done ->
                        val task = Mobile.startScopedURLTest(running.instance,CoreRuntime.platform,request,
                            Handler({ running.profileId },result::set,done))
                        AutoCloseable { task.cancel() }
                    }
                    if(result.get().error.isNotEmpty()) return@holding
                }
                awaitScopedUrlProbe(urlProbeBudgetMs(1, 1, request.timeoutMs)) { done ->
                    val task = Mobile.startScopedURLTest(
                        running.instance, CoreRuntime.platform, request,
                        Handler({ running.profileId }, result::set, done),
                    )
                    AutoCloseable { task.cancel() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.set(ProbeResult(running.profileId, error = e.readableMessage))
        }
        session.reportCurrentUrl(result.get())
    }

    /**
     * Emits every result; once they are in, a failed (not aborted) VPN profile whose tunnel is up is emitted again with
     * the verdict (applyUrlResult's vpnConnected, TestRunner.cpp:127-142).
     */
    private class Handler(
        private val profileOf: (String?) -> Long?,
        private val emit: (ProbeResult) -> Unit,
        private val done: () -> Unit,
    ) : URLTestHandler {
        // Results may land on another Go thread than the verdicts.
        private val failures = ConcurrentHashMap<String, ProbeResult>()

        override fun onResult(tag: String?, latencyMs: Int, error: String?) = guarded {
            val id = profileOf(tag) ?: return@guarded
            val result = ProbeResult(id, latency = latencyMs, error = error.orEmpty())
            if (tag != null && result.error.isNotEmpty() && !ProxyEntity.isTestAborted(result.error)) {
                failures[tag] = result
            }
            emit(result)
        }

        override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) = guarded {
            val failure = failures.remove(tag.orEmpty()) ?: return@guarded
            if (connected) {
                emit(ProbeResult(failure.profileId, failure.latency, error = failure.error, connectOnly = true))
            }
        }

        override fun onDone() = guarded(done)
    }

    companion object {
        /** The core's errInstanceNotRunning text. */
        const val ERROR_NOT_RUNNING = "instance is not running"

        /** Profiles backed by a native client process instead of a sing-box outbound. */
        val NATIVE_TYPES = listOf("qwdtt","csqtt","wdttplus","openflux","olcrtc","dnstt","vaydns","slipstream","masterdns","cottendns","soar","s3bypass","sstp")

        /** VK access for a ping: hashes, credentials and an automatic check (the desktop's desktopPingAuthBudget). */
        const val QWDTT_AUTH_BUDGET_MS = 90_000L

        /** The TURN allocation and DTLS handshake once credentials are there, as the original app allows. */
        const val QWDTT_HANDSHAKE_MS = 15_000L

        /** Grace past a ping's own deadline before its result is given up on. */
        private const val PING_OVERRUN_MS = 15_000L
        private val PING_SCOPE = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }
}
