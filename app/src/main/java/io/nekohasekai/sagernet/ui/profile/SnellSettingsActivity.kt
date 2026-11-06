package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Snell

/** Snell v4 (obfs) and v6 (mode); the core speaks no other client version. */
class SnellSettingsActivity : BindingSettingsActivity<Snell>() {

    override fun createEntity() = Snell()
    override val preferencesResource = R.xml.snell_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.int("version")
        pbm.text("psk")
        pbm.text("userkey")
        pbm.text("network")
        pbm.text("obfs_mode")
        pbm.text("obfs_host")
        pbm.text("mode")
        pbm.bool("reuse")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("psk", "userkey")
        onMenu("version") { version ->
            val v6 = version == "6"
            setVisible(!v6, "obfs_mode", "obfs_host")
            setVisible(v6, "mode")
        }
    }

}
