package app.rawline.mobile.bg.carrier

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.os.HandlerCompat
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ui.CaptchaAddressPolicy
import app.rawline.mobile.ui.CaptchaAutoScripts
import org.json.JSONArray
import kotlin.random.Random

/**
 * The hidden captcha attempt as the original qWDTT, CSQTT and WDTT Plus apps run it (their CaptchaWebViewManager): a
 * measured WebView that is never attached to a window, in the tunnel's own process, so it works with any screen of
 * the app open or none at all. The steps per client are those of the in-app automatic sheet (MobileCaptchaController).
 */
internal object HeadlessCaptcha {
    val clients = setOf("qwdtt", "csqtt", "wdttplus")
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    /** [done] gets the success token or an "error:" answer exactly once, on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun solve(url: String, client: String, done: (String) -> Unit) = main.post {
        if (!CaptchaAddressPolicy.allowed(url)) { done("error:invalid captcha address"); return@post }
        // One hidden WebView at a time, as the originals' mutex; the client moves on to its next step.
        if (busy) { done("error:timeout"); return@post }
        busy = true
        val plus = client == "wdttplus"
        val slider = if (plus) "error:slider_detected" else "error:slider detected"
        val token = Any()
        val density = app.resources.displayMetrics.density
        val web = WebView(app)
        var finished = false
        var aimed = false
        var clickedAt = 0L
        var settleAt = 0L
        var probes = 0
        fun later(delay: Long, block: () -> Unit) = HandlerCompat.postDelayed(main, block, token, delay)
        fun finish(result: String) {
            if (finished) return
            finished = true
            busy = false
            main.removeCallbacksAndMessages(token)
            web.stopLoading(); web.destroy()
            done(result)
        }
        fun send(down: Long, action: Int, x: Float, y: Float, pressure: Float) =
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, pressure, 1f, 0, 1f, 1f, 0, 0)
                .also { it.source = InputDevice.SOURCE_TOUCHSCREEN; web.dispatchTouchEvent(it); it.recycle() }
        // CSS pixels to view pixels: the page's own width against the measured view.
        fun scale(innerWidth: Double) = if (web.width > 0) web.width / innerWidth.toFloat().coerceAtLeast(1f) else density
        /** qWDTT and CSQTT: a random point of the label, held 80-180 ms. */
        fun touch(label: JSONArray) {
            val scale = scale(label.getDouble(4))
            val x = (label.getDouble(0) + label.getDouble(2) * (0.15 + Random.nextDouble() * 0.7)).toFloat() * scale
            val y = (label.getDouble(1) + label.getDouble(3) * (0.25 + Random.nextDouble() * 0.5)).toFloat() * scale
            val down = SystemClock.uptimeMillis()
            send(down, MotionEvent.ACTION_DOWN, x, y, 0.5f + Random.nextFloat() * 0.4f)
            later(80L + Random.nextLong(0, 100)) {
                send(down, MotionEvent.ACTION_UP, x + (Random.nextFloat() * 2f - 1f) * density, y + (Random.nextFloat() - 0.5f) * density, 0f)
            }
        }
        /** WDTT Plus: the checkbox square, a small move, held 150-320 ms. */
        fun touchPlus(target: JSONArray) {
            val scale = scale(target.getDouble(5))
            val left = target.getDouble(1).toFloat(); val top = target.getDouble(2).toFloat()
            val width = target.getDouble(3).toFloat(); val height = target.getDouble(4).toFloat()
            val cssX = if (target.getString(0) == "icon") left + width * (0.25f + Random.nextFloat() * 0.5f)
                else left + (height * (0.35f + Random.nextFloat() * 0.3f)).coerceAtMost(width * 0.35f)
            val cssY = top + height * (0.25f + Random.nextFloat() * 0.5f)
            val x = cssX * scale; val y = cssY * scale
            val pressure = 0.5f + Random.nextFloat() * 0.4f
            val down = SystemClock.uptimeMillis()
            send(down, MotionEvent.ACTION_DOWN, x, y, pressure)
            later(70L + Random.nextLong(0, 90)) {
                send(down, MotionEvent.ACTION_MOVE, x + (Random.nextFloat() * 1.6f - 0.8f) * density, y + (Random.nextFloat() * 1.2f - 0.6f) * density, pressure)
            }
            later(150L + Random.nextLong(0, 170)) {
                send(down, MotionEvent.ACTION_UP, x + (Random.nextFloat() * 2f - 1f) * density, y + (Random.nextFloat() - 0.5f) * density, 0f)
            }
        }
        fun aim() {
            if (plus) {
                // WDTT Plus: after the page settles, up to 16 probes, then its watchdogs after the click.
                val now = SystemClock.uptimeMillis()
                if (settleAt == 0L) settleAt = now + CaptchaAutoScripts.wdttPlusSettleMs()
                if (now < settleAt) return
                web.evaluateJavascript(CaptchaAutoScripts.wdttPlusCheckbox) { raw ->
                    if (finished || aimed) return@evaluateJavascript
                    if (raw == "\"manual\"") { finish(slider); return@evaluateJavascript }
                    val target = runCatching { JSONArray(raw) }.getOrNull()?.takeIf { it.length() == 6 }
                    if (target == null) { if (++probes >= 16) finish("error:checkbox_not_found"); return@evaluateJavascript }
                    aimed = true
                    later(CaptchaAutoScripts.wdttPlusThinkMs()) {
                        touchPlus(target); clickedAt = SystemClock.uptimeMillis()
                        later(3_500) { web.evaluateJavascript("!!window.__qthroneCheckSent") { if (it != "true") finish("error:auto_check_not_sent") } }
                        later(8_000) { finish("error:auto_no_result") }
                    }
                }
            } else web.evaluateJavascript(CaptchaAutoScripts.checkbox) { raw ->
                if (finished || aimed) return@evaluateJavascript
                if (raw == "\"manual\"") { finish(slider); return@evaluateJavascript }
                val label = runCatching { JSONArray(raw) }.getOrNull()?.takeIf { it.length() == 5 } ?: return@evaluateJavascript
                aimed = true
                later(CaptchaAutoScripts.clickDelayMs()) { touch(label); clickedAt = SystemClock.uptimeMillis() }
            }
        }
        fun poll() {
            if (finished) return
            if (web.url?.let(CaptchaAddressPolicy::allowed) == true) {
                if (!aimed) aim()
                // As the originals: for about five seconds after the click a slider means a manual captcha.
                if (clickedAt > 0 && SystemClock.uptimeMillis() - clickedAt <= 5_400)
                    web.evaluateJavascript(if (plus) CaptchaAutoScripts.wdttPlusSliderAfterClick else CaptchaAutoScripts.sliderAfterClick) {
                        if (it == "true") finish(slider)
                    }
                web.evaluateJavascript(CaptchaAutoScripts.state) { raw ->
                    val state = runCatching { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                        ?: return@evaluateJavascript
                    val value = state.optString("token").takeIf { !state.isNull("token") && it.isNotEmpty() }
                    when {
                        value != null -> finish(if (value.length <= 16384 && value.none(Char::isISOControl)) value else "error:invalid token")
                        // WDTT Plus gives up on a BOT or ERROR verdict at once, as its hidden WebView does.
                        plus && state.optString("status").uppercase() in listOf("BOT", "ERROR_LIMIT", "ERROR") ->
                            finish("error:captcha_check_" + state.optString("status"))
                        state.optString("failure") == "slider" -> finish(slider)
                        state.optString("failure") == "error" -> finish("error:VK captcha error")
                    }
                }
            }
            later(400) { poll() }
        }
        try {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                // qWDTT shows its captcha WebViews as desktop Chrome; CSQTT and WDTT Plus keep the WebView's own agent.
                if (client == "qwdtt") userAgentString = CaptchaAutoScripts.desktopUserAgent(WebSettings.getDefaultUserAgent(app))
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                    !CaptchaAddressPolicy.allowed(request.url.toString())
                override fun onPageStarted(view: WebView, address: String, favicon: android.graphics.Bitmap?) {
                    if (CaptchaAddressPolicy.allowed(address)) view.evaluateJavascript(CaptchaAutoScripts.intercept, null)
                }
                override fun onPageFinished(view: WebView, address: String) {
                    if (CaptchaAddressPolicy.allowed(address)) view.evaluateJavascript(CaptchaAutoScripts.intercept, null)
                }
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, CaptchaAutoScripts.intercept,
                    setOf("https://vk.ru", "https://*.vk.ru", "https://vk.com", "https://*.vk.com", "https://ok.ru", "https://*.ok.ru", "https://*.okcdn.ru"))
            // The originals' randomized phone-sized viewport (356-368 x 376-388).
            val width = ((356 + 2 * Random.nextInt(7)) * density).toInt()
            val height = ((376 + 2 * Random.nextInt(7)) * density).toInt()
            web.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, width, height)
            web.onResume()
            later(NativeCaptchaPolicy.automaticTimeout(client)) { finish("error:timeout") }
            web.loadUrl(url)
            later(400) { poll() }
        } catch (error: Exception) {
            finish("error:" + (error.message ?: "WebView unavailable").replace('\n', ' ').replace('\r', ' '))
        }
    }
}
