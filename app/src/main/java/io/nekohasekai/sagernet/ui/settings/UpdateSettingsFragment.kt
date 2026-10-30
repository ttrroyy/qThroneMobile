package app.qthrone.mobile.ui.settings

import android.content.Intent
import androidx.preference.Preference
import app.qthrone.mobile.R
import app.qthrone.mobile.update.UpdateActivity
import app.qthrone.mobile.BuildConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class UpdateSettingsFragment : SettingsScreenFragment(R.xml.settings_updates) {
    override fun bind() {
        val currentVersion=pref<Preference>("updateCurrentVersion")
        currentVersion.summary=getString(R.string.update_current_version,BuildConfig.VERSION_NAME)
        // A found version sits right under the check; the installed one stays below a line.
        val available=pref<Preference>("updateAvailable")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.qthrone.mobile.update.UpdateDiscovery.available.collect { offer ->
                    available.isVisible=offer!=null
                    if(offer!=null) {
                        available.title=getString(R.string.update_home_banner,offer.versionName)
                        available.setOnPreferenceClickListener { UpdateActivity.start(requireContext(),offer);true }
                    }
                }
            }
        }
        pref<Preference>("qmobileUpdate").setOnPreferenceClickListener {
            startActivity(Intent(requireContext(),UpdateActivity::class.java))
            true
        }
    }
}
