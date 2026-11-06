package app.rawline.mobile.bg

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app

/**
 * Reference-counted foreground promotion of [CoreService] (type dataSync) while test sessions
 * run: the first holder starts the service (so it outlives its clients) and makes it foreground, the last one demotes
 * and stops it. Holders may post their own notification; the newest one with content is shown. When the system refuses
 * the promotion (background start on 31+, the dataSync budget on 35+) the notification is posted as a plain one.
 */
object CoreForeground {

    const val CHANNEL = "connection-test"
    const val NOTIFICATION_ID = 0x7E57

    private val lock = Any()
    private var service: Service? = null
    private val holders = LinkedHashMap<String, Int>()
    private val contents = HashMap<String, Notification>()
    private var started = false
    private var foreground = false

    fun attach(service: Service) = synchronized(lock) {
        this.service = service
        if (holders.isNotEmpty()) promote(service)
    }

    fun detach(service: Service) = synchronized(lock) {
        if (this.service !== service) return@synchronized
        this.service = null
        started = false
        foreground = false
        if (holders.isNotEmpty()) show()
    }

    @JvmOverloads
    fun acquire(reason: String, notification: Notification? = null) = synchronized(lock) {
        val first = holders.isEmpty()
        holders[reason] = (holders.remove(reason) ?: 0) + 1
        if (notification != null) contents[reason] = notification
        val service = service
        when {
            service == null -> {
                // The service attaches and promotes itself once it is created.
                runCatching { app.startService(Intent(app, CoreService::class.java)) }.onFailure { Logs.w(it) }
                show()
            }

            first || !foreground && !started -> promote(service)
            else -> show()
        }
    }

    /** Replaces [reason]'s notification while it holds the service. */
    fun post(reason: String, notification: Notification) = synchronized(lock) {
        if (reason !in holders) return@synchronized
        contents[reason] = notification
        if (displayed() == reason) show()
    }

    fun release(reason: String) = synchronized(lock) {
        val count = holders[reason] ?: return@synchronized
        if (count > 1) {
            holders[reason] = count - 1
            return@synchronized
        }
        holders.remove(reason)
        contents.remove(reason)
        if (holders.isEmpty()) demote() else show()
    }

    /** CoreService.onStartCommand: a start that arrives after the last release stops it again. */
    fun onStartCommand(service: Service, startId: Int): Int {
        synchronized(lock) {
            if (holders.isEmpty()) service.stopSelf(startId)
        }
        return Service.START_NOT_STICKY
    }

    /** CoreService.onTimeout (35+): the dataSync budget is spent, keep going as a bound service. */
    fun onTimeout(service: Service) = synchronized(lock) {
        Logs.w("core service: foreground time limit reached")
        if (foreground) runCatching { service.stopForeground(Service.STOP_FOREGROUND_DETACH) }
        foreground = false
        if (started) service.stopSelf()
        started = false
    }

    private fun promote(service: Service) {
        if (!started) {
            started = runCatching { service.startService(Intent(service, service.javaClass)) }
                .onFailure { Logs.w(it) }.isSuccess
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                service.startForeground(NOTIFICATION_ID, content(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                service.startForeground(NOTIFICATION_ID, content())
            }
            foreground = true
        } catch (e: Exception) {
            Logs.w(e)
            show()
        }
    }

    private fun demote() {
        val service = service
        if (service != null) {
            if (foreground) runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            if (started) service.stopSelf()
        }
        started = false
        foreground = false
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
    }

    @SuppressLint("MissingPermission")
    private fun show() {
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, content()) }.onFailure { Logs.w(it) }
    }

    /** The newest holder with a notification of its own, else the newest holder. */
    private fun displayed(): String? =
        holders.keys.reversed().firstOrNull { it in contents } ?: holders.keys.lastOrNull()

    private fun content(): Notification {
        val reason = displayed()
        return contents[reason] ?: NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_rawline_tile)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(app.getString(R.string.test_engine_fg_busy))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(SagerNet.configureIntent(app))
            .build()
    }
}
