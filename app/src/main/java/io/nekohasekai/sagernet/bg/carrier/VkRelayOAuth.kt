package app.qthrone.mobile.bg.carrier

import java.net.URI
import java.net.URLDecoder

/** Redirects stay on VK; tokens and cookies never reach an external redirect target. */
internal object VkRelayOAuth {
    fun obtainToken(cached: String, expires: Long, now: Long, http: () -> String?, web: () -> String?): String? {
        if(validToken(cached) && expires>now+60_000) return cached
        val refreshed=try { http() } catch(_: java.io.IOException) { null }
        return refreshed?.takeIf(::validToken) ?: web()?.takeIf(::validToken)
    }
    private fun validToken(token: String) = token.isNotBlank() && token.length<=4096 && token.none { it.isWhitespace() || it.isISOControl() }
    fun trustedHttps(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" || host.endsWith(".vk.ru"))
    }
    fun tokenFields(url: String,state: String): Pair<String,Long>? = runCatching {
        val uri = URI(url)
        if(!trustedHttps(uri) || uri.host !in listOf("oauth.vk.com","oauth.vk.ru") || uri.path != "/blank.html") return null
        val parts = listOfNotNull(uri.rawQuery,uri.rawFragment).flatMap { it.split('&') }.filter(String::isNotEmpty).map { field ->
            URLDecoder.decode(field.substringBefore('='),"UTF-8") to URLDecoder.decode(field.substringAfter('=',""),"UTF-8")
        }
        if(parts.map { it.first }.distinct().size!=parts.size) return null
        val fields=parts.toMap()
        val token = fields["access_token"].orEmpty()
        if(fields["state"] != state || !validToken(token)) return null
        token to (fields["expires_in"]?.toLongOrNull() ?: 0L)
    }.getOrNull()
    fun userId(url: String): String? = Regex("[#?&]user_id=(\\d+)").find(url)?.groupValues?.get(1)
    /** VK keeps the signed-in id in remixmid; "0" means nobody. */
    fun userIdFromCookies(cookies: Collection<String>): String = cookies.asSequence().flatMap { it.split(';') }
        .map { it.trim() }.firstOrNull { it.startsWith("remixmid=") }?.substringAfter('=')?.takeIf { it.isNotBlank() && it != "0" }.orEmpty()
    fun nextAuthorizeUrl(body: String): String? {
        val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
        if(href != null) return href.replace("&amp;","&")
        return Regex("""(https://login\.vk\.(?:com|ru)/\?act=grant_access[^"'\s<]+)""")
            .find(body)?.groupValues?.get(1)?.replace("&amp;","&")
    }
}
