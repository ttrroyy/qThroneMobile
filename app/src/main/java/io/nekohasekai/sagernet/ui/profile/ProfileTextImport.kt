package app.rawline.mobile.ui.profile

import app.rawline.mobile.database.SettingsMapper
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.`import`.ProfileImport

/**
 * The UI's entry to the profile text parser (WP-S `ProfileImport`): clipboard text, QR payloads and file contents
 * (links, sing-box / Clash / SIP008 documents, WireGuard INI, `.ovpn`, openconnect config, AnyConnect XML).
 */
object ProfileTextImport {

    /** Every outbound found in [text]; empty when nothing parsed. The parser's notes (skipped nodes) go to the log. */
    fun parse(text: String): List<Outbound> {
        val result = ProfileImport.parse(text, SettingsMapper.xrayVlessPreference())
        for (message in result.messages) Logs.w(message)
        return result.outbounds
    }
}
