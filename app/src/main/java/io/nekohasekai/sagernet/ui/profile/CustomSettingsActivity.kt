package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.outbound.types.Custom
import app.rawline.mobile.ui.json.engine.SchemaStore
import moe.matsuri.nb4a.ui.EditConfigPreference

/** A raw JSON profile in one of the four custom subtypes (sing-box / Xray, outbound / full config). */
class CustomSettingsActivity : BindingSettingsActivity<Custom>() {

    override fun createEntity() = Custom().apply { subtype = Custom.CUSTOM_OUTBOUND }
    override val preferencesResource = R.xml.custom_preferences
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("subtype")
        pbm.text("config", Key.SERVER_CONFIG)
    }

    private var editConfigPreference: EditConfigPreference? = null

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        editConfigPreference = findPreference<EditConfigPreference>(Key.SERVER_CONFIG)?.apply {
            schemaProvider = { schemaRoots(DataStore.profileCacheStore.getString("subtype")) }
        }
    }

    override fun onResume() {
        super.onResume()
        editConfigPreference?.notifyChanged()
    }

    /** edit_custom.cpp:88-100: sing-box outbound or endpoint, sing-box config, nothing for the Xray subtypes. */
    private fun schemaRoots(subtype: String?): List<String> = when (subtype) {
        Custom.CUSTOM_OUTBOUND -> listOf(SchemaStore.OUTBOUND, SchemaStore.ENDPOINT)
        Custom.CUSTOM_FULL_CONFIG -> listOf(SchemaStore.CONFIG)
        else -> emptyList()
    }

}
