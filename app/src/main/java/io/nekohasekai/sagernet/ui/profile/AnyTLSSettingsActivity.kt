package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.AnyTls

class AnyTLSSettingsActivity : BindingSettingsActivity<AnyTls>() {

    override fun createEntity() = AnyTls()
    override val preferencesResource = R.xml.anytls_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("password")
        pbm.text("idle_session_check_interval")
        pbm.text("idle_session_timeout")
        pbm.int("min_idle_session")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("min_idle_session")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
    }

}
