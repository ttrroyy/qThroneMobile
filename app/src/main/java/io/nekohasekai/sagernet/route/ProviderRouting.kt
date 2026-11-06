package app.rawline.mobile.route

import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.Base64Strict
import app.rawline.mobile.outbound.config.GeneratorSettings

/** Happ and Incy routing documents are kept with their subscription, including unknown fields. */
object ProviderRouting {
    data class Command(val provider: String, val activate: Boolean, val off: Boolean, val document: JsonObject?)
    private val link = Regex("^(happ|incy)?://routing/(add|onadd|off)(?:/([^?#]*))?(?:\\?data=([^#]*))?(?:#.*)?$", RegexOption.IGNORE_CASE)
    // Browser launcher links carry the actual routing document in their url query.
    // Decode locally so the launcher HTML is never mistaken for a server subscription.
    private fun unwrap(text: String): String {
        val raw=text.trim()
        if(raw.length>2*1024*1024) return raw
        return runCatching {
            val uri=java.net.URI(raw)
            if(uri.scheme?.lowercase() !in listOf("http","https") || uri.host.isNullOrBlank()) return@runCatching raw
            val parameter=uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=')=="url" } ?: return@runCatching raw
            val value=java.net.URLDecoder.decode(parameter.substringAfter('=',"").replace("+","%2B"),"UTF-8")
            if(link.matches(value)) value else raw
        }.getOrDefault(raw)
    }
    fun isLink(text: String) = link.matches(unwrap(text))
    fun parse(text: String, provider: String = "Happ"): Command {
        require(text.length <= 2 * 1024 * 1024) { "Routing profile is too large" }
        val raw = unwrap(text)
        val input = if (raw.contains("://routing/") && !isLink(raw)) provider.lowercase() + raw.substring(raw.indexOf("://routing/")) else raw
        val match = link.matchEntire(input)
        val source = match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: provider
        if (input.equals("off", true) || match?.groupValues?.get(2)?.equals("off", true) == true)
            return Command(source, false, true, null)
        val payload = match?.let { java.net.URLDecoder.decode(it.groupValues[3].ifEmpty { it.groupValues[4] }.replace("+", "%2B"), "UTF-8") } ?: input
        val json = JsonInput.parseObjectOrNull(payload) ?: JsonInput.parseObjectOrNull(Base64Strict.decodeToString(payload)) ?: JsonInput.parseObjectOrNull(Base64Strict.decodeToString(payload,true))
            ?: error("Профиль маршрутизации $source неполный или повреждён. Скопируйте ссылку целиком.")
        require(json.contains("GlobalProxy") || json.contains("DirectSites") || json.contains("Name")) { "Not a $source routing profile" }
        for (key in listOf("DirectSites", "DirectIp", "ProxySites", "ProxyIp", "BlockSites", "BlockIp")) {
            require(!json.contains(key) || json.isArray(key)) { "$key must be a list" }
            require(json.array(key).size <= 100000) { "Too many routing rules" }
        }
        return Command(source, match?.groupValues?.get(2)?.equals("onadd", true) == true, false, json)
    }
    data class Payload(val body: String, val commands: List<Command>, val warnings: List<String> = emptyList())
    fun subscription(body: String, header: String = "", provider: String = "Happ", ignoreRouting: Boolean = false): Payload {
        var text = body.trim()
        if (!text.contains("://") && !text.startsWith("{") && !text.startsWith("[")) {
            val decoded = Base64Strict.decodeToString(text.filterNot { it.isWhitespace() })
            if (decoded.contains("://")) text = decoded
        }
        val commands = mutableListOf<Command>()
        val warnings = mutableListOf<String>()
        fun importRoute(value: String) {
            runCatching { parse(value,provider) }
                .onSuccess { commands.add(it) }
                .onFailure { warnings.add(it.message ?: "Повреждённый профиль маршрутизации") }
        }
        if (header.isNotBlank() && !ignoreRouting) importRoute(header)
        val cleaned = text.lineSequence().filter { line ->
            if (isLink(line.trim())) { if (!ignoreRouting) importRoute(line.trim()); false } else true
        }.joinToString("\n")
        return Payload(cleaned, commands, warnings)
    }
    fun truth(json: JsonObject, key: String, default: Boolean): Boolean = when (val value = json[key]) {
        is Boolean -> value
        is String -> value.equals("true", true) || value == "1"
        else -> default
    }
    fun profile(json: JsonObject, provider: String): RouteProfile = RouteProfile.defaultProfile().apply {
        name = json.string("Name").ifBlank { "$provider Default" }
        default_outbound_id = if (truth(json, "GlobalProxy", true)) OutboundIds.PROXY else OutboundIds.DIRECT
        raw_route = jsonObjectOf("provider" to provider, "profile" to json).toCompact()
        prevent_modifications = true
        val order = json.string("RouteOrder").lowercase().split('-')
        val names = if (order.size == 3 && order.toSet() == setOf("block","proxy","direct")) order else listOf("block","proxy","direct")
        for (item in names) {
            val prefix = item.replaceFirstChar { it.uppercase() }
            val outbound = when (item) { "block" -> OutboundIds.BLOCK; "direct" -> OutboundIds.DIRECT; else -> OutboundIds.PROXY }
            for ((key, ip) in listOf("${prefix}Sites" to false, "${prefix}Ip" to true)) {
                val rule = RouteRule().apply { name = key; outbound_id = outbound }
                for (entry in json.array(key).strings().filter { it.isNotBlank() }) {
                    when {
                        entry.startsWith("geosite:") || entry.startsWith("geoip:") -> rule.rule_set.add(entry)
                        ip -> rule.ip_cidr.addAll(MobileRouteInput.networks(entry))
                        entry.startsWith("full:") -> rule.domain.add(entry.removePrefix("full:"))
                        entry.startsWith("regexp:") -> rule.domain_regex.add(entry.removePrefix("regexp:"))
                        entry.startsWith("keyword:") -> rule.domain_keyword.add(entry.removePrefix("keyword:"))
                        else -> rule.domain_suffix.add(entry.removePrefix("domain:"))
                    }
                }
                if (rule.domain.isNotEmpty() || rule.domain_suffix.isNotEmpty() || rule.domain_keyword.isNotEmpty() || rule.domain_regex.isNotEmpty() || rule.rule_set.isNotEmpty() || rule.ip_cidr.isNotEmpty()) rules.add(rule)
            }
        }
        if (json.string("DomainStrategy") == "IPIfNonMatch") {
            val dns = rules.first()
            val domainRules = rules.drop(1).filter { it.name.endsWith("Sites") }
            val ipRules = rules.drop(1).filter { it.name.endsWith("Ip") }
            rules = mutableListOf(dns).apply {
                addAll(domainRules)
                if (ipRules.isNotEmpty()) add(RouteRule().apply { name = "Resolve unmatched domains"; action = "resolve"; strategy = "prefer_ipv4" })
                addAll(ipRules)
            }
        }
    }
    fun stored(profile: RouteProfile): Pair<String, JsonObject>? {
        val stored = JsonInput.parseObjectOrNull(profile.raw_route) ?: return null
        return if (stored.string("provider") in listOf("Happ", "Incy")) stored.string("provider") to stored.obj("profile") else null
    }
    fun settings(json: JsonObject, base: GeneratorSettings): GeneratorSettings {
        fun dns(prefix: String, fallback: String): String {
            val type = json.string("${prefix}DNSType")
            val domain = json.string("${prefix}DNSDomain")
            val ip = json.string("${prefix}DNSIP").ifBlank { json.string("${prefix}Dns") }
            return if (type.equals("DoH", true) && domain.isNotBlank()) domain else ip.ifBlank { fallback }
        }
        val hosts = json.obj("DnsHosts").map { "${it.value} ${it.key}" }.toMutableList()
        for (prefix in listOf("Remote","Domestic")) {
            val host = runCatching { java.net.URI(json.string("${prefix}DNSDomain")).host }.getOrNull()
            val ip = json.string("${prefix}DNSIP")
            if (!host.isNullOrBlank() && ip.isNotBlank() && !json.obj("DnsHosts").contains(host)) hosts.add("$ip $host")
        }
        return base.copy(remoteDns = dns("Remote", "https://cloudflare-dns.com/dns-query"),
            directDns = dns("Domestic", "8.8.8.8"), fakeDns = truth(json, "FakeDNS", false),
            dnsFinalOut = if (truth(json, "GlobalProxy", true)) "remote" else "direct",
            dnsPredefinedEnable = true, dnsPredefinedRules = base.dnsPredefinedRules + hosts,
            resolveDomainStrategy = if (json.string("DomainStrategy") == "IPOnDemand") "prefer_ipv4" else "")
    }
}
