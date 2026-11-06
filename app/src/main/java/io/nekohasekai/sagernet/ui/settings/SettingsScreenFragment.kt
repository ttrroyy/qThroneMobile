package app.rawline.mobile.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.preference.EditTextPreferenceModifiers
import app.rawline.mobile.ktx.FixedLinearLayoutManager
import app.rawline.mobile.ktx.needReload
import moe.matsuri.nb4a.ui.EditConfigPreference

/**
 * One settings sub-screen bound to the configuration store. Sub-classes wire their preferences in [bind]; values
 * that shape the generated config prompt a service reload after a change.
 */
abstract class SettingsScreenFragment(@XmlRes private val preferences: Int) : PreferenceFragmentCompat() {

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? =
        app.rawline.mobile.ui.MobileScreenMotion.create(this, nextAnim)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        app.rawline.mobile.ui.MobileSettingsStyle.apply(this, view)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        beforeInflate()
        setPreferencesFromResource(preferences, rootKey)
        bind()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if(preference is androidx.preference.ListPreference && app.rawline.mobile.ui.MobileSelector.show(requireContext(),preference)) return
        if (preference is EditTextPreference) {
            app.rawline.mobile.ui.MobilePreferenceDialog.show(this, preference)
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    protected open fun beforeInflate() {}

    protected abstract fun bind()

    override fun onResume() {
        super.onResume()
        // The JSON editors write from their own activity.
        preferenceScreen?.let { screen ->
            for (i in 0 until screen.preferenceCount) refreshEditors(screen.getPreference(i))
        }
    }

    private fun refreshEditors(preference: Preference) {
        if (preference is EditConfigPreference) preference.notifyChanged()
        if (preference is PreferenceGroup) {
            for (i in 0 until preference.preferenceCount) refreshEditors(preference.getPreference(i))
        }
    }

    protected fun <T : Preference> pref(key: String): T = findPreference(key)!!

    protected fun toast(@StringRes message: Int, vararg args: Any) {
        Toast.makeText(requireContext(), getString(message, *args), Toast.LENGTH_SHORT).show()
    }

    /** A change of any of [keys] prompts a service reload. */
    protected fun reloadOn(vararg keys: String) {
        for (key in keys) {
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                needReload()
                true
            }
        }
    }

    /**
     * Text input of [key] is trimmed and checked with [valid]: a rejected value shows [invalid] (formatted with the
     * value) and is not saved; an accepted one prompts a service reload when [reload].
     */
    protected fun checkText(
        key: String,
        @StringRes invalid: Int,
        reload: Boolean = true,
        valid: (String) -> Boolean,
    ) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty()
            val value = raw.trim()
            if (!valid(value)) {
                toast(invalid, value)
                return@setOnPreferenceChangeListener false
            }
            if (reload) needReload()
            if (value != raw) {
                // setText persists without calling the listener again.
                preference.text = value
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    /** A setting stored as an integer: blank input stores [blankAs], anything else must pass [valid]. */
    protected fun checkInt(
        key: String,
        @StringRes invalid: Int,
        reload: Boolean = true,
        blankAs: Int? = null,
        valid: (Int) -> Boolean,
    ) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        preference.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty().trim()
            val value = if (raw.isEmpty() && blankAs != null) blankAs else raw.toIntOrNull()
            if (value == null || !valid(value)) {
                toast(invalid, raw)
                return@setOnPreferenceChangeListener false
            }
            if (reload) needReload()
            if (value.toString() != newValue?.toString()) {
                preference.text = value.toString()
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    companion object {
        const val ARG_TITLE = "title"
    }
}
