package app.rawline.mobile.ui.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import app.rawline.mobile.Key
import app.rawline.mobile.R
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.RouteManager
import app.rawline.mobile.database.SettingValidators
import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.database.preference.EditTextPreferenceModifiers
import app.rawline.mobile.group.RemoteRouteUpdater
import app.rawline.mobile.ktx.needReload
import app.rawline.mobile.ui.AppManagerActivity
import app.rawline.mobile.ui.GroupSettingsActivity
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.ui.route.RouteQuickSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.EditConfigPreference
import kotlin.math.abs

/**
 * The mixed inbound (Basic Settings › Inbound Settings) and the DNS server port (Basic Settings › Core), plus the
 * Android system HTTP proxy switch and its bypass list.
 */
open class InboundSettingsFragment(@androidx.annotation.XmlRes preferences: Int = R.xml.settings_inbound) : SettingsScreenFragment(preferences) {

    override fun beforeInflate() {
        DataStore.initGlobal()
    }

    override fun bind() {
        val port = pref<EditTextPreference>(SettingsRegistry.INBOUND_SOCKS_PORT.key)
        val httpPort = pref<EditTextPreference>(SettingsRegistry.INBOUND_HTTP_PORT.key)
        val randomPort = pref<SwitchPreference>(SettingsRegistry.RANDOM_INBOUND_PORT.key)
        val allowLan = pref<SwitchPreference>(KEY_ALLOW_LAN)
        val auth = pref<SwitchPreference>(SettingsRegistry.INBOUND_AUTH.key)
        val user = pref<EditTextPreference>(SettingsRegistry.INBOUND_USER.key)
        val pass = pref<EditTextPreference>(SettingsRegistry.INBOUND_PASS.key)
        findPreference<Preference>("qmobileResetSocks")?.setOnPreferenceClickListener {
            DataStore.inboundUser = "rawline_"+java.util.UUID.randomUUID().toString().take(8)
            DataStore.inboundPass = java.util.UUID.randomUUID().toString().replace("-", "")
            user.text=DataStore.inboundUser;pass.text=DataStore.inboundPass
            needReload();true
        }
        val appendHttpProxy = pref<SwitchPreference>(Key.APPEND_HTTP_PROXY)
        val httpProxyBypass = pref<EditTextPreference>(Key.HTTP_PROXY_BYPASS)
        val dnsInPort = pref<EditTextPreference>(SettingsRegistry.CORE_DNS_IN_PORT.key)

        checkText(port.key, R.string.invalid_port, valid = { SettingValidators.isPort(it) && it.toIntOrNull()!=DataStore.inboundHttpPort })
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        checkText(httpPort.key, R.string.invalid_port, valid = { SettingValidators.isPort(it) && it.toIntOrNull()!=DataStore.inboundSocksPort })
        httpPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        // dialog_basic_settings.cpp:255-256.
        checkText(dnsInPort.key, R.string.invalid_port, valid = SettingValidators::isPort)
        dnsInPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        checkText(pass.key,R.string.qmobile_socks_password_invalid,valid={ it.length>=32 })
        pass.setOnBindEditTextListener { it.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        pass.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        httpProxyBypass.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        httpProxyBypass.summaryProvider = LinesSummaryProvider(maxLines = 1)
        pref<EditConfigPreference>(SettingsRegistry.CUSTOM_INBOUND.key).useConfigStore(SettingsRegistry.CUSTOM_INBOUND.key)

        allowLan.isChecked = DataStore.allowLanAccess
        // This legacy switch binds the proxy-only listener; VPN sharing has its own explicit switch.
        allowLan.isVisible = DataStore.serviceMode != Key.MODE_VPN && findPreference<Preference>(SettingsRegistry.LAN_SHARING_ENABLED.key)==null
        allowLan.setOnPreferenceChangeListener { _, newValue ->
            DataStore.inboundAddress = if (newValue as Boolean) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            needReload()
            true
        }

        var mixedDisabled = DataStore.disableMixedInbound
        fun updateHttpProxyState(authOn: Boolean = auth.isChecked, append: Boolean = appendHttpProxy.isChecked) {
            appendHttpProxy.isEnabled = !mixedDisabled && !authOn
            appendHttpProxy.summary = getString(
                when {
                    mixedDisabled -> R.string.append_http_proxy_mixed_disabled
                    authOn -> R.string.append_http_proxy_auth_on
                    else -> R.string.append_http_proxy_sum
                }
            )
            httpProxyBypass.isEnabled = appendHttpProxy.isEnabled && append
        }

        fun updateMixedState(disabled: Boolean) {
            mixedDisabled = disabled
            val sharing = DataStore.lanSharingEnabled && DataStore.serviceMode == Key.MODE_VPN
            if (sharing && !auth.isChecked) auth.isChecked = true
            for (p in listOf(port, httpPort, randomPort, allowLan, auth, user, pass)) p.isEnabled = !disabled
            randomPort.isEnabled = !disabled && !sharing
            auth.isEnabled = !disabled && !sharing
            if (disabled && !sharing) {
                port.summaryProvider = null
                port.summary = getString(R.string.mixed_inbound_disabled)
            } else {
                port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            port.isEnabled = !disabled || sharing
            httpPort.isEnabled = !disabled || sharing
            user.isEnabled = sharing || (!disabled && auth.isChecked)
            pass.isEnabled = sharing || (!disabled && auth.isChecked)
            updateHttpProxyState()
        }
        updateMixedState(mixedDisabled)
        findPreference<SwitchPreference>(SettingsRegistry.LAN_SHARING_ENABLED.key)?.setOnPreferenceChangeListener { _, value ->
            DataStore.lanSharingEnabled = value as Boolean
            if(DataStore.serviceMode == Key.MODE_PROXY) DataStore.inboundAddress = if(value) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            updateMixedState(mixedDisabled)
            needReload()
            true
        }
        pref<SwitchPreference>(SettingsRegistry.DISABLE_MIXED_INBOUND.key).setOnPreferenceChangeListener { _, newValue ->
            val disabled = newValue as Boolean
            if (disabled && DataStore.serviceMode == Key.MODE_PROXY) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.disable_mixed_inbound_proxy_toast, DataStore.inboundSocksPort),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateMixedState(disabled)
            needReload()
            true
        }
        auth.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            user.isEnabled = DataStore.lanSharingEnabled || enabled
            pass.isEnabled = DataStore.lanSharingEnabled || enabled
            updateHttpProxyState(authOn = enabled)
            needReload()
            true
        }
        appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
            updateHttpProxyState(append = newValue as Boolean)
            needReload()
            true
        }
        // VpnService.Builder.setHttpProxy needs Android 10.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            appendHttpProxy.isVisible = false
            httpProxyBypass.isVisible = false
        }
        user.setOnPreferenceChangeListener { _, value ->
            if (value.toString().isBlank()) false else { needReload(); true }
        }
        reloadOn(randomPort.key, httpProxyBypass.key)
    }

    private companion object {
        const val KEY_ALLOW_LAN = "inboundAllowLan"
    }
}

