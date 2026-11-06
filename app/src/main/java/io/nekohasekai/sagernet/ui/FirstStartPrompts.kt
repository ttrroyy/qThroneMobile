package app.qthrone.mobile.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ui.settings.GeneralSettingsFragment
import app.qthrone.mobile.utils.BatteryOptimizations

/**
 * The first start: the notification permission (13+), then the battery-optimization exemption, each asked once.
 * batteryPromptShown is set before anything shows, so a kill or a rotation never asks twice. Create it while the
 * activity initialises.
 */
class FirstStartPrompts(private val activity: MainActivity) {

    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askBattery() }

    private val batteryRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The request reports nothing useful: ask the system what was decided.
        if (!BatteryOptimizations.isIgnoring(activity)) showLaterHint()
    }

    fun start() {
        if (DataStore.batteryPromptShown) return
        if (Build.VERSION.SDK_INT >= 33 && !SagerNet.isTv &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                MobileAlertDialogBuilder(activity)
                    .setTitle(R.string.notification_permission)
                    .setMessage(R.string.notification_permission_rationale)
                    .setPositiveButton(R.string.wifi_permission_continue) { _, _ -> requestNotifications() }
                    .setNegativeButton(R.string.not_now) { _, _ -> askBattery() }
                    .setOnCancelListener { askBattery() }
                    .show()
            } else {
                requestNotifications()
            }
        } else {
            askBattery()
        }
    }

    private fun requestNotifications() {
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: ActivityNotFoundException) {
            askBattery()
        }
    }

    private fun askBattery() {
        if (DataStore.batteryPromptShown) return
        DataStore.batteryPromptShown = true
        if (BatteryOptimizations.isIgnoring(activity)) return
        // No request screen (TVs, trimmed ROMs): nothing to offer.
        val intent = BatteryOptimizations.requestIntent(activity) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            batteryRequest.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showLaterHint()
        } catch (_: SecurityException) {
            showLaterHint()
        }
    }

    private fun showLaterHint() {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.snackbar(R.string.battery_prompt_later).setAction(R.string.settings) {
            activity.openSettingsScreen(
                GeneralSettingsFragment::class.java.name, activity.getString(R.string.settings_general)
            )
        }.show()
    }
}
