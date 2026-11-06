package app.rawline.mobile.bg.test

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Native authorization can wait for captcha without taking ordinary HTTP probe slots. */
internal class ProbeLanes(concurrency: Int) {
    private val regular = Semaphore(concurrency)
    private val native = Semaphore(concurrency)

    suspend fun <T> run(hasCarrier: Boolean, block: suspend () -> T): T =
        (if (hasCarrier) native else regular).withPermit { block() }
}
