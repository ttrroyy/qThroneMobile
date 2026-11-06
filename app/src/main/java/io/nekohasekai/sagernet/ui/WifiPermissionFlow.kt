package app.qthrone.mobile.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.Action
import app.qthrone.mobile.R
import app.qthrone.mobile.utils.WifiStateAccess
import app.qthrone.mobile.utils.WifiStateAccess.Status

/**
 * Walks the user through what Wi-Fi rules need (WifiStateAccess): precise location, then "all the time", then location
 * services. Never blocking: a refusal leaves the rules inactive. Create it while the activity initialises.
 */
class WifiPermissionFlow(private val activity: ComponentActivity) {

    private var onDone: ((Boolean) -> Unit)? = null

    // The status the last step tried to fix: seeing it again means the user refused.
    private var step: Status? = null

    private val foreground = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { afterRequest(Manifest.permission.ACCESS_FINE_LOCATION) }

    private val background = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { afterRequest(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

    private val settings = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { next() }

    /**
     * A call while a walk-through runs is dropped: a second one would stack its rationale on the first and leave that
     * one open over an activity that may finish when the walk-through ends.
     */
    fun run(onDone: (granted: Boolean) -> Unit = {}) {
        if (this.onDone != null) return
        this.onDone = onDone
        step = null
        next()
    }

    private fun next() {
        val status = WifiStateAccess.status(activity)
        if (status == Status.OK) return finish(true)
        if (status == step) return finish(false)
        step = status
        when (status) {
            Status.NEED_FOREGROUND -> explain(R.string.wifi_permission_message) {
                foreground.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }

            Status.NEED_BACKGROUND -> explain(R.string.wifi_permission_background_message) {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }

            Status.LOCATION_OFF -> explain(R.string.wifi_location_off_message, R.string.location_settings) {
                openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }

            Status.OK -> Unit
        }
    }

    /** A denial the system will not ask about again is sent to the app settings once. */
    private fun afterRequest(permission: String) {
        if (WifiStateAccess.status(activity) == step && !activity.shouldShowRequestPermissionRationale(permission)) {
            explain(R.string.wifi_permission_settings_message, R.string.open_app_settings) {
                openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                )
            }
        } else {
            next()
        }
    }

    private fun explain(@StringRes message: Int, @StringRes positive: Int = R.string.wifi_permission_continue, action: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return finish(false)
        MobileAlertDialogBuilder(activity)
            .setTitle(R.string.wifi_permission_title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton(R.string.not_now) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .show()
    }

    private fun openSettings(intent: Intent) {
        try {
            settings.launch(intent)
        } catch (_: ActivityNotFoundException) {
            finish(false)
        } catch (_: SecurityException) {
            finish(false)
        }
    }

    private fun finish(granted: Boolean) {
        step = null
        if (granted) {
            // A running service re-reads the Wi-Fi state and drops its warning.
            activity.sendBroadcast(Intent(Action.REFRESH_WIFI_STATE).setPackage(activity.packageName))
        }
        val callback = onDone ?: return
        onDone = null
        callback(granted)
    }
}
