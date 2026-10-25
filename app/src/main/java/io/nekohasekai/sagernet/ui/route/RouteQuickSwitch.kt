package app.qthrone.mobile.ui.route

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.ktx.snackbar
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.ui.MainActivity
import app.qthrone.mobile.ui.settings.WarpSettingsFragment
import app.qthrone.mobile.ui.warp.WarpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The routing-profile quick switch (the desktop Routing menu's profile list and its "Enable Warp" toggle). */
object RouteQuickSwitch {

    /** A single-choice list of the route profiles; picking another one makes it current. */
    fun show(fragment: Fragment, onSwitched: ((RouteProfile) -> Unit)? = null) {
        fragment.lifecycleScope.launch {
            val (profiles, currentId, warp) = withContext(Dispatchers.IO) {
                Triple(RouteManager.usable(), RouteManager.current().id, DataStore.enableWarp)
            }
            val context = fragment.context ?: return@launch
            val names = Array<CharSequence>(profiles.size) { profiles[it].name.ifBlank { "#" + profiles[it].id } }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.route_profile_quick_switch)
                .setSingleChoiceItems(names, profiles.indexOfFirst { it.id == currentId }) { dialog, which ->
                    dialog.dismiss()
                    val picked = profiles[which]
                    if (picked.id == DataStore.currentRouteId) return@setSingleChoiceItems
                    switchTo(picked.id)
                    switched(fragment, names[which])
                    onSwitched?.invoke(picked)
                }
                .setNeutralButton(if (warp) R.string.warp_disable else R.string.warp_enable) { _, _ ->
                    setWarpEnabled(fragment, !warp)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** current_route_id is global; a running service picks it up when the user applies the reload, like other settings. */
    fun switchTo(id: Long) {
        DataStore.currentRouteId = id
    }

    /** "Routing profile X" when nothing runs, else the usual reload prompt with Apply. */
    fun switched(fragment: Fragment, name: CharSequence) {
        if (DataStore.serviceState.started) {
            fragment.snackbar(fragment.getString(R.string.route_switched, name) + ". " + fragment.getString(R.string.need_reload))
                .setAction(R.string.apply) { SagerNet.reloadService() }.show()
        } else {
            fragment.snackbar(fragment.getString(R.string.route_switched, name)).show()
        }
    }

    /** "Enable Warp" (mainwindow_setup.cpp:893-903): saves enable_warp and reloads a running service. */
    fun setWarpEnabled(fragment: Fragment, enabled: Boolean) {
        DataStore.enableWarp = enabled
        if (DataStore.serviceState.started) SagerNet.reloadService()
        val activity = fragment.activity as? MainActivity
        if (enabled && !WarpClient.isGenerated()) {
            fragment.snackbar(fragment.getString(R.string.warp_not_generated)).apply {
                if (activity != null) setAction(R.string.settings) {
                    val screen = WarpSettingsFragment::class.java.name
                    activity.openSettingsScreen(screen, activity.getString(R.string.warp_settings))
                }
            }.show()
        } else {
            fragment.snackbar(fragment.getString(if (enabled) R.string.warp_enabled else R.string.warp_disabled)).show()
        }
    }
}
