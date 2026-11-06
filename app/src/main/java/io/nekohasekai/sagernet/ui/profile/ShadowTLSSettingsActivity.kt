package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.ShadowTls

class ShadowTLSSettingsActivity : BindingSettingsActivity<ShadowTls>() {

    override fun createEntity() = ShadowTls()
    override val preferencesResource = R.xml.shadowtls_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.int("version")
        pbm.text("password")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        onMenu("version") { setVisible(it != "1", "password") }
        TlsBlock.setup(this, mustTls = true)
    }

}
