package app.qthrone.mobile.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteWorkManager
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import java.util.concurrent.TimeUnit

/**
 * Background update discovery while the app is not on screen: every 15 minutes (Android's shortest periodic work)
 * and only with a network. A newly found version is announced once by notification.
 */
object UpdateScheduler {
    private const val WORK_NAME = "UpdateCheck"
    private const val NOTIFIED = "updateNotifiedVersionCode"

    /** Follows the "show available updates" switch: scheduled while it is on, cancelled otherwise. */
    fun sync() {
        runOnDefaultDispatcher {
            try {
                val manager = RemoteWorkManager.getInstance(app)
                if (BuildConfig.IN_APP_UPDATER && DataStore.updateCheckAuto) {
                    val request = PeriodicWorkRequestBuilder<CheckTask>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request).get()
                } else manager.cancelUniqueWork(WORK_NAME).get()
            } catch (e: Exception) {
                Logs.w("UpdateScheduler: scheduling failed", e)
            }
        }
    }

    class CheckTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            if (!BuildConfig.IN_APP_UPDATER || !DataStore.updateCheckAuto) return Result.success()
            val offer = UpdateDiscovery.backgroundCheck() ?: return Result.success()
            val key = offer.versionCode.takeIf { it > 0 }?.toString() ?: offer.versionName
            if (DataStore.configurationStore.getString(NOTIFIED) != key) {
                UpdateNotifications.available(applicationContext, offer)
                DataStore.configurationStore.putString(NOTIFIED, key)
            }
            return Result.success()
        }
    }
}
