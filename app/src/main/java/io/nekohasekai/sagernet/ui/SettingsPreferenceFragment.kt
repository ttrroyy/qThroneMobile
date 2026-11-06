package app.rawline.mobile.ui

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.FixedLinearLayoutManager
import app.rawline.mobile.ktx.confirmAction
import app.rawline.mobile.ktx.triggerFullRestart

/**
 * The settings root: one entry per sub-screen (app.rawline.mobile.ui.settings, opened by [SettingsFragment]),
 * the backup screen and the reset action.
 */
open class SettingsPreferenceFragment(private val advanced: Boolean = false) : PreferenceFragmentCompat() {

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? =
        MobileScreenMotion.create(this, nextAnim)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        app.rawline.mobile.ui.MobileSettingsStyle.apply(this, view)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(if (advanced) R.xml.global_preferences else R.xml.qmobile_settings)
        findPreference<Preference>("qmobileLogs")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragment(LogcatFragment())
            true
        }
        findPreference<Preference>("qmobileUpdate")?.setOnPreferenceClickListener { startActivity(android.content.Intent(requireContext(),app.rawline.mobile.update.UpdateActivity::class.java));true }

        findPreference<Preference>(KEY_BACKUP)!!.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_tools)
            true
        }


    }

    private companion object {
        const val KEY_BACKUP = "settingsBackup"
        const val KEY_RESET_SETTINGS = "resetSettings"
    }
}

class AdvancedSettingsPreferenceFragment : SettingsPreferenceFragment(true)