/** Tun Settings: stack, MTU, IPv6, tun routing, private range bypass, per-app proxy and the tun addresses. */
open class TunSettingsFragment : InboundSettingsFragment(R.xml.settings_tun_combined) {
    override fun bind() {
        super.bind()
        LanSharingSettings.bind(this)
        reloadOn(
            SettingsRegistry.VPN_MTU.key, SettingsRegistry.VPN_IPV6.key,
            SettingsRegistry.ENABLE_TUN_ROUTING.key, SettingsRegistry.BLOCK_UNOWNED_TUN.key, SettingsRegistry.BLOCK_QUIC.key,
        )

        val ranges = pref<StringListPreference>(SettingsRegistry.VPN_PRIVATE_RANGES.key)
        val bypass = pref<SwitchPreference>(KEY_BYPASS_PRIVATE_RANGES)
        bypass.isChecked = !DataStore.disablePrivateRangeBypass
        ranges.isEnabled = bypass.isChecked
        bypass.setOnPreferenceChangeListener { _, newValue ->
            DataStore.disablePrivateRangeBypass = !(newValue as Boolean)
            ranges.isEnabled = newValue
            needReload()
            true
        }
        ranges.setOnPreferenceChangeListener { _, newValue ->
            val bad = SettingValidators.lines(newValue as String?).firstOrNull { !SettingValidators.isPrivateRange(it) }
            if (bad != null) {
                toast(R.string.invalid_private_range, bad)
                return@setOnPreferenceChangeListener false
            }
            needReload()
            true
        }
        pref<Preference>(KEY_RESTORE_RANGES).setOnPreferenceClickListener {
            ranges.text = SettingsRegistry.DEFAULT_PRIVATE_RANGES.joinToString("\n")
            needReload()
            true
        }

        val ipv4 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV4_CIDR.key)
        val ipv6 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV6_CIDR.key)
        checkText(ipv4.key, R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = false) }
        checkText(ipv6.key, R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = true) }
        pref<Preference>(KEY_RESTORE_ADDRESSES).setOnPreferenceClickListener {
            ipv4.text = SettingsRegistry.DEFAULT_TUN_IPV4_CIDR
            ipv6.text = SettingsRegistry.DEFAULT_TUN_IPV6_CIDR
            needReload()
            true
        }

    }

    override fun onResume() {
        super.onResume()
        LanSharingSettings.refresh(this)
    }

    private companion object {
        const val KEY_BYPASS_PRIVATE_RANGES = "tunBypassPrivateRanges"
        const val KEY_RESTORE_RANGES = "tunRestoreRanges"
        const val KEY_RESTORE_ADDRESSES = "tunRestoreAddresses"
    }
}

