package app.rawline.mobile.bg.carrier

import org.json.JSONObject
import org.json.JSONArray
import moe.matsuri.nb4a.utils.LogRedactor

/** Remove private config values even when a native client prints them without field names. */
internal class CarrierLogRedactor(config: String, credentials: CarrierBridgeCredentials) {
    private val secrets = mutableSetOf(credentials.username, credentials.password)
    private val general = LogRedactor(false)
    init { collect(JSONObject(config)) }
    private fun collect(value: Any?, secret: Boolean = false) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                collect(value.opt(key), secret || Regex("(?i)pass|secret|token|cookie|key|hash|seed|user|login").containsMatchIn(key))
            }
            is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it), secret) }
            is String -> if (secret && value.isNotEmpty()) secrets.add(value)
        }
    }
    fun redact(line: String): String? {
        if (line.startsWith("CAPTCHA_SOLVE|") || line.startsWith("CAPTCHA_RESULT|") || line.startsWith("VK_AUTH_REQUIRED|") || line.startsWith("TURN_CREDS|")) return null
        var safe = line
        secrets.sortedByDescending { it.length }.forEach { secret ->
            safe = if (secret.length >= 4) safe.replace(secret, "<redacted>")
            else Regex("""(?<![\w])""" + Regex.escape(secret) + """(?![\w])""").replace(safe, "<redacted>")
        }
        return general.redact(safe).take(8192)
    }
}
