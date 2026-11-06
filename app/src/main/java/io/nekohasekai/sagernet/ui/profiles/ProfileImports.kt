package app.rawline.mobile.ui.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.ktx.snackbar
import app.rawline.mobile.ktx.startFilesForResult
import app.rawline.mobile.ui.ConfigurationFragment
import app.rawline.mobile.ui.ScannerActivity
import app.rawline.mobile.ui.SubscribeFlows
import app.rawline.mobile.ui.profile.AnyTLSSettingsActivity
import app.rawline.mobile.ui.profile.AutoSelectorSettingsActivity
import app.rawline.mobile.ui.profile.ChainSettingsActivity
import app.rawline.mobile.ui.profile.CustomSettingsActivity
import app.rawline.mobile.ui.profile.DirectSettingsActivity
import app.rawline.mobile.ui.profile.HttpSettingsActivity
import app.rawline.mobile.ui.profile.HysteriaSettingsActivity
import app.rawline.mobile.ui.profile.JuicitySettingsActivity
import app.rawline.mobile.ui.profile.MasqueSettingsActivity
import app.rawline.mobile.ui.profile.MieruSettingsActivity
import app.rawline.mobile.ui.profile.NaiveSettingsActivity
import app.rawline.mobile.ui.profile.OpenConnectSettingsActivity
import app.rawline.mobile.ui.profile.OpenVpnSettingsActivity
import app.rawline.mobile.ui.profile.SSHSettingsActivity
import app.rawline.mobile.ui.profile.ShadowTLSSettingsActivity
import app.rawline.mobile.ui.profile.ShadowsocksSettingsActivity
import app.rawline.mobile.ui.profile.ShadowsocksRSettingsActivity
import app.rawline.mobile.ui.profile.SnellSettingsActivity
import app.rawline.mobile.ui.profile.SocksSettingsActivity
import app.rawline.mobile.ui.profile.TrojanSettingsActivity
import app.rawline.mobile.ui.profile.TrustTunnelSettingsActivity
import app.rawline.mobile.ui.profile.TuicSettingsActivity
import app.rawline.mobile.ui.profile.VMessSettingsActivity
import app.rawline.mobile.ui.profile.VlessSettingsActivity
import app.rawline.mobile.ui.profile.WireGuardSettingsActivity
import app.rawline.mobile.ui.profile.XrayVlessSettingsActivity

/** The "+" menu: scan, clipboard, file and the new-profile editors. Imports go to the current tab's group. */
class ProfileImports(private val host: ConfigurationFragment) {

    private val importFile = host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }

    /** Devices without a camera (TVs) have nothing to scan with. */
    fun prepare(menu: Menu) {
        menu.findItem(R.id.action_scan_qr_code)?.isVisible = hasCamera(host.requireContext())
    }

    private fun hasCamera(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> host.startActivity(Intent(host.requireContext(), ScannerActivity::class.java))
            R.id.action_import_clipboard -> importClipboard()
            R.id.action_import_file -> host.startFilesForResult(importFile, "*/*")
            else -> {
                val editor = editorFor(item.itemId) ?: return false
                host.startActivity(Intent(host.requireContext(), editor))
            }
        }
        return true
    }

    /** import_or_handle_deeplink: links, documents, deep links and subscription URLs, into the current tab's group. */
    private fun importClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            host.snackbar(R.string.clipboard_empty).show()
        } else {
            SubscribeFlows.importText(host.requireActivity(), text)
        }
    }

    private fun importFile(uri: Uri) = SubscribeFlows.importFile(host.requireActivity(), uri)

    private fun editorFor(itemId: Int): Class<*>? = when (itemId) {
        R.id.action_new_auto_selector -> AutoSelectorSettingsActivity::class.java
        R.id.action_new_socks -> SocksSettingsActivity::class.java
        R.id.action_new_http -> HttpSettingsActivity::class.java
        R.id.action_new_ss -> ShadowsocksSettingsActivity::class.java
        R.id.action_new_ssr -> ShadowsocksRSettingsActivity::class.java
        R.id.action_new_vmess -> VMessSettingsActivity::class.java
        R.id.action_new_vless -> VlessSettingsActivity::class.java
        R.id.action_new_xray_vless -> XrayVlessSettingsActivity::class.java
        R.id.action_new_trojan -> TrojanSettingsActivity::class.java
        R.id.action_new_mieru -> MieruSettingsActivity::class.java
        R.id.action_new_naive -> NaiveSettingsActivity::class.java
        R.id.action_new_hysteria -> HysteriaSettingsActivity::class.java
        R.id.action_new_tuic -> TuicSettingsActivity::class.java
        R.id.action_new_juicity -> JuicitySettingsActivity::class.java
        R.id.action_new_ssh -> SSHSettingsActivity::class.java
        R.id.action_new_snell -> SnellSettingsActivity::class.java
        R.id.action_new_wg -> WireGuardSettingsActivity::class.java
        R.id.action_new_shadowtls -> ShadowTLSSettingsActivity::class.java
        R.id.action_new_anytls -> AnyTLSSettingsActivity::class.java
        R.id.action_new_trusttunnel -> TrustTunnelSettingsActivity::class.java
        R.id.action_new_direct -> DirectSettingsActivity::class.java
        R.id.action_new_masque -> MasqueSettingsActivity::class.java
        R.id.action_new_openvpn -> OpenVpnSettingsActivity::class.java
        R.id.action_new_openconnect -> OpenConnectSettingsActivity::class.java
        R.id.action_new_config -> CustomSettingsActivity::class.java
        R.id.action_new_chain -> ChainSettingsActivity::class.java
        else -> null
    }
}
