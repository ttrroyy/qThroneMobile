package app.rawline.mobile.group

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.utils.PlatformNotifications

internal object SubscriptionNotifications {
    fun updated(group: ProxyGroup) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val notification = NotificationCompat.Builder(app, PlatformNotifications.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(app.getString(R.string.sub_settings_updated_title))
            .setContentText(app.getString(R.string.sub_settings_updated_text, group.displayName()))
            .setContentIntent(PendingIntent.getActivity(app, group.id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(app).notify("subscription-update", group.id.hashCode(), notification)
        } catch (_: SecurityException) { }
    }
}
