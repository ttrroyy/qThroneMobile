package app.rawline.mobile.bg.test

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

internal class ProbeDeadlineExceeded : IllegalStateException("HTTP test timed out")

/** A missing core callback cannot hold the sweep forever. Cancellation belongs to this probe only. */
internal suspend fun awaitScopedUrlProbe(timeoutMs: Long, start: (done: () -> Unit) -> AutoCloseable) {
    var task: AutoCloseable? = null
    try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val done = AtomicBoolean()
                val handle = start {
                    if (done.compareAndSet(false, true) && continuation.isActive) continuation.resume(Unit)
                }
                val closed = AtomicBoolean()
                task = AutoCloseable { if (closed.compareAndSet(false, true)) handle.close() }
                continuation.invokeOnCancellation { task?.close() }
            }
        }
    } catch (error: TimeoutCancellationException) {
        coroutineContext.ensureActive()
        throw ProbeDeadlineExceeded()
    } finally {
        task?.close()
    }
}

internal fun urlProbeBudgetMs(profiles: Int, concurrency: Int, timeoutMs: Int): Long {
    val parallel = if (concurrency <= 0 || concurrency >= 500) 100 else concurrency
    val rounds = (profiles.coerceAtLeast(1).toLong() + parallel - 1) / parallel
    // Two HTTP requests plus tunnel handshake allowance; box setup/cleanup gets its own margin.
    return 30_000L + rounds * (2L * timeoutMs.coerceAtLeast(3_000) + 15_000L)
}
