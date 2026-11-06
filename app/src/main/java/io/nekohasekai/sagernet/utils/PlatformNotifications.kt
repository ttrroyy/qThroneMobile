package app.rawline.mobile.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rawline.mobile.R
import app.rawline.mobile.ui.MainActivity

/** The warnings the service posts next to its own notification (channel [CHANNEL_WARNINGS]). */
object PlatformNotifications {

    const val CHANNEL_WARNINGS = "warnings"

    /** MainActivity runs the Wi-Fi permission flow when started with this extra. */
    const val EXTRA_WIFI_PERMISSION = "wifiPermissionFlow"

    private const val TAG = "platform"
    private const val ID_WIFI_RULES = 1
    private const val ID_ALWAYS_ON = 2

    fun wifiRulesInactive(context: Context) = post(
        context, ID_WIFI_RULES, R.string.wifi_rules_inactive_title, R.string.wifi_rules_inactive_text,
        mainIntent(context).putExtra(EXTRA_WIFI_PERMISSION, true),
    )

    fun cancelWifiRulesInactive(context: Context) = cancel(context, ID_WIFI_RULES)

    fun alwaysOnNoProfile(context: Context) = post(
        context, ID_ALWAYS_ON, R.string.always_on_vpn_no_profile, R.string.always_on_vpn_no_profile_text,
        mainIntent(context),
    )

    fun cancelAlwaysOnNoProfile(context: Context) = cancel(context, ID_ALWAYS_ON)

    private fun mainIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun post(context: Context, id: Int, @StringRes title: Int, @StringRes text: Int, intent: Intent) {
        val message = context.getString(text)
        val notification = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(TAG, id)
    }
}
