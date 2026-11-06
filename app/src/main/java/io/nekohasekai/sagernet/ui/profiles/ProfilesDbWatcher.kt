package app.rawline.mobile.ui.profiles

import androidx.room.InvalidationTracker
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes of the `:bg` process (subscription refreshes, test results, imports) reach this process only through
 * Room's multi-instance invalidation: [onProfiles] / [onGroups] run on the main thread after the `profiles` /
 * `groups` tables changed, at most once per [THROTTLE_MS] while changes keep coming.
 */
internal class ProfilesDbWatcher(
    private val onProfiles: () -> Unit,
    private val onGroups: () -> Unit,
) {

    companion object {
        private const val THROTTLE_MS = 400L
    }

    private val profiles = Channel<Unit>(Channel.CONFLATED)
    private val groups = Channel<Unit>(Channel.CONFLATED)

    private val observer = object : InvalidationTracker.Observer(arrayOf(ProxyEntity.TABLE, ProxyGroup.TABLE)) {
        override fun onInvalidated(tables: Set<String>) {
            if (tables.any { it.equals(ProxyEntity.TABLE, true) }) profiles.trySend(Unit)
            if (tables.any { it.equals(ProxyGroup.TABLE, true) }) groups.trySend(Unit)
        }
    }

    /** Watches until [scope] is cancelled. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val tracker = SagerDatabase.instance.invalidationTracker
            try {
                tracker.addObserver(observer)
            } catch (e: Exception) {
                Logs.w(e)
                return@launch
            }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { tracker.removeObserver(observer) }
            }
        }
        scope.launch(Dispatchers.Main) {
            for (signal in profiles) {
                onProfiles()
                delay(THROTTLE_MS)
            }
        }
        scope.launch(Dispatchers.Main) {
            for (signal in groups) {
                onGroups()
                delay(THROTTLE_MS)
            }
        }
    }
}
