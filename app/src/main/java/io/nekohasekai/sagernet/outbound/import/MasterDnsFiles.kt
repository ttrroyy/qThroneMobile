package app.rawline.mobile.outbound.import

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf
import app.rawline.mobile.outbound.types.DnsTunnel

/** MasterDnsVPN's own client configuration and WhiteDNS's profile file, as the desktop reads them (SubscriptionParser.cpp). */
object MasterDnsFiles {
    private val managedKeys = setOf("DOMAINS", "ENCRYPTION_KEY", "DATA_ENCRYPTION_METHOD", "PROTOCOL_TYPE",
        "LISTEN_IP", "LISTEN_PORT", "SOCKS5_AUTH", "SOCKS5_USER", "SOCKS5_PASS", "RESOLVERS_FILE", "LOG_LEVEL")
    private val tomlTokens = Regex(""""(?:\\.|[^"\\])*"|'[^']*'|#.*|,\s*(?=])""")

    private fun tomlJson(text: String) = tomlTokens.replace(text) {
        val token = it.value
        when (token.first()) {
            '#', ',' -> ""
            '\'' -> JsonArray.of(token.substring(1, token.length - 1)).toCompact().drop(1).dropLast(1)
            else -> token
        }
    }

    private fun profile(domains: String, key: String, method: Int, label: String): JsonObject? {
        val list = domains.split(Regex("""[,\s"\[\]]+""")).filter { it.isNotEmpty() }
        if (list.isEmpty() || key.isBlank()) return null
        return jsonObjectOf("type" to "masterdns", "name" to label, "domain" to list.joinToString(","),
            "mkey" to key.trim(), "encryption" to method)
    }

    /** The native client's flat TOML config: strings, numbers, flags and arrays. */
    fun fromToml(text: String): JsonObject? {
        val values = JsonObject()
        var key = ""
        var value = ""
        for (raw in text.lines()) {
            if (key.isEmpty()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) continue
                val equals = line.indexOf('=')
                if (equals <= 0) return null
                key = line.substring(0, equals).trim().uppercase()
                value = line.substring(equals + 1)
            } else value += "\n$raw"
            val parsed = JsonInput.parseValue("[${tomlJson(value)}]") as? JsonArray ?: continue
            if (parsed.size != 1) return null
            values[key] = parsed.first()
            key = ""
        }
        return if (key.isEmpty()) fromJson(values) else null
    }

    /** WhiteDNS's profile file (what a masterdns:// link carries) or MasterDnsVPN's JSON config. */
    fun fromJson(doc: JsonObject): JsonObject? {
        if (doc.string("schema") == "whitedns.profile") {
            return DnsTunnel().takeIf { it.parseWhiteDnsProfile(doc) }?.exportToJson()
        }
        if (doc.contains("DOMAINS") && doc.contains("ENCRYPTION_KEY")) {
            val domains = if (doc.isArray("DOMAINS")) doc.array("DOMAINS").strings() else listOf(doc.string("DOMAINS"))
            return profile(domains.joinToString(","), doc.string("ENCRYPTION_KEY"),
                if (doc.contains("DATA_ENCRYPTION_METHOD")) doc.int("DATA_ENCRYPTION_METHOD") else 1, "")?.apply {
                val options = doc.copy()
                for (key in doc.keys()) if (key in managedKeys || key.startsWith("LOCAL_DNS_")) options.remove(key)
                if (options.isNotEmpty()) this["options"] = options
            }
        }
        return null
    }
}
