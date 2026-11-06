package app.rawline.mobile.bg.carrier

/** qWDTT's three login routes; VK ID's WebView error must advance the route once. */
internal object VkRelayLogin {
    fun awaitingLogin(url: String): Boolean = runCatching {
        val uri=java.net.URI(url)
        uri.host in listOf("id.vk.com","id.vk.ru") &&
            listOf("authorize","login","auth").any { url.contains(it,ignoreCase=true) }
    }.getOrDefault(false)
    fun startUrl(attempt: Int) = when(attempt) {
        0 -> "https://m.vk.ru/login"
        1 -> "https://m.vk.ru/"
        else -> "https://vk.ru/login"
    }
    fun userAgent(default: String,attempt: Int) = if(attempt < 2) default else
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    val headers = mapOf("Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
    val errorWatcher = """
        (function() {
            if(window.__qthrone_login_watcher) return;
            window.__qthrone_login_watcher=true;
            function check() {
                var t=((document.body && document.body.innerText)||'').toLowerCase();
                if(t.indexOf('unknown method')!==-1) window.RawlineVkLogin.onLoginError();
            }
            setInterval(check,900);
            if(document.documentElement) new MutationObserver(check).observe(document.documentElement,
                {childList:true,subtree:true,characterData:true});
            check();
        })();
    """.trimIndent()
}
