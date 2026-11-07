package app.rawline.mobile.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The home screen icon follows the app's own theme: two launcher entries, one of them on. */
object LauncherIcon {
    private const val DARK = "app.rawline.mobile.LauncherDark"
    private const val LIGHT = "app.rawline.mobile.LauncherLight"

    fun sync(context: Context) {
        val manager = context.packageManager
        val wanted = if (MobilePalette.light) LIGHT else DARK
        fun enabled(name: String): Boolean = when (manager.getComponentEnabledSetting(ComponentName(context, name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == DARK
            else -> false
        }
        val other = if (wanted == LIGHT) DARK else LIGHT
        if (enabled(wanted) && !enabled(other)) return
        // The wanted one first: for a moment two icons, never none.
        runCatching {
            manager.setComponentEnabledSetting(ComponentName(context, wanted), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            manager.setComponentEnabledSetting(ComponentName(context, other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
