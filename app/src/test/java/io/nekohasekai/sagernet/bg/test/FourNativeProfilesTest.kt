package app.rawline.mobile.bg.test

import app.rawline.mobile.outbound.types.Qwdtt
import app.rawline.mobile.outbound.types.Csqtt
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FourNativeProfilesTest {
    @Test fun fourthProfileRunsAfterOneOfThreeCompletesAndEveryResultArrives(): Unit = runBlocking {
        val three=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val results=mutableListOf<Int>()
        var active=0
        var started=0
        var peak=0
        val sweep=launch {
            runIndependentProbes(listOf(1,2,3,4),3,{ false }) { id ->
                active++;started++;peak=maxOf(peak,active)
                if(started==3) three.complete(Unit)
                try { release.await();results.add(id) } finally { active-- }
            }
        }
        three.await();yield()
        assertEquals(3,started)
        release.complete(Unit);sweep.join()
        assertEquals(listOf(1,2,3,4),results.sorted())
        assertEquals(3,peak)
        assertEquals(0,active)
    }

    @Test fun rawQueueUsesRawPortAndCsqttUsesItsActualPeerPort() {
        val first=Qwdtt().apply { server="192.0.2.1";serverPort=56000;rawPort=56003;deviceId="fixture";mode="raw" }
        val second=Qwdtt().apply { server=first.server;serverPort=56001;rawPort=56003;deviceId="fixture";mode="raw" }
        assertEquals(first.probeSessionKey(),second.probeSessionKey())
        second.rawPort=56004
        assertNotEquals(first.probeSessionKey(),second.probeSessionKey())
        first.mode="wg"
        assertEquals("56000",first.probeSessionKey()[2])
        val cs=Csqtt().apply { serverPort=46000;rawPort=56003 }
        assertEquals("46000",cs.probeSessionKey()[2])
    }
}
