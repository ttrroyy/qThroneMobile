package app.rawline.mobile

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.app

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!DataStore.rememberEnable) {   // sanity check
            enabled = false
            return
        }

        val doStart = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false // DataStore.directBootAware
            else -> Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked
        } && DataStore.selectedProxy > 0

        if (doStart) SagerNet.startService()
    }
}

/**
 * Always enabled, unlike [BootReceiver]: MY_PACKAGE_REPLACED restarts the connection an in-app update interrupted
 * (resumeAfterUpdate, set before the install), or auto-connects when remember_enable is on.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val resume = DataStore.resumeAfterUpdate
        if (resume != 0L) DataStore.resumeAfterUpdate = 0
        val wanted = resume > 0 || DataStore.rememberEnable
        if (wanted && DataStore.selectedProxy > 0 && (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)) {
            SagerNet.startService()
        }
    }
}
