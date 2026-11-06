package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Tuic

class TuicSettingsActivity : BindingSettingsActivity<Tuic>() {

    override fun createEntity() = Tuic()
    override val preferencesResource = R.xml.tuic_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("password")
        pbm.text("congestion_control")
        pbm.text("udp_relay_mode")
        pbm.bool("udp_over_stream")
        pbm.bool("zero_rtt_handshake")
        pbm.text("heartbeat")
        TlsBlock.bind(pbm)
        QuicBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
        QuicBlock.setup(this)
    }

}
