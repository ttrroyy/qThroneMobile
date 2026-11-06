package app.rawline.mobile.bg.test

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScopedUrlProbeTest {
    @Test fun missingCallbackTimesOutOnlyItsOwnProbeAndAllowsRepeats(): Unit = runBlocking {
        var closed = 0
        val neighbour = CompletableDeferred<Unit>()
        val job = launch { neighbour.await() }
        val failure = runCatching {
            awaitScopedUrlProbe(100) { AutoCloseable { closed++ } }
        }.exceptionOrNull()
        assertTrue(failure is ProbeDeadlineExceeded)
        assertTrue(job.isActive)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(closed > 0)
        repeat(50) {
            awaitScopedUrlProbe(1000) { done -> done(); AutoCloseable {} }
        }
        neighbour.complete(Unit); job.join()
    }

    @Test fun userCancellationIsPreservedAndClosesTheTask(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        val job = launch {
            awaitScopedUrlProbe(5000) { started.complete(Unit); AutoCloseable { closed = true } }
            fail("Cancelled test continued")
        }
        started.await(); job.cancelAndJoin()
        assertTrue(closed)
        assertTrue(job.isCancelled)
    }

    @Test fun allNativeSlotsWaitingForCaptchaDoNotBlockOrdinaryProfiles(): Unit = runBlocking {
        val lanes = ProbeLanes(3)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var count = 0
        val jobs = (1..3).map { launch {
            lanes.run(true) { if (++count == 3) started.complete(Unit); release.await() }
        } }
        started.await()
        assertEquals(123, withTimeout(1000) { lanes.run(false) { 123 } })
        assertTrue(jobs.all { it.isActive })
        release.complete(Unit); jobs.forEach { it.join() }
    }

    @Test fun sameDeviceVariantsQueueWithoutTakingUnrelatedDeviceSlots(): Unit = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val unrelated = CompletableDeferred<Unit>()
        var overlapping = false
        var firstActive = false
        val sweep = launch {
            runIndependentProbes(listOf(1,2,3), 2, { false }, { if (it < 3) "device-a" else "device-b" }) { id ->
                when (id) {
                    1 -> { firstActive = true; first.complete(Unit); release.await(); firstActive = false }
                    2 -> { if (firstActive) overlapping = true }
                    3 -> unrelated.complete(Unit)
                }
            }
        }
        first.await(); withTimeout(1000) { unrelated.await() }
        assertFalse(overlapping)
        release.complete(Unit); sweep.join()
        assertFalse(overlapping)
    }

    @Test fun serversSharingVkCallsQueueWhileOtherCallsRun(): Unit = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val unrelated = CompletableDeferred<Unit>()
        var overlapping = false
        var firstActive = false
        val sweep = launch {
            // Three servers, three devices: only the shared calls of 1 and 2 keep them apart.
            runIndependentProbes(listOf(1,2,3), 3, { false }, { "device-$it" }, { if (it < 3) "calls-a" else "calls-b" }) { id ->
                when (id) {
                    1 -> { firstActive = true; first.complete(Unit); release.await(); firstActive = false }
                    2 -> { if (firstActive) overlapping = true }
                    3 -> unrelated.complete(Unit)
                }
            }
        }
        first.await(); withTimeout(1000) { unrelated.await() }
        assertFalse(overlapping)
        release.complete(Unit); sweep.join()
        assertFalse(overlapping)
    }

    @Test fun slowCarrierCloseDoesNotHoldTheProbe() {
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val lease = AutoCloseable { release.await(); closed.countDown() }.closedInBackground()
        val start = System.nanoTime()
        lease.close()
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
        release.countDown()
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun watchdogIncludesBothRequestsAndConcurrencyRounds() {
        assertEquals(75000L, urlProbeBudgetMs(1, 3, 15000))
        assertEquals(120000L, urlProbeBudgetMs(4, 3, 15000))
        assertEquals(51000L, urlProbeBudgetMs(100, 0, 3000))
    }
}
