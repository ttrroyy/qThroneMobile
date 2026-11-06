package app.rawline.mobile.bg.test

import app.rawline.mobile.bg.CoreRuntime
import app.rawline.mobile.database.ProxyEntity
import app.qthrone.core.mobile.IPTestHandler
import app.qthrone.core.mobile.Mobile

/** Egress IP and country tests (runIpProbe, TestRunner.cpp:234-293): results stream as the core reports them. */
internal class IpTestRunner(session: TestSession) : LatencySweep(session) {

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        val request = probe.request().apply {
            timeoutMs = session.options.timeoutMs
            maxConcurrency = session.options.concurrency
        }
        Mobile.startIPTest(CoreRuntime.platform, request, object : IPTestHandler {
            override fun onResult(tag: String?, ip: String?, countryCode: String?, error: String?) = guarded {
                val id = probe.profileOf(tag) ?: return@guarded
                emit(ProbeResult(id, ip = ip.orEmpty(), country = countryCode.orEmpty(), error = error.orEmpty()))
            }

            override fun onDone() = guarded(done)
        })
    }

    override fun report(results: List<ProbeResult>) = session.reportIp(results)

    /** Not tested: the desktop only flags the latency in memory for an invalid candidate. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason, measured = false)
}
