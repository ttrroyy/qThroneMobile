package app.rawline.mobile.ui.profile

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.annotation.XmlRes
import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.ui.json.ProfileJson
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.ui.EditConfigPreference

/**
 * A profile editor whose whole state is a list of [PreferenceBindingManager] bindings over one preference XML: the
 * bindings copy the outbound into the cache on init and back on serialize, and every preference key is the bound
 * (dotted) field path. A screen may also carry a "Raw JSON" [EditConfigPreference] keyed [ProfileSettingsActivity.KEY_RAW_JSON]
 * that opens the whole ExportToJson in the JSON editor, and [EditConfigPreference] rows for JSON object members
 * registered with [jsonObjectText].
 */
abstract class BindingSettingsActivity<T : Outbound>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ProfileSettingsActivity<T>(resId) {

    protected val pbm = PreferenceBindingManager()

    @get:XmlRes
    protected abstract val preferencesResource: Int

    private class JsonTextBinding<T>(
        val cacheKey: String,
        val get: T.() -> JsonObject,
        val set: T.(JsonObject) -> Unit,
    )

    private val jsonTextBindings = ArrayList<JsonTextBinding<T>>()

    /** A JSON object member edited as text under [cacheKey] (the row's key); blank or invalid text is an empty object. */
    protected fun jsonObjectText(cacheKey: String, get: T.() -> JsonObject, set: T.(JsonObject) -> Unit) {
        jsonTextBindings.add(JsonTextBinding(cacheKey, get, set))
    }

    override fun T.init() {
        pbm.writeToCacheAll(this)
        for (binding in jsonTextBindings) {
            DataStore.profileCacheStore.putString(binding.cacheKey, ProfileJson.text(binding.get(this)))
        }
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
    }

    override fun T.serialize() {
        pbm.fromCacheAll(this)
        for (binding in jsonTextBindings) {
            binding.set(this, JsonInput.parseObject(DataStore.profileCacheStore.getString(binding.cacheKey) ?: ""))
        }
    }

    private var rawJsonPreference: EditConfigPreference? = null
    private var jsonTextPreferences: List<EditConfigPreference> = emptyList()

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(preferencesResource)
        pbm.setPreferenceFragment(this)
        rawJsonPreference = findPreference<EditConfigPreference>(KEY_RAW_JSON)?.apply {
            setOnPreferenceClickListener {
                openRawJsonEditor()
                true
            }
        }
        jsonTextPreferences = jsonTextBindings.mapNotNull { findPreference<EditConfigPreference>(it.cacheKey) }
        onPreferencesCreated()
    }

    override fun onResume() {
        super.onResume()
        rawJsonPreference?.notifyChanged()
        for (preference in jsonTextPreferences) preference.notifyChanged()
    }

    /** Called with the screen inflated and the bindings attached; wire visibility and input modifiers here. */
    protected open fun PreferenceFragmentCompat.onPreferencesCreated() {
    }

}
