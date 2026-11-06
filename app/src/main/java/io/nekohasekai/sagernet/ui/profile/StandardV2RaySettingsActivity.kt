package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.types.Trojan
import app.rawline.mobile.outbound.types.Vless
import app.rawline.mobile.outbound.types.Vmess

/**
 * One screen for the three sing-box V2Ray-family types (vmess, vless, trojan): the same transport, TLS and
 * multiplex blocks, and a type-specific credential / option subset. The VLESS `encryption` field is Xray-only and
 * lives on [XrayVlessSettingsActivity].
 */
abstract class StandardV2RaySettingsActivity<T : Outbound> : BindingSettingsActivity<T>() {

    override val preferencesResource = R.xml.standard_v2ray_preferences

    protected abstract val isVmess: Boolean
    protected abstract val isVless: Boolean
    protected abstract val isTrojan: Boolean

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("password")
        pbm.int("alter_id")
        pbm.text("security")
        pbm.text("flow")
        pbm.text("packet_encoding")
        pbm.bool("global_padding")
        pbm.bool("authenticated_length")
        TransportBlock.bind(pbm)
        TlsBlock.bind(pbm)
        MuxBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("alter_id")
        passwordSummary("uuid", "password")

        setVisible(!isTrojan, "uuid", "packet_encoding")
        setVisible(isTrojan, "password")
        setVisible(isVmess, "alter_id", "security", "global_padding", "authenticated_length")
        setVisible(isVless, "flow")

        TransportBlock.setup(this)
        TlsBlock.setup(this, mustTls = false)
        MuxBlock.setup(this)
        // UI only, as on the desktop (dialog_edit_profile.cpp:397-406): the build still emits the mux state
        if (isVless) onMenu("flow") { findPreference<PreferenceCategory>("muxCategory")?.isEnabled = it != "xtls-rprx-vision" }
    }

}

class VMessSettingsActivity : StandardV2RaySettingsActivity<Vmess>() {
    override fun createEntity() = Vmess()
    override val isVmess = true
    override val isVless = false
    override val isTrojan = false
}

class VlessSettingsActivity : StandardV2RaySettingsActivity<Vless>() {
    override fun createEntity() = Vless()
    override val isVmess = false
    override val isVless = true
    override val isTrojan = false
}

class TrojanSettingsActivity : StandardV2RaySettingsActivity<Trojan>() {
    override fun createEntity() = Trojan()
    override val isVmess = false
    override val isVless = false
    override val isTrojan = true
}
