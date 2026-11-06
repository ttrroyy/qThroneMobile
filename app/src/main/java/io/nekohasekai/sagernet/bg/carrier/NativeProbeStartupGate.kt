package app.rawline.mobile.bg.carrier

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Bound cold authorizations; every queued profile gets its full budget after obtaining a slot. */
internal class NativeProbeStartupGate(concurrency:Int=3,private val timeoutMs:Long=300_000) {
    private val permits=Semaphore(concurrency)
    suspend fun <T> prepare(start:suspend ()->T):T = permits.withPermit {
        try {
            withTimeout(timeoutMs) { start() }
        } catch(error:TimeoutCancellationException) {
            coroutineContext.ensureActive() // Preserve an actual user/session cancellation.
            throw IllegalStateException("Время подготовки тестового туннеля истекло",error)
        }
    }
}
