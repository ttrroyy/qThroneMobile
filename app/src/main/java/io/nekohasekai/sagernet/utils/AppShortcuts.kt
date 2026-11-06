package app.rawline.mobile.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.rawline.mobile.QuickToggleShortcut
import app.rawline.mobile.R
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ui.QuickDisableShortcut
import app.rawline.mobile.ui.QuickEnableShortcut
import app.rawline.mobile.ui.ScannerActivity

/**
 * The launcher shortcuts, published at runtime: a static shortcuts.xml can only name one hard-coded package, which
 * breaks every build with another application id. The ids differ from the old static ones, which the system keeps
 * immutable while they stay pinned.
 */
object AppShortcuts {

    fun publish(context: Context) {
        try {
            val shortcuts = listOf(
                shortcut(context, "quick_toggle", R.string.quick_toggle, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickToggleShortcut::class.java),
                shortcut(context, "quick_enable", R.string.quick_enable, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickEnableShortcut::class.java),
                shortcut(context, "quick_disable", R.string.quick_disable, R.drawable.ic_qu_shadowsocks_launcher,
                    QuickDisableShortcut::class.java),
                shortcut(context, "quick_scan", R.string.add_profile_methods_scan_qr_code,
                    R.drawable.ic_qu_camera_launcher, ScannerActivity::class.java)
                    .takeIf { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
            ).filterNotNull()
            val current = ShortcutManagerCompat.getDynamicShortcuts(context)
            val unchanged = current.size == shortcuts.size && shortcuts.all { wanted ->
                current.any { it.id == wanted.id && it.shortLabel == wanted.shortLabel && ShortcutAuthorization.isAuthorized(context, it.intent) }
            }
            if (!unchanged) ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            if (Build.VERSION.SDK_INT >= 25) {
                val manager = context.getSystemService(ShortcutManager::class.java)
                val targets = setOf(QuickToggleShortcut::class.java.name, QuickEnableShortcut::class.java.name,
                    QuickDisableShortcut::class.java.name)
                val updates = manager.pinnedShortcuts.mapNotNull { shortcut ->
                    val old = shortcut.intent ?: return@mapNotNull null
                    if (old.component?.className !in targets || old.component?.packageName != context.packageName ||
                        ShortcutAuthorization.isAuthorized(context, old)) return@mapNotNull null
                    ShortcutInfo.Builder(context, shortcut.id).setShortLabel(shortcut.shortLabel ?: "VPN")
                        .setIntent(ShortcutAuthorization.sign(context, Intent(old))).build()
                }
                if (updates.isNotEmpty()) manager.updateShortcuts(updates)
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun shortcut(context: Context, id: String, label: Int, icon: Int, target: Class<*>) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(ShortcutAuthorization.sign(context, Intent(context, target).setAction(Intent.ACTION_MAIN)))
            .build()
}
