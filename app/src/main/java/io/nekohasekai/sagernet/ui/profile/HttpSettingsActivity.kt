package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Http

class HttpSettingsActivity : BindingSettingsActivity<Http>() {

    override fun createEntity() = Http()
    override val preferencesResource = R.xml.http_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.text("path")
        pbm.text("headers")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        multilineInput("headers")
        TlsBlock.setup(this, mustTls = false)
    }

}