/**
 * Routing Settings › Common (domain strategies, rule-set mirror), AdBlock and the remote route profile auto update;
 * the route profiles themselves live on the Route screen.
 */
class RoutingSettingsFragment : SettingsScreenFragment(R.xml.settings_routing) {
    private var routeNameReady = false
    private var routeLoad: kotlinx.coroutines.Job? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Keep the previous screen until the real route name has been bound and measured.
        view.viewTreeObserver.addOnPreDrawListener { routeNameReady }
        showCurrentRoute()
    }


    override fun bind() {
        pref<SwitchPreference>(Key.PROXY_APPS).setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) startActivity(Intent(activity, AppManagerActivity::class.java))
            DataStore.dirty = true
            true
        }
        pref<Preference>("routingApplications").setOnPreferenceClickListener {
            startActivity(Intent(activity, AppManagerActivity::class.java))
            true
        }

        pref<Preference>(KEY_ROUTE_PROFILES).setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragment(app.rawline.mobile.ui.RouteFragment())
            true
        }
        pref<Preference>(KEY_CURRENT_ROUTE).setOnPreferenceClickListener {
            RouteQuickSwitch.show(this) { showCurrentRoute() }
            true
        }

        reloadOn(
            SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY.key, SettingsRegistry.DOMAIN_STRATEGY.key,
            SettingsRegistry.RULESET_MIRROR.key, SettingsRegistry.ADBLOCK_ENABLE.key,
        )

        val enabled = pref<SwitchPreference>(KEY_AUTO_UPDATE)
        val interval = pref<EditTextPreference>(KEY_AUTO_UPDATE_INTERVAL)
        fun minutes() = abs(DataStore.routeAutoUpdate).takeIf { it > 0 } ?: -SettingsRegistry.ROUTE_AUTO_UPDATE.default

        enabled.isChecked = DataStore.routeAutoUpdate > 0
        interval.text = minutes().toString()
        interval.isEnabled = enabled.isChecked
        interval.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            getString(R.string.auto_update_interval_sum, minutes())
        }
        interval.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.routeAutoUpdate = if (on) minutes() else -minutes()
            interval.isEnabled = on
            RemoteRouteUpdater.schedule()
            true
        }
        interval.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || value < SettingsRegistry.MIN_AUTO_UPDATE_MINUTES) {
                toast(R.string.auto_update_interval_invalid, SettingsRegistry.MIN_AUTO_UPDATE_MINUTES)
                return@setOnPreferenceChangeListener false
            }
            DataStore.routeAutoUpdate = if (enabled.isChecked) value else -value
            RemoteRouteUpdater.schedule()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Setting it writes the store, and any write asks for a service reload: only when it really changed.
        pref<SwitchPreference>(Key.PROXY_APPS).apply { if (isChecked != DataStore.proxyApps) isChecked = DataStore.proxyApps }
        showCurrentRoute()
    }

    private fun showCurrentRoute() {
        if (routeLoad?.isActive == true) return
        routeLoad = lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { RouteManager.current().name }
                findPreference<Preference>(KEY_CURRENT_ROUTE)?.summary = name
            } finally {
                routeNameReady = true
                view?.requestLayout()
            }
        }
    }

    private companion object {
        const val KEY_AUTO_UPDATE = "routeAutoUpdateEnabled"
        const val KEY_AUTO_UPDATE_INTERVAL = "routeAutoUpdateInterval"
        const val KEY_ROUTE_PROFILES = "routeProfiles"
        const val KEY_CURRENT_ROUTE = "currentRouteProfile"
    }
}
