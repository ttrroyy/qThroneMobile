package app.rawline.mobile.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The battery-optimization exemption: it keeps the VPN alive in Doze and exempts background foreground-service
 * starts (switching from the notification or a widget, boot). Either system screen may be missing (TVs, trimmed
 * ROMs), so every intent is resolved first and every launch is guarded.
 */
object BatteryOptimizations {

    enum class State { UNRESTRICTED, OPTIMIZED, RESTRICTED }

    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** "Restricted" battery usage (API 28+) blocks background starts whatever Doze says. */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.isBackgroundRestricted
    }

    fun state(context: Context): State = when {
        isBackgroundRestricted(context) -> State.RESTRICTED
        isIgnoring(context) -> State.UNRESTRICTED
        else -> State.OPTIMIZED
    }

    /** The direct exemption request, else the list of apps, else the app's details. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent? = firstResolvable(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /** Where an exempt app can be put back under optimization. */
    fun settingsIntent(context: Context): Intent? = firstResolvable(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /** App info, whose battery page lifts "Restricted". */
    fun detailsIntent(context: Context): Intent? = firstResolvable(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    )

    /** The screen that changes the current [state]. */
    fun actionIntent(context: Context): Intent? = when (state(context)) {
        State.OPTIMIZED -> requestIntent(context)
        State.UNRESTRICTED -> settingsIntent(context)
        State.RESTRICTED -> detailsIntent(context)
    }

    fun launch(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun firstResolvable(context: Context, vararg intents: Intent): Intent? =
        intents.firstOrNull { it.resolveActivity(context.packageManager) != null }
}
