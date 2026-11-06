package app.rawline.mobile.bg.test

import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.aidl.ITestSessionCallback
import app.qthrone.core.mobile.Mobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * The :bg test engine behind ICoreService.startTest (the desktop TestRunner): one session at a time under a lock held
 * for the whole sweep; interactive sessions are refused while it is held, [queueUrlTests] waits for it.
 */
object TestEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = Mutex()
    private val sessionIds = AtomicInteger()

    @Volatile
    private var current: TestSession? = null

    /** Starts a session and returns its id, or -1 while another session runs. */
    fun start(spec: TestSpec, callback: ITestSessionCallback): Int {
        if (!session.tryLock()) return -1
        val test = TestSession(sessionIds.incrementAndGet(), spec, callback)
        current = test
        test.job = scope.launch {
            try {
                test.run()
            } finally {
                finish(test)
            }
        }
        return test.id
    }

    /** TestRunner::stop: the core aborts what runs, the session starts nothing new and ends with onDone(cancelled). */
    fun stop() {
        val test = current
        if (test == null) Mobile.stopTests() else stop(test)
    }

    /** profile_stop (mainwindow_profile_lifecycle.cpp:419-423): a test of the running box ends with the box. */
    fun onRunningClosed() {
        current?.takeIf { it.spec.testCurrent }?.let { stop(it) }
    }

    private fun stop(test: TestSession) {
        test.stop()
        test.job?.cancel()
    }

    /** A VPN start takes priority, but never reuses a transport before its probe has released it. */
    suspend fun stopAndJoin() {
        val test=current ?: return
        stop(test)
        while(current===test && test.job==null) kotlinx.coroutines.yield()
        test.job?.join()
    }

    /** The running session id, 0 when idle. */
    fun runningSession(): Int = current?.id ?: 0

    /**
     * Waits for a running session to finish, then URL-tests [ids] and persists the results (desktop queueUrlTests);
     * cancelling the caller stops the tests.
     */
    suspend fun queueUrlTests(ids: List<Long>, onResult: ((ProxyEntity) -> Unit)? = null) {
        if (ids.isEmpty()) return
        session.lock()
        val test = TestSession(sessionIds.incrementAndGet(), TestSpec(TestSpec.KIND_URL, ids.toLongArray()), null, onResult)
        current = test
        try {
            coroutineScope {
                val job = launch(Dispatchers.IO) { test.run() }
                test.job = job
                job.join()
            }
        } finally {
            finish(test)
        }
    }

    private fun finish(test: TestSession) {
        if (current === test) current = null
        session.unlock()
    }
}
