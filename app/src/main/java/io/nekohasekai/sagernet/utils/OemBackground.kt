package app.rawline.mobile.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import app.rawline.mobile.ktx.launchCustomTab
import java.util.Locale

/**
 * Vendor auto-start / background managers that kill VPN apps on top of Doze. Their component names change between
 * ROM versions, so this is best effort with dontkillmyapp.com as the fallback.
 */
object OemBackground {

    private val components = mapOf(
        "xiaomi" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "redmi" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "poco" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "huawei" to listOf("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity"),
        "honor" to listOf("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity"),
        "oppo" to listOf("com.coloros.safecenter/.permission.startup.StartupAppListActivity"),
        "realme" to listOf("com.coloros.safecenter/.permission.startup.StartupAppListActivity"),
        "vivo" to listOf("com.vivo.permissionmanager/.activity.BgStartUpManagerActivity"),
        "samsung" to listOf("com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity"),
        "oneplus" to listOf("com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity"),
        "asus" to listOf("com.asus.mobilemanager/.entry.FunctionActivity"),
    )

    private val manufacturer get() = Build.MANUFACTURER.lowercase(Locale.ROOT)

    /** Opens the vendor screen when one resolves and starts, else the dontkillmyapp.com page of the vendor. */
    fun open(context: Context) {
        for (name in components[manufacturer].orEmpty()) {
            val component = ComponentName.unflattenFromString(name) ?: continue
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        context.launchCustomTab("https://dontkillmyapp.com/$manufacturer")
    }
}
