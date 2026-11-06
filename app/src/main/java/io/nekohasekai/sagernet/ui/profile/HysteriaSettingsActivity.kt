package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Hysteria

/** Hysteria v1 and v2 on one screen; the version menu gates the version-specific fields and the v2 realm block. */
class HysteriaSettingsActivity : BindingSettingsActivity<Hysteria>() {

    override fun createEntity() = Hysteria().apply { protocol_version = "2" }
    override val preferencesResource = R.xml.hysteria_preferences

    init {
        pbm.text("name")
        pbm.text("protocol_version")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("server_ports")
        pbm.text("hop_interval")
        pbm.text("hop_interval_max")
        pbm.text("auth_type")
        pbm.text("auth")
        pbm.text("password")
        pbm.text("obfs_type")
        pbm.text("obfs")
        pbm.int("up_mbps")
        pbm.int("down_mbps")
        pbm.int("recv_window_conn")
        pbm.int("recv_window")
        pbm.bool("disable_mtu_discovery")
        pbm.int("min_packet_size")
        pbm.int("max_packet_size")
        pbm.text("bbr_profile")
        pbm.bool("disable_chrome_parrot")
        pbm.bool("realm_enabled")
        pbm.text("realm_server_url")
        pbm.text("realm_token")
        pbm.text("realm_id")
        pbm.text("realm_stun_servers")
        pbm.int("realm_ip_version")
        pbm.bool("realm_port_mapping")
        pbm.text("realm_port_mapping_timeout")
        pbm.text("realm_port_mapping_lifetime")
        TlsBlock.bind(pbm)
        QuicBlock.bind(pbm)
    }

    private val v1Only = arrayOf("auth_type", "auth", "recv_window_conn", "recv_window", "disable_mtu_discovery")
    private val v2Only = arrayOf(
        "password", "obfs_type", "hop_interval_max", "min_packet_size", "max_packet_size", "bbr_profile",
        "disable_chrome_parrot",
    )
    private val realmFields = arrayOf(
        "realm_server_url", "realm_token", "realm_id", "realm_stun_servers", "realm_ip_version", "realm_port_mapping",
        "realm_port_mapping_timeout", "realm_port_mapping_lifetime",
    )

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("up_mbps", "down_mbps", "recv_window_conn", "recv_window", "min_packet_size", "max_packet_size")
        passwordSummary("auth", "password", "obfs", "realm_token")
        multilineInput("server_ports", "realm_stun_servers")

        val realm = findPreference<PreferenceCategory>("hysteriaRealmCategory")
        fun applyVersion(version: String) {
            val v2 = version == "2"
            setVisible(!v2, *v1Only)
            setVisible(v2, *v2Only)
            realm?.isVisible = v2
        }
        onMenu("protocol_version") { applyVersion(it) }
        onMenu("auth_type") { setVisible(it.isNotEmpty(), "auth") }
        onSwitch("realm_enabled") { setVisible(it, *realmFields) }

        TlsBlock.setup(this, mustTls = true)
        QuicBlock.setup(this)
    }

}
