package app.qthrone.mobile.bg.carrier

internal object NativeCaptchaPolicy {
    const val AUTOMATIC_TIMEOUT_MS = 10_000L
    /** The original manual WebView closes itself after a minute; the client stops waiting too. */
    const val VISIBLE_TIMEOUT_MS = 60_000L
    /** WDTT Plus's own WebView windows: 18 s for the hidden attempt, 180 s for the visible one. */
    fun automaticTimeout(client: String) = if (client == "wdttplus") 18_000L else AUTOMATIC_TIMEOUT_MS
    // A Yandex check has no client-side deadline in the original OpenFlux apps; five minutes are plenty.
    fun visibleTimeout(client: String) = when (client) { "wdttplus" -> 180_000L; "openflux","openfluxkey" -> 300_000L; else -> VISIBLE_TIMEOUT_MS }
    fun dismissal(automatic:Boolean):String = if(automatic) "error:timeout" else "error:cancelled"
    fun automatic(line:String):Boolean {
        val fields=line.split('|',limit=4)
        return fields.size==4 && fields[0]=="CAPTCHA_SOLVE" && fields[1]=="auto"
    }
    fun permitted(allowUi:Boolean,cancelled:Boolean,automatic:Boolean):Boolean =
        !cancelled && (allowUi || automatic)
}
