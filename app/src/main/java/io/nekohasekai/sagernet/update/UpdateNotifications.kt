package app.rawline.mobile.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rawline.mobile.R

/** The update channel: download progress, install prompt and failures (legacy release alerts are cancelled). */
@SuppressLint("MissingPermission")
object UpdateNotifications {

    private const val CHANNEL = "app-update"
    private const val ID_AVAILABLE = 0x75_01
    private const val ID_PROGRESS = 0x75_02

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentIntent(activityIntent(context))
            .setOnlyAlertOnce(true)
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun activityIntent(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun receiverIntent(context: Context, action: String, versionCode: Long = 0) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, UpdateReceiver::class.java).setAction(action).putExtra(UpdateReceiver.EXTRA_VERSION_CODE, versionCode),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun available(context: Context, offer: UpdateChecker.Offer) {
        val builder = builder(context)
            .setSmallIcon(R.drawable.ic_baseline_update_24)
            .setContentTitle(context.getString(R.string.update_notification_available, offer.versionName))
            .setContentText(context.getString(R.string.update_notification_available_text))
            .setAutoCancel(true)
        if (offer.versionCode > 0) {
            builder.addAction(
                0, context.getString(R.string.update_action_skip),
                receiverIntent(context, UpdateReceiver.ACTION_SKIP, offer.versionCode)
            )
        }
        post(context, ID_AVAILABLE, builder)
    }

    fun progress(context: Context, offer: UpdateChecker.Offer, done: Long, total: Long) {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        post(
            context, ID_PROGRESS, builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.update_downloading_x, offer.versionName))
                .setContentText(
                    context.getString(
                        R.string.update_progress,
                        Formatter.formatShortFileSize(context, done),
                        Formatter.formatShortFileSize(context, total)
                    )
                )
                .setProgress(100, percent, total <= 0)
                .setOngoing(true)
                .addAction(0, context.getString(android.R.string.cancel), receiverIntent(context, UpdateReceiver.ACTION_CANCEL))
        )
    }

    /** Shown when the system confirmation cannot be opened from the background. */
    fun installPrompt(context: Context, confirm: Intent) {
        val tap = PendingIntent.getActivity(
            context, 1, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            context, ID_PROGRESS, builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_notification_ready))
                .setContentText(context.getString(R.string.update_notification_tap_install))
                .setContentIntent(tap)
                .setAutoCancel(true)
        )
    }

    fun failed(context: Context, message: String) {
        post(
            context, ID_PROGRESS, builder(context)
                .setSmallIcon(R.drawable.ic_baseline_update_24)
                .setContentTitle(context.getString(R.string.update_failed))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
        )
    }

    fun cancelProgress(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PROGRESS)

    fun cancelAvailable(context: Context) = NotificationManagerCompat.from(context).cancel(ID_AVAILABLE)
}
