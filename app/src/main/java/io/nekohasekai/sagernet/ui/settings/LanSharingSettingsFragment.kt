package app.rawline.mobile.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

object LanSharingSettings {
    private var cachedAddresses: String? = null
    fun bind(fragment: androidx.preference.PreferenceFragmentCompat) {
        fragment.findPreference<Preference>("sharingAddresses")!!.summary =
            cachedAddresses ?: fragment.getString(R.string.lan_sharing_loading)
        fragment.findPreference<Preference>("sharingAddresses")!!.setOnPreferenceClickListener {
            SagerNet.trySetPrimaryClip(it.summary?.toString().orEmpty()); true
        }
        fragment.findPreference<Preference>("sharingHotspot")!!.setOnPreferenceClickListener {
            runCatching { fragment.startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                .onFailure { fragment.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            true
        }
    }
    fun refresh(fragment: androidx.preference.PreferenceFragmentCompat) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val addresses=withContext(Dispatchers.IO) {
                runCatching { NetworkInterface.getNetworkInterfaces().toList().filter {
                    it.isUp && !it.isLoopback && listOf("tun","wg","rmnet","ccmni","pdp").none { prefix -> it.name.startsWith(prefix) }
                }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct() }.getOrDefault(emptyList())
            }
            val summary=addresses.joinToString("\n").ifEmpty { fragment.getString(R.string.lan_sharing_no_address) }
            cachedAddresses=summary
            fragment.findPreference<Preference>("sharingAddresses")?.let { preference ->
                if(preference.summary?.toString()!=summary) preference.summary=summary
            }
        }
    }
}

/** Compatibility for a beta 1 screen restored by Android after an update. */
@androidx.annotation.Keep
class LanSharingSettingsFragment : TunSettingsFragment()
