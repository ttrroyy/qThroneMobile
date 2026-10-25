package app.qthrone.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.outbound.types.Socks

class SocksSettingsActivity : BindingSettingsActivity<Socks>() {

    override fun createEntity() = Socks()
    override val preferencesResource = R.xml.socks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.int("version")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("uot")
        pbm.text("phobos_key")
        pbm.text("phobos_masking")
        pbm.text("phobos_media_ssrc")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password", "phobos_key")
        onMenu("version") { setVisible(it != "4", "password") }
    }

}
