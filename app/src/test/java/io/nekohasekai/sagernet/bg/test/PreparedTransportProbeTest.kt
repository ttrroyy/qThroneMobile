package app.rawline.mobile.bg.test

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreparedTransportProbeTest {
    @Test fun slowNativePreparationDoesNotBlockOtherProfilesAndConcurrencyIsBounded() = runBlocking {
        val release=CompletableDeferred<Unit>()
        val slowStarted=CompletableDeferred<Unit>()
        val fastDone=CompletableDeferred<Unit>()
        val active=java.util.concurrent.atomic.AtomicInteger()
        val peak=java.util.concurrent.atomic.AtomicInteger()
        var slowFinished=false
        val sweep=launch {
            runIndependentProbes(listOf(1,2,3,4),2,{ false }) { id ->
                val count=active.incrementAndGet();peak.updateAndGet { maxOf(it,count) }
                try {
                    if(id==1) { slowStarted.complete(Unit);release.await();slowFinished=true }
                    else { slowStarted.await();fastDone.complete(Unit) }
                } finally { active.decrementAndGet() }
            }
        }
        fastDone.await()
        assertFalse(slowFinished)
        assertTrue(peak.get()<=2)
        release.complete(Unit);sweep.join()
        assertEquals(0,active.get())
        assertTrue(slowFinished)
    }

    @Test fun coldTransportStartupDoesNotConsumeUrlTimeout() = runBlocking {
        var closed=false
        val result=withPreparedTransports(listOf(1), {
            delay(100) // A working carrier takes longer to connect than the URL timeout.
            AutoCloseable { closed=true }
        }) { withTimeout(50) { 123 } }
        assertEquals(123,result)
        assertTrue(closed)
    }
    @Test fun partialStartupFailureReleasesEarlierLease() = runBlocking {
        var closed=false
        var measured=false
        try {
            withPreparedTransports(listOf(1,2), {
                if(it==2) error("startup failed")
                AutoCloseable { closed=true }
            }) { measured=true }
            fail("Expected startup failure")
        } catch(_: IllegalStateException) { }
        assertTrue(closed)
        assertFalse(measured)
    }
    @Test fun cancellingPreparationPreservesActiveVpnLease() = runBlocking {
        var references=1 // The live VPN already owns one reference.
        val preparing=CompletableDeferred<Unit>()
        val job=launch {
            withPreparedTransports(listOf(1,2), {
                if(it==2) { preparing.complete(Unit);awaitCancellation() }
                references++
                AutoCloseable { references-- }
            }) { fail("Cancelled preparation must not start a URL test") }
        }
        preparing.await()
        job.cancelAndJoin()
        assertEquals(1,references)
    }
}
