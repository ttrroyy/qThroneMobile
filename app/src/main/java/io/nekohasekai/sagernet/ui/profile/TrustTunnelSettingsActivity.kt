package app.rawline.mobile.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.TrustTunnel

class TrustTunnelSettingsActivity : BindingSettingsActivity<TrustTunnel>() {

    override fun createEntity() = TrustTunnel()
    override val preferencesResource = R.xml.trusttunnel_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("quic")
        pbm.text("congestion_control")
        pbm.text("custom_sni")
        pbm.text("client_random")
        pbm.bool("health_check")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
        // QUIC dials through qtls, which takes no uTLS config (dialog_edit_profile.cpp:759-763)
        onSwitch("quic") { findPreference<Preference>("tls.utls.fingerPrint")?.isEnabled = !it }
    }

}
