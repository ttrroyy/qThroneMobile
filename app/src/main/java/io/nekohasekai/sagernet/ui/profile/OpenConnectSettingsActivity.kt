package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.OpenConnect

/** OpenConnect endpoint: the primary fields on the screen, the full option set through the Raw JSON preference. */
class OpenConnectSettingsActivity : BindingSettingsActivity<OpenConnect>() {

    override fun createEntity() = OpenConnect()
    override val preferencesResource = R.xml.openconnect_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("server_path")
        pbm.text("flavor")
        pbm.text("username")
        pbm.text("password")
        pbm.text("auth_group")
        pbm.int("mtu")
        pbm.text("tls.server_name")
        pbm.bool("tls.insecure")
        pbm.text("tls.certificate_authority")
        pbm.text("tls.client_certificate")
        pbm.text("tls.client_key")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("mtu")
        passwordSummary("password")
        multilineInput("tls.certificate_authority", "tls.client_certificate", "tls.client_key")
    }

}
