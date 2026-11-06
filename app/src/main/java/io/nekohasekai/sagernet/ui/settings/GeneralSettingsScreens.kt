package app.rawline.mobile.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import app.rawline.mobile.BootReceiver
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.ktx.needReload
import app.rawline.mobile.ktx.remove
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.utils.AlwaysOnVpn
import app.rawline.mobile.utils.AppLocale
import app.rawline.mobile.utils.BatteryOptimizations
import app.rawline.mobile.utils.OemBackground
import app.rawline.mobile.utils.Theme
import moe.matsuri.nb4a.ui.ColorPickerPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/** Service mode, auto connect (remember_enable) and the connection behaviour switches. */
class GeneralSettingsFragment : SettingsScreenFragment(R.xml.settings_general) {

    private lateinit var autoConnect: SwitchPreference
    private lateinit var alwaysOn: Preference
    private lateinit var battery: Preference

    override fun bind() {
        pref<Preference>(Key.SERVICE_MODE).setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        // The boot receiver follows the switch at once, not only at the next service start.
        autoConnect = pref(SettingsRegistry.REMEMBER_ENABLE.key)
        autoConnect.setOnPreferenceChangeListener { _, newValue ->
            BootReceiver.enabled = newValue as Boolean
            true
        }
        if (Build.VERSION.SDK_INT < 28) {
            pref<Preference>(Key.METERED_NETWORK).remove()
        }
        pref<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS).setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }
        reloadOn(Key.METERED_NETWORK)
        reloadOn("allowAppsBypassVpn")

        alwaysOn = pref(KEY_ALWAYS_ON_VPN)
        alwaysOn.setOnPreferenceClickListener {
            if (!AlwaysOnVpn.openSettings(requireContext())) toast(R.string.vpn_settings_missing)
            true
        }
        battery = pref(KEY_BATTERY_OPTIMIZATION)
        battery.setOnPreferenceClickListener {
            val context = requireContext()
            if (!BatteryOptimizations.launch(context, BatteryOptimizations.actionIntent(context))) {
                toast(R.string.battery_settings_missing)
            }
            true
        }
        pref<Preference>(KEY_BACKGROUND_RESTRICTIONS).setOnPreferenceClickListener {
            OemBackground.open(requireContext())
            true
        }
        // Populate summaries before the list is first measured, not midway through the entrance.
        refreshStates()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        if (!::battery.isInitialized) return
        val context = requireContext()
        battery.setSummary(
            when (BatteryOptimizations.state(context)) {
                BatteryOptimizations.State.UNRESTRICTED -> R.string.battery_state_unrestricted
                BatteryOptimizations.State.OPTIMIZED -> R.string.battery_state_optimized
                BatteryOptimizations.State.RESTRICTED -> R.string.battery_state_restricted
            }
        )
        val alwaysOnState = AlwaysOnVpn.state()
        alwaysOn.setSummary(
            when (alwaysOnState) {
                AlwaysOnVpn.State.OFF -> R.string.always_on_vpn_off
                AlwaysOnVpn.State.ON -> R.string.always_on_vpn_on
                AlwaysOnVpn.State.LOCKDOWN -> R.string.always_on_vpn_lockdown
                AlwaysOnVpn.State.UNKNOWN -> R.string.always_on_vpn_unknown
            }
        )
        val superseded = alwaysOnState == AlwaysOnVpn.State.ON || alwaysOnState == AlwaysOnVpn.State.LOCKDOWN
        autoConnect.setSummary(if (superseded) R.string.auto_connect_superseded else R.string.auto_connect_summary)
    }

    private companion object {
        const val KEY_ALWAYS_ON_VPN = "alwaysOnVpn"
        const val KEY_BATTERY_OPTIMIZATION = "batteryOptimization"
        const val KEY_BACKGROUND_RESTRICTIONS = "backgroundRestrictions"
        const val KEY_UPDATES = "cag_updates"
    }
}

/** Theme, language, profile list and notification options (all Android-only). */
class AppearanceSettingsFragment : SettingsScreenFragment(R.xml.settings_appearance) {

    override fun bind() {
        pref<SimpleMenuPreference>("qmobileUiStyle").setOnPreferenceChangeListener { _, _ -> applyThemeInstantly(); true }
        // The new style is black or white with one look: accent colours, seasonal art and a bottom tile bar mean nothing there.
        val modern = app.rawline.mobile.ui.MobileStyle.new
        listOf(Key.APP_THEME, "seasonal_decoration", "qmobileTilesPosition", "qmobileHomeTilesMode", "qmobileListMode").forEach { pref<Preference>(it).isVisible = !modern }
        pref<Preference>("qmobileControlPosition").isVisible = modern
        pref<Preference>("seasonal_decoration").setOnPreferenceChangeListener { _, _ -> applyThemeInstantly(); true }
        pref<ColorPickerPreference>(Key.APP_THEME).setOnPreferenceChangeListener { _, _ ->
            DataStore.useSystemTheme = false
            applyThemeInstantly()
            true
        }
        pref<SimpleMenuPreference>("qmobileSurfaceTheme").apply {
            if (modern) {
                setEntries(R.array.qmobile_surface_themes_new)
                setEntryValues(R.array.qmobile_surface_values_new)
            }
            value = if (modern && DataStore.nightTheme == 1) "oled" else if (DataStore.amoledTheme && DataStore.nightTheme == 1) "oled" else when (DataStore.nightTheme) {
                0 -> "system"
                2 -> "light"
                else -> "dark"
            }
            setOnPreferenceChangeListener { _, selected ->
                if (!modern) DataStore.amoledTheme = selected == "oled"
                DataStore.nightTheme = when (selected) { "system" -> 0; "light" -> 2; else -> 1 }
                Theme.currentNightMode = DataStore.nightTheme
                // The recreated host applies night mode. Applying it here as well
                // causes a second recreation while the old preference row is rebinding.
                applyThemeInstantly()
                true
            }
        }
        pref<SimpleMenuPreference>(Key.APP_LANGUAGE).setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        reloadOn("qmobileNotificationSpeed")
    }

    // Appearance changes apply at once: the recreated host applies the theme and the AMOLED overlay
    // from DataStore in onCreate; posted to the next frame to stay out of the running preference/dialog transaction
    private fun applyThemeInstantly() {
        val host = activity ?: return
        host.window?.decorView?.post {
            if (!host.isFinishing && !host.isDestroyed) {
                app.rawline.mobile.ui.MobileScreenMotion.holdForRecreate(host) {
                    if (!host.isFinishing && !host.isDestroyed) ActivityCompat.recreate(host)
                }
            }
        }
    }

    private companion object {
        const val KEY_NOTIFICATION_PERMISSION = "notificationPermission"
    }
}
