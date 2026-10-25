package app.qthrone.mobile.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The automatic subscription update (mainwindow_setup.cpp:1096-1109 on PeriodicRunner): sub_auto_update is
 * sign-encoded minutes and only takes effect from 30; each run stamps sub_auto_update_last first, then
 * RefreshAll(onlyAllowed = true) in the :bg queue.
 */
object SubscriptionScheduler {

    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "SubscriptionScheduler"

    /** The per-group updater of v11 and older, whose worker class is gone. */
    private const val LEGACY_WORK_NAME = "SubscriptionUpdater"

    private const val KEY_INTERVAL = "sub_auto_update"

    /** Keep manual updates alive after the activity closes, without a foreground notification. */
    fun holdQueue() {
        runOnDefaultDispatcher {
            try {
                val request=OneTimeWorkRequest.Builder(QueueTask::class.java).setInputData(
                    Data.Builder()
                        .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME,app.packageName)
                        .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME,RemoteWorkerService::class.java.name)
                        .build()
                ).build()
                RemoteWorkManager.getInstance(app).enqueueUniqueWork("SubscriptionQueue",ExistingWorkPolicy.APPEND_OR_REPLACE,listOf(request)).await()
            } catch(e: Exception) { Logs.w("SubscriptionScheduler: queue worker failed",e) }
        }
    }

    class QueueTask(appContext: Context,params: WorkerParameters):RemoteCoroutineWorker(appContext,params) {
        override suspend fun doRemoteWork(): Result {
            SubscriptionQueue.awaitIdle()
            return Result.success()
        }
    }

    /**
     * A subscription's own period, else (with sub_server_interval on) the period its server asks for, else
     * sub_auto_update; null when it is not updated automatically. The server period only replaces the common one, so
     * it never turns automatic updates on by itself, and it is kept within [MIN_INTERVAL_MINUTES] and a week.
     */
    fun intervalOf(group: app.qthrone.mobile.database.ProxyGroup): Int? {
        if (group.url.isEmpty() || group.archive || group.skipAutoUpdate) return null
        group.subOptions.autoUpdateMinutes.takeIf { it >= MIN_INTERVAL_MINUTES }?.let { return it }
        val common = DataStore.subAutoUpdate.takeIf { it >= MIN_INTERVAL_MINUTES } ?: return null
        val serverHours = if (DataStore.subServerInterval) group.subInfo.serverInterval else 0
        if (serverHours > 0) return (serverHours * 60).coerceIn(MIN_INTERVAL_MINUTES, MAX_SERVER_INTERVAL_MINUTES)
        return common
    }

    private const val MAX_SERVER_INTERVAL_MINUTES = 7 * 24 * 60

    /** The worker wakes at the shortest period in use; each run refreshes only the subscriptions that are due. */
    private fun shortestInterval(): Int = GroupRepo.all().mapNotNull(::intervalOf).minOrNull() ?: 0

    /** A run a little early (WorkManager timing) still counts, so a period is never skipped whole. */
    private fun due(group: app.qthrone.mobile.database.ProxyGroup, interval: Int, now: Long) =
        now - group.subLastUpdate >= interval * 60L * 9 / 10

    /**
     * (Re)schedules the periodic update, or cancels it when no subscription updates automatically. Call it after an
     * interval changes; [keepExisting] (app start) leaves an already scheduled update alone.
     */
    fun schedule(keepExisting: Boolean = false) {
        runOnDefaultDispatcher {
            val workManager = RemoteWorkManager.getInstance(app)
            try {
                val interval = shortestInterval()
                workManager.cancelUniqueWork(LEGACY_WORK_NAME).await()
                if (interval < MIN_INTERVAL_MINUTES) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val period = interval * 60L
                val now = System.currentTimeMillis() / 1000
                val initialDelay = GroupRepo.all().mapNotNull { group -> intervalOf(group)?.let { group.subLastUpdate + it * 60L - now } }
                    .minOrNull()?.coerceIn(0L, period) ?: period
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, interval.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process, where the queue lives.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putInt(KEY_INTERVAL, interval)
                            .build()
                    )
                    .build()
                val policy = if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
                workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request).await()
            } catch (e: Throwable) {
                Logs.w("SubscriptionScheduler: scheduling failed", e)
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            // The settings changed without a reschedule (e.g. a restored backup): follow them from now on.
            if (inputData.getInt(KEY_INTERVAL, 0) != shortestInterval()) schedule()
            val now = System.currentTimeMillis() / 1000
            val groups = GroupRepo.all().filter { group -> intervalOf(group)?.let { due(group, it, now) } == true }
            if (groups.isEmpty()) return Result.success()
            DataStore.subAutoUpdateLast = now
            Logs.i(app.getString(R.string.subs_auto_update_running, app.getString(R.string.subs_auto_update_task)))
            for (group in groups) SubscriptionQueue.refreshGroup(group.id, false, foreground = false)
            SubscriptionQueue.awaitIdle()
            return Result.success()
        }
    }
}
