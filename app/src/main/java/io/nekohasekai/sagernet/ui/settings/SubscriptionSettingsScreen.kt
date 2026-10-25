package app.qthrone.mobile.ui.settings

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.SettingsRegistry
import app.qthrone.mobile.database.preference.EditTextPreferenceModifiers
import app.qthrone.mobile.group.DeviceDetails
import app.qthrone.mobile.group.RequestIdentity
import app.qthrone.mobile.group.SubscriptionScheduler
import app.qthrone.mobile.ktx.USER_AGENT
import kotlin.math.abs

/**
 * Basic Settings › Subscription (dialog_basic_settings.ui, Subscription tab) and the network knobs of the app's own
 * requests (Use proxy, Ignore TLS errors, the Android-only minimum TLS version).
 */
class SubscriptionSettingsFragment : SettingsScreenFragment(R.xml.settings_subscriptions) {

    override fun bind() {
        val userAgent = pref<EditTextPreference>(SettingsRegistry.USER_AGENT2.key)
        userAgent.summaryProvider = DefaultSummaryProvider(USER_AGENT)
        userAgent.setOnBindEditTextListener {
            it.setSingleLine()
            it.hint = USER_AGENT
        }
        checkText(userAgent.key, R.string.invalid_value, reload = false) { true }

        bindAutoUpdate()
        pref<EditTextPreference>(SettingsRegistry.SUB_REQUEST_TIMEOUT.key).apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                getString(R.string.sub_settings_timeout_summary, DataStore.subRequestTimeoutSeconds)
            }
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            setOnPreferenceChangeListener { _, value ->
                val raw = value?.toString().orEmpty()
                val number = raw.trim().toIntOrNull()
                if (number == null || number !in 5..300) {
                    toast(R.string.sub_settings_timeout_error)
                    false
                } else if (raw != number.toString()) {
                    text = number.toString()
                    false
                } else true
            }
        }

        val sendHwid = pref<SwitchPreference>(SettingsRegistry.SUB_SEND_HWID.key)
        sendHwid.summary = hwidSummary(DataStore.subCustomHwidParams)
        val params = pref<EditTextPreference>(SettingsRegistry.SUB_CUSTOM_HWID_PARAMS.key)
        params.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        params.setOnBindEditTextListener {
            it.setSingleLine()
            it.hint = getString(R.string.grp_sub_custom_hwid_params_hint)
        }
        params.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty()
            val value = raw.trim()
            sendHwid.summary = hwidSummary(value)
            if (value != raw) {
                params.text = value
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    /** sub_auto_update is sign-encoded minutes: negative is off, the magnitude is kept while off. */
    private fun bindAutoUpdate() {
        val enabled = pref<SwitchPreference>(KEY_AUTO_UPDATE)
        val interval = pref<EditTextPreference>(KEY_AUTO_UPDATE_INTERVAL)
        fun minutes() = abs(DataStore.subAutoUpdate).takeIf { it > 0 } ?: -SettingsRegistry.SUB_AUTO_UPDATE.default

        enabled.isChecked = DataStore.subAutoUpdate > 0
        interval.text = minutes().toString()
        interval.isEnabled = enabled.isChecked
        interval.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            getString(R.string.auto_update_interval_sum, minutes())
        }
        interval.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.subAutoUpdate = if (on) minutes() else -minutes()
            interval.isEnabled = on
            SubscriptionScheduler.schedule()
            true
        }
        interval.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || value < SettingsRegistry.MIN_AUTO_UPDATE_MINUTES) {
                toast(R.string.auto_update_interval_invalid, SettingsRegistry.MIN_AUTO_UPDATE_MINUTES)
                return@setOnPreferenceChangeListener false
            }
            DataStore.subAutoUpdate = if (enabled.isChecked) value else -value
            SubscriptionScheduler.schedule()
            true
        }
        pref<androidx.preference.SwitchPreference>(SettingsRegistry.SUB_SERVER_INTERVAL.key).setOnPreferenceChangeListener { _, _ ->
            // the new value is stored after this returns
            listView?.post { SubscriptionScheduler.schedule() }
            true
        }
    }

    /** The four values the requests send (the desktop's Send HWID tooltip), after the custom parameters. */
    private fun hwidSummary(customParams: String): String {
        val device = RequestIdentity.applyCustomHwidParams(DeviceDetails.get(), customParams)
        fun shown(value: String) = value.ifEmpty { getString(R.string.grp_not_available) }
        return getString(
            R.string.grp_sub_send_hwid_values,
            shown(device.hwid), shown(device.os), shown(device.osVersion), shown(device.model),
        )
    }

    private companion object {
        const val KEY_AUTO_UPDATE = "subAutoUpdateEnabled"
        const val KEY_AUTO_UPDATE_INTERVAL = "subAutoUpdateInterval"
    }
}
