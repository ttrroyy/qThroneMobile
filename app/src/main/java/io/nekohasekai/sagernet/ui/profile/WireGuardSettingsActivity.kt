package app.qthrone.mobile.ui.profile

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.outbound.types.WireGuard
import app.qthrone.mobile.ui.warp.WarpClient
import app.qthrone.mobile.ui.warp.WarpGenerate
import app.qthrone.mobile.ui.warp.setFieldText

/** WireGuard / AmneziaWG: the endpoint lives in `peer.*`, the interface in the top-level fields. */
class WireGuardSettingsActivity : BindingSettingsActivity<WireGuard>() {

    override fun createEntity() = WireGuard()
    override val preferencesResource = R.xml.wireguard_preferences

    init {
        pbm.text("name")
        pbm.text("peer.address")
        pbm.int("peer.port")
        pbm.text("address")
        pbm.text("private_key")
        pbm.text("peer.public_key")
        pbm.text("peer.pre_shared_key")
        pbm.text("peer.reserved")
        pbm.text("peer.persistent_keepalive")
        pbm.int("mtu")
        pbm.bool("system")
        pbm.int("worker_count")
        pbm.text("udp_timeout")
        pbm.bool("enable_amnezia")
        for (field in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")) pbm.int(field)
        for (field in listOf(
            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "header_protection_key", "content_padding_addition",
            "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts",
        )) pbm.text(field)
        pbm.bool("random_trailers")
        pbm.bool("disable_cookies")
        pbm.text("obfs_type")
        pbm.text("obfs_key")
        pbm.text("obfs_masking")
        pbm.int("obfs_obfuscate_bytes")
        pbm.text("obfs_media_ssrc")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("peer.port")
        numberInput("mtu", "worker_count", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "obfs_obfuscate_bytes")
        passwordSummary("private_key", "peer.pre_shared_key")
        multilineInput("address", "peer.reserved")
        // stored one per line, shown on one
        findPreference<EditTextPreference>("peer.reserved")?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    .ifEmpty { getString(androidx.preference.R.string.not_set) }
            }

        val amnezia = findPreference<PreferenceCategory>("amneziaCategory")
        onSwitch("enable_amnezia") { on ->
            if (amnezia == null) return@onSwitch
            for (i in 0 until amnezia.preferenceCount) {
                val child = amnezia.getPreference(i)
                if (child.key != "enable_amnezia") child.isVisible = on
            }
        }

        // wg-obfuscator has masking options; wgobfs only a key.
        onMenu("obfs_type") { type ->
            setVisible(type == "wg-obfuscator" || type == "wgobfs", "obfs_key")
            setVisible(type == "wg-obfuscator", "obfs_masking", "obfs_obfuscate_bytes", "obfs_media_ssrc")
        }

        // edit_wireguard.cpp:39-51
        WarpGenerate.bindEditorRow(
            this@WireGuardSettingsActivity, this, WarpClient.TUNNEL_WIREGUARD, R.string.warp_wireguard_profile_name,
        ) { identity ->
            setFieldText("private_key", identity.privateKey)
            setFieldText("peer.public_key", identity.peerPublicKey)
            setFieldText("address", identity.addresses.joinToString("\n"))
            setFieldText("mtu", "1280")
            setFieldText("peer.persistent_keepalive", "30")
            identity.endpointHostPort()?.let { (host, port) ->
                setFieldText("peer.address", host)
                setFieldText("peer.port", port)
            }
            setFieldText("peer.reserved", identity.reserved.joinToString("\n"))
        }
    }

}
