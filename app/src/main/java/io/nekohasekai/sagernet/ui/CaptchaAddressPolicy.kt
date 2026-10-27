package app.qthrone.mobile.ui

import java.net.URI
import java.util.Locale

internal object CaptchaAddressPolicy {
    fun allowed(value: String): Boolean = allowed(value,listOf("vk.com","vk.ru","ok.ru","okcdn.ru"))

    /** OpenFlux's provider checks: Yandex SmartCaptcha and its sign-in (as the core's own captchaAddress). */
    fun allowedOpenFlux(value: String): Boolean = allowed(value,listOf("yandex.ru","yandex.com","yandex.net","ya.ru","mail.ru"))

    /** A Yandex page that is still the check or a sign-in, not the document behind it (CaptchaBrowser.isCheckpoint). */
    fun openFluxCheckpoint(value: String): Boolean =
        "showcaptcha" in value || "passport.yandex" in value || "/captcha" in value

    private fun allowed(value: String,domains: List<String>): Boolean {
        val uri=runCatching { URI(value) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https",ignoreCase=true) && uri.rawUserInfo==null && uri.port in listOf(-1,443) &&
            domains.any { host==it || host.endsWith("."+it) }
    }
}
