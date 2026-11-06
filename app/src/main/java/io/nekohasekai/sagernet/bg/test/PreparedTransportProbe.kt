package app.rawline.mobile.bg.test

import kotlinx.coroutines.launch

/** Carrier startup has its own deadline; the URL timeout starts only in [measure].
 * Leases keep an already running VPN alive and are released on stop or a partial startup failure. */
internal suspend fun <P, L : AutoCloseable, R> withPreparedTransports(
    plans: Iterable<P>, acquire: suspend (P) -> L, measure: suspend () -> R,
): R {
    val leases=mutableListOf<L>()
    try {
        for(plan in plans) leases.add(acquire(plan))
        return measure()
    } finally {
        leases.asReversed().forEach { it.close() }
    }
}

private val CLOSE_SCOPE=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)

/** CSQTT leaves its VK call for up to 20 s. The pool keeps that device until it has, so nothing reuses it early,
 * while the next ping and the probe slot no longer wait for it. */
internal fun AutoCloseable.closedInBackground():AutoCloseable { val lease=this;return AutoCloseable { CLOSE_SCOPE.launch { lease.close() } } }

/** Keep independent native preparations bounded, while a slow one does not block the others. */
internal suspend fun <P> runIndependentProbes(
    profiles:List<P>,concurrency:Int,cancelled:()->Boolean,
    sessionKey:(P)->Any?={ null },run:suspend (P)->Unit,
) = runIndependentProbes(profiles,concurrency,cancelled,sessionKey,{ null },run)

/** [callKey]: profiles joining the same VK calls also queue; parallel joins of one call fail some of them. */
internal suspend fun <P> runIndependentProbes(
    profiles:List<P>,concurrency:Int,cancelled:()->Boolean,
    sessionKey:(P)->Any?,callKey:(P)->Any?,run:suspend (P)->Unit,
) = kotlinx.coroutines.coroutineScope {
    val gate=kotlinx.coroutines.sync.Semaphore(concurrency)
    val keys=profiles.associateWith(sessionKey)
    val calls=profiles.associateWith(callKey)
    val sessions=keys.values.filterNotNull().distinct().associateWith { kotlinx.coroutines.sync.Mutex() }
    val callLocks=calls.values.filterNotNull().distinct().associateWith { kotlinx.coroutines.sync.Mutex() }
    for(profile in profiles) launch {
        // Same-device variants queue before taking a global slot. They cannot evict
        // one another's remote session or prevent unrelated devices from starting.
        // Always device first, then call: one order everywhere cannot deadlock.
        val mutex=keys[profile]?.let { sessions[it] }
        val call=calls[profile]?.let { callLocks[it] }
        mutex?.lock()
        try {
            call?.lock()
            try {
                gate.acquire()
                try { if(!cancelled()) run(profile) }
                finally { gate.release() }
            } finally { call?.unlock() }
        } finally { mutex?.unlock() }
    }
}
