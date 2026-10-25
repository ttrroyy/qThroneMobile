package app.qthrone.mobile.ui.profile

import android.text.InputType
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.CoreServiceClient
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.dp2px
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.ui.MobileAlertDialogBuilder
import app.qthrone.mobile.outbound.types.WireGuard
import app.qthrone.mobile.ui.warp.WarpClient
import app.qthrone.mobile.ui.warp.WarpGenerate
import app.qthrone.mobile.ui.warp.setFieldText
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        findPreference<Preference>(KEY_QUIC_SIGNATURE)?.setOnPreferenceClickListener { button ->
            askSignatureDomain(this, button)
            true
        }
    }

    private fun askSignatureDomain(screen: PreferenceFragmentCompat, button: Preference) {
        val layout = TextInputLayout(this).apply { hint = getString(R.string.amnezia_quic_signature_domain) }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(lastSignatureDomain)
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        MobileAlertDialogBuilder(this)
            .setTitle(R.string.amnezia_quic_signature_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val domain = input.text?.toString()?.trim().orEmpty()
                if (domain.isNotEmpty()) {
                    lastSignatureDomain = domain
                    generateSignature(screen, button, domain)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateSignature(screen: PreferenceFragmentCompat, button: Preference, domain: String) {
        val idle = button.summary
        button.isEnabled = false
        button.summary = getString(R.string.generating)
        lifecycleScope.launch {
            val raw = try {
                withContext(Dispatchers.IO) { CoreServiceClient.call { it.genQuicSignature(domain) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            button.summary = idle
            button.isEnabled = true
            val result = raw?.let { JsonInput.parseObjectOrNull(it) }
            val packets = result?.array("packets")?.strings().orEmpty()
            val error = if (result == null) getString(R.string.warp_core_unreachable) else result.string("error")
            if (error.isNotEmpty() || packets.isEmpty()) {
                MobileAlertDialogBuilder(this@WireGuardSettingsActivity)
                    .setTitle(R.string.amnezia_quic_signature_failed)
                    .apply { if (error.isNotEmpty()) setMessage(error) }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            // An "Edit as JSON" round trip may have rebuilt the screen meanwhile.
            val target = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat ?: screen
            SIGNATURE_FIELDS.forEachIndexed { i, key -> target.setFieldText(key, packets.getOrElse(i) { "" }) }
        }
    }

    private companion object {
        const val KEY_QUIC_SIGNATURE = "amneziaQuicSignature"
        val SIGNATURE_FIELDS = listOf("i1", "i2", "i3", "i4", "i5")

        // Offered again next time.
        var lastSignatureDomain = ""
    }

}
