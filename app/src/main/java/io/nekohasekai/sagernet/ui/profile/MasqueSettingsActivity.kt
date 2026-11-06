package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Masque
import app.rawline.mobile.ui.warp.WarpClient
import app.rawline.mobile.ui.warp.WarpGenerate
import app.rawline.mobile.ui.warp.setFieldText

/** MASQUE endpoint: the TLS camouflage (no Reality, masque.cpp strips it) and QUIC blocks; the rest through Raw JSON. */
class MasqueSettingsActivity : BindingSettingsActivity<Masque>() {

    override fun createEntity() = Masque()
    override val preferencesResource = R.xml.masque_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("private_key")
        pbm.text("peer_public_key")
        pbm.text("address")
        pbm.int("mtu")
        pbm.int("http_version")
        pbm.bool("disable_version_fallback")
        TlsBlock.bind(pbm)
        QuicBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("mtu")
        passwordSummary("private_key")
        multilineInput("address")
        TlsBlock.setup(this, mustTls = true)
        QuicBlock.setup(this)

        // EditMasque::applyWarpIdentity (edit_masque.cpp:92-111)
        WarpGenerate.bindEditorRow(
            this@MasqueSettingsActivity, this, WarpClient.TUNNEL_MASQUE, R.string.warp_masque_profile_name,
        ) { identity ->
            setFieldText("private_key", identity.privateKey)
            setFieldText("peer_public_key", identity.peerPublicKey)
            setFieldText("address", identity.addresses.joinToString("\n"))
            setFieldText("mtu", "1280")
            identity.endpointHostPort()?.let { (host, port) ->
                setFieldText("server", host)
                setFieldText("serverPort", port)
            }
        }
    }

}
