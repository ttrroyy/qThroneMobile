package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Ssh

class SSHSettingsActivity : BindingSettingsActivity<Ssh>() {

    override fun createEntity() = Ssh()
    override val preferencesResource = R.xml.ssh_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("user")
        pbm.text("password")
        pbm.text("private_key")
        pbm.text("private_key_passphrase")
        pbm.text("host_key")
        pbm.text("host_key_algorithms")
        pbm.text("client_version")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password", "private_key_passphrase")
        multilineInput("private_key", "host_key", "host_key_algorithms")
    }

}
