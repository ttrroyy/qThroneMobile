package app.rawline.mobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdateDiscoveryPolicyTest {
    @Test fun firstCheckIsImmediateAndSuccessfulChecksAreThrottledAcrossResumes() {
        val now = 5_000_000L
        assertTrue(UpdateDiscoveryPolicy.shouldCheck(now, 0, false))
        assertFalse(UpdateDiscoveryPolicy.shouldCheck(now, now, true))
        assertFalse(UpdateDiscoveryPolicy.shouldCheck(now + UpdateDiscoveryPolicy.CHECK_INTERVAL_MS - 1, now, true))
        assertTrue(UpdateDiscoveryPolicy.shouldCheck(now + UpdateDiscoveryPolicy.CHECK_INTERVAL_MS, now, true))
    }

    @Test fun failedChecksRetrySoonAndClockRollbackDoesNotSuppressChecks() {
        val now = 5_000_000L
        assertFalse(UpdateDiscoveryPolicy.shouldCheck(now + UpdateDiscoveryPolicy.RETRY_INTERVAL_MS - 1, now, false))
        assertTrue(UpdateDiscoveryPolicy.shouldCheck(now + UpdateDiscoveryPolicy.RETRY_INTERVAL_MS, now, false))
        assertTrue(UpdateDiscoveryPolicy.shouldCheck(now - 1, now, true))
    }

    @Test fun cachedOffersRespectInstalledVersionReleaseChannelAndSkippedVersion() {
        fun accepts(code: Long, version: String = "1.9.1", beta: Boolean = false, allowBeta: Boolean = false, skipped: Long = 0) =
            UpdateDiscoveryPolicy.acceptsOffer(code, version, beta, 190L, "1.9.0", allowBeta, skipped)
        assertTrue(accepts(191))
        assertFalse(accepts(190))
        assertFalse(accepts(189))
        assertFalse(accepts(191, skipped = 191))
        assertFalse(accepts(191, beta = true))
        assertTrue(accepts(191, beta = true, allowBeta = true))
        assertTrue(accepts(0, "1.9.1"))
        assertFalse(accepts(0, "1.9.0"))
        assertFalse(accepts(0, "1.9.0-beta.1"))
        assertFalse(accepts(0, "unknown"))
    }
}
