package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Shadowsocks

class ShadowsocksSettingsActivity : BindingSettingsActivity<Shadowsocks>() {

    override fun createEntity() = Shadowsocks()
    override val preferencesResource = R.xml.shadowsocks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("method")
        pbm.text("password")
        pbm.text("plugin")
        pbm.text("plugin_opts")
        pbm.bool("uot")
        MuxBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        MuxBlock.setup(this)
    }

}
