package app.rawline.mobile.bg.carrier

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeProbeStartupGateTest {
    @Test fun queuedProfilesReceiveFreshPreparationBudgets(): Unit = runBlocking {
        val gate=NativeProbeStartupGate(1,1000)
        // All four individual preparations fit their budget. The total queue wait does not.
        val results=(1..4).map { id -> async {
            gate.prepare { delay(400);id }
        } }.awaitAll()
        assertEquals(listOf(1,2,3,4),results)
    }

    @Test fun preparationTimeoutFailsOnlyThatProbeAndReleasesItsPermit() = runBlocking {
        val gate=NativeProbeStartupGate(1,100)
        val failure=runCatching { gate.prepare { awaitCancellation() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(7,gate.prepare { 7 })
    }

    @Test fun defaultLimitAllowsThreePreparationsAndQueuesTheFourth() = runBlocking {
        val gate=NativeProbeStartupGate()
        val three=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var entered=0
        val jobs=(1..4).map { launch {
            gate.prepare { entered++;if(entered==3) three.complete(Unit);release.await() }
        } }
        three.await();yield()
        assertEquals(3,entered)
        release.complete(Unit);jobs.forEach { it.join() }
        assertEquals(4,entered)
    }

    @Test fun coldAuthorizationsAreSerialButHttpMeasurementDoesNotHoldTheGate() = runBlocking {
        val gate=NativeProbeStartupGate(1)
        val authorization=CompletableDeferred<Unit>()
        val releaseFirst=CompletableDeferred<Unit>()
        val secondReady=CompletableDeferred<Unit>()
        val releaseHttp=CompletableDeferred<Unit>()
        var secondStarted=false
        val first=launch {
            gate.prepare { authorization.complete(Unit);releaseFirst.await() }
            releaseHttp.await() // A slow HTTP result must not block the next authorization.
        }
        authorization.await()
        val second=launch { gate.prepare { secondStarted=true;secondReady.complete(Unit) } }
        yield();assertFalse(secondStarted)
        releaseFirst.complete(Unit);secondReady.await()
        assertTrue(first.isActive)
        releaseHttp.complete(Unit);first.join();second.join()
    }
    @Test fun cancellationOfQueuedTestDoesNotStartItOrStopTheCurrentPreparation() = runBlocking {
        val gate=NativeProbeStartupGate(1)
        val ready=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val first=launch { gate.prepare { ready.complete(Unit);release.await() } }
        ready.await()
        var queuedStarted=false
        val queued=launch { gate.prepare { queuedStarted=true } }
        yield();queued.cancelAndJoin()
        assertFalse(queuedStarted)
        assertTrue(first.isActive)
        release.complete(Unit);first.join()
        assertEquals(7,gate.prepare { 7 })
    }
}
