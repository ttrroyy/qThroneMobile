package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.qthrone.mobile.ui.MobileSheetDialog as BottomSheetDialog

/** The captcha remains on its real HTTPS address in a bottom sheet. */
class MobileCaptchaController(private val host: FragmentActivity): AutoCloseable {
    private var dialog: BottomSheetDialog?=null
    private var currentId: String?=null
    private var cleanupWeb: (() -> Unit)?=null
    private var showOnResume=false
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val id=intent.getStringExtra("id") ?: return
            if(intent.action==host.packageName+".CAPTCHA_CLOSE") {
                if(currentId==id) { val sheet=dialog;currentId=null;cleanupWeb?.invoke();sheet?.dismiss() }
            } else {
                // Only the visible activity owns the WebView; other hosts replay on resume.
                if(!host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                if(currentId==id) {
                    if(showOnResume && host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) dialog?.show()
                    return
                }
                val url=intent.getStringExtra("url") ?: return
                val tokenState=intent.getStringExtra("tokenState")
                if(tokenState!=null) {
                    if(!allowed(url)) { reply(id,"error:invalid token address");return }
                    show(id,url,oauthState=tokenState);return
                }
                if(intent.getBooleanExtra("turn",false)) {
                    if(!url.matches(Regex("[A-Za-z0-9_-]{16,512}"))) { reply(id,"error:invalid call hash");return }
                    show(id,url,true);return
                }
                val client=intent.getStringExtra("client") ?: "qwdtt"
                if(client=="openflux") {
                    if(!CaptchaAddressPolicy.allowedOpenFlux(url)) { reply(id,"error:invalid captcha address");return }
                    showOpenFlux(id,url);return
                }
                if(!allowed(url)) { reply(id,"error:invalid captcha address");return }
                show(id,url,automatic=intent.getBooleanExtra("automatic",false),client=client)
            }
        }
    }
    private val lifecycleObserver=androidx.lifecycle.LifecycleEventObserver { _,event ->
        if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) host.lifecycleScope.launch {
            val pending=withContext(Dispatchers.IO) { app.qthrone.mobile.bg.carrier.CarrierUiRequests.pending() }
            if(pending!=null && host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                receiver.onReceive(host,pending)
        }
    }
    init {
        val filter=IntentFilter(host.packageName+".CAPTCHA_REQUEST").apply { addAction(host.packageName+".CAPTCHA_CLOSE") }
        ContextCompat.registerReceiver(host,receiver,filter,host.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        host.lifecycle.addObserver(lifecycleObserver)
    }
    private fun allowed(value: String)=CaptchaAddressPolicy.allowed(value)
    private fun reply(id: String,result: String) {
        host.sendBroadcast(Intent(host.packageName+".CAPTCHA_RESULT").setPackage(host.packageName).putExtra("id",id).putExtra("result",result),host.packageName+".SERVICE")
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun show(id: String,url: String,turn: Boolean=false,oauthState: String?=null,automatic: Boolean=false,client: String="qwdtt") {
        val plus=client=="wdttplus"
        if(app.qthrone.mobile.bg.carrier.CarrierUiRequests.pending()?.getStringExtra("id")!=id) return
        val previousSheet=dialog
        cleanupWeb?.invoke()
        previousSheet?.dismiss()
        currentId=id
        showOnResume=false
        val sheet=BottomSheetDialog(host)
        dialog=sheet
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.surface) }
        val header=LinearLayout(host).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        header.addView(TextView(host).apply { text=if(turn || oauthState!=null) "VK · Авторизация" else "VK · Captcha";textSize=19f;setTextColor(MobilePalette.text);setPadding(24,20,24,20) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(android.widget.ImageButton(host).apply {
            setImageResource(app.qthrone.mobile.R.drawable.ic_navigation_close)
            imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(host))
            background=null;contentDescription="Закрыть капчу"
            setOnClickListener { sheet.dismiss() }
        },LinearLayout.LayoutParams((48*host.resources.displayMetrics.density).toInt(),(48*host.resources.displayMetrics.density).toInt()))
        body.addView(header)
        var turnUrlIndex=0
        var autoClicked=false
        var clickedAt=0L
        var settleAt=0L
        var probes=0
        val fail: (String) -> Unit = { result -> if(currentId==id) { reply(id,result);currentId=null;cleanupWeb?.invoke();sheet.dismiss() } }
        val web=WebView(host).apply {
            setBackgroundColor(MobilePalette.surface)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.mediaPlaybackRequiresUserGesture=false
            settings.loadWithOverviewMode=true
            settings.useWideViewPort=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if(automatic) settings.cacheMode=android.webkit.WebSettings.LOAD_NO_CACHE
            // The original qWDTT app shows both its captcha WebViews as desktop Chrome; CSQTT and WDTT Plus keep the WebView's own agent.
            if(!turn && oauthState==null && client=="qwdtt")
                settings.userAgentString=CaptchaAutoScripts.desktopUserAgent(android.webkit.WebSettings.getDefaultUserAgent(host))
            webChromeClient=android.webkit.WebChromeClient()
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            if(turn || oauthState!=null) {
                settings.userAgentString=app.qthrone.mobile.bg.carrier.VkRelayAccount.sessionUserAgent().ifBlank {
                    app.qthrone.mobile.bg.carrier.VkRelayLogin.userAgent(android.webkit.WebSettings.getDefaultUserAgent(host),0)
                }
            }
            val hook=if(turn) VkTurnScripts.INTERCEPTOR else CaptchaAutoScripts.intercept
            if(!turn && oauthState==null && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER))
                androidx.webkit.WebViewCompat.addWebMessageListener(this,"QThroneCaptcha",
                    setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com","https://ok.ru","https://*.ok.ru","https://*.okcdn.ru")) { _,message,origin,_,_ ->
                    val token=if(allowed(origin.toString())) runCatching { message.data }.getOrNull() else null
                    if(currentId==id && !token.isNullOrEmpty() && token.length<=16384 && token.none { it=='\n' || it=='\r' }) {
                        reply(id,token);currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    }
                }
            if(turn && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER))
                androidx.webkit.WebViewCompat.addWebMessageListener(this,"QThroneVkTurn",
                    setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com")) { _,message,origin,_,_ ->
                    val trusted=runCatching { app.qthrone.mobile.bg.carrier.VkRelayOAuth.trustedHttps(java.net.URI(origin.toString())) }.getOrDefault(false)
                    val payload=if(trusted) runCatching { message.data }.getOrNull()?.let { VkTurnPayload.encode(url,it) } else null
                    if(payload!=null && currentId==id) {
                        reply(id,payload);currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    }
                }
            if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,hook,setOf("https://vk.ru","https://*.vk.ru","https://vk.com","https://*.vk.com","https://ok.ru","https://*.ok.ru","https://*.okcdn.ru"))
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    if(oauthState!=null && deliverToken(request.url.toString())) return true
                    return !allowed(request.url.toString())
                }
                private fun deliverToken(address: String): Boolean {
                    val state=oauthState ?: return false
                    val token=app.qthrone.mobile.bg.carrier.VkRelayOAuth.tokenFields(address,state) ?: return false
                    if(currentId==id) {
                        reply(id,org.json.JSONObject().put("token",token.first).put("expires",token.second).toString())
                        currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    }
                    return true
                }
                override fun onPageStarted(view: WebView,address: String,favicon: android.graphics.Bitmap?) {
                    if(allowed(address)) {
                        if(turn) view.evaluateJavascript(VkTurnScripts.AUTO_JOIN_RESET,null)
                        view.evaluateJavascript(hook,null)
                    }
                }
                override fun onPageFinished(view: WebView,address: String) {
                    if(oauthState!=null && deliverToken(address)) return
                    if(allowed(address)) {
                        view.evaluateJavascript(hook,null)
                        if(turn) {
                            view.evaluateJavascript(VkTurnScripts.AUTO_JOIN_SETUP,null)
                            view.evaluateJavascript(VkTurnScripts.PAGE_404_CHECK) { value ->
                                if(value=="\"404\"" && currentId==id && turnUrlIndex<VkTurnScripts.joinUrlCandidates(url).lastIndex) {
                                    turnUrlIndex++
                                    view.loadUrl(VkTurnScripts.joinUrlCandidates(url)[turnUrlIndex])
                                }
                            }
                        }
                    }
                }
            }
        }
        body.addView(web,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.65f).toInt()))
        val polling=object: Runnable {
            override fun run() {
                if(currentId!=id) return
                val onCaptcha=web.url?.let(::allowed)==true
                // WDTT Plus: after the page settles, up to 16 probes 350 ms apart, then its watchdogs after the click.
                if(plus && automatic && onCaptcha && !autoClicked) {
                    val now=android.os.SystemClock.uptimeMillis()
                    if(settleAt==0L) settleAt=now+CaptchaAutoScripts.wdttPlusSettleMs()
                    if(now>=settleAt) web.evaluateJavascript(CaptchaAutoScripts.wdttPlusCheckbox) { raw ->
                        if(currentId!=id || autoClicked) return@evaluateJavascript
                        if(raw=="\"manual\"") { fail("error:slider_detected");return@evaluateJavascript }
                        val target=runCatching { org.json.JSONArray(raw) }.getOrNull()?.takeIf { it.length()==6 }
                        if(target==null) { if(++probes>=16) fail("error:checkbox_not_found");return@evaluateJavascript }
                        autoClicked=true
                        web.postDelayed({ if(currentId==id) {
                            touchWdttPlus(web,target);clickedAt=android.os.SystemClock.uptimeMillis()
                            web.postDelayed({ if(currentId==id) web.evaluateJavascript("!!window.__qthroneCheckSent") { sent -> if(sent!="true") fail("error:auto_check_not_sent") } },3_500)
                            web.postDelayed({ fail("error:auto_no_result") },8_000)
                        } },CaptchaAutoScripts.wdttPlusThinkMs())
                    }
                }
                if(!plus && automatic && onCaptcha && !autoClicked) {
                    web.evaluateJavascript(CaptchaAutoScripts.checkbox) { raw ->
                        if(currentId!=id || autoClicked) return@evaluateJavascript
                        if(raw=="\"manual\"") fail("error:slider detected")
                        else runCatching { org.json.JSONArray(raw) }.getOrNull()?.takeIf { it.length()==5 }?.let { label ->
                            autoClicked=true
                            web.postDelayed({ if(currentId==id) { touch(web,label);clickedAt=android.os.SystemClock.uptimeMillis() } },CaptchaAutoScripts.clickDelayMs())
                        }
                    }
                }
                // As the original: for about five seconds after the click a slider means a manual captcha.
                if(automatic && onCaptcha && clickedAt>0 && android.os.SystemClock.uptimeMillis()-clickedAt<=5_400)
                    web.evaluateJavascript(if(plus) CaptchaAutoScripts.wdttPlusSliderAfterClick else CaptchaAutoScripts.sliderAfterClick) { raw ->
                        if(raw=="true") fail(if(plus) "error:slider_detected" else "error:slider detected") }
                if(turn && onCaptcha) web.evaluateJavascript(VkTurnScripts.AUTO_JOIN_TRY,null)
                if(turn && onCaptcha) web.evaluateJavascript("window.__qthroneTurnServer || null") { raw ->
                    val token=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                    val result=token?.let { VkTurnPayload.encode(url,it) }
                    if(!result.isNullOrEmpty() && currentId==id) { reply(id,result);currentId=null;cleanupWeb?.invoke();sheet.dismiss() }
                }
                if(!turn && oauthState==null && onCaptcha) web.evaluateJavascript(CaptchaAutoScripts.state) { raw ->
                    if(currentId!=id) return@evaluateJavascript
                    val state=runCatching { org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull() ?: return@evaluateJavascript
                    val token=state.optString("token").takeIf { !state.isNull("token") && it.isNotEmpty() }
                    when {
                        token!=null -> if(token.length<=16384 && token.none(Char::isISOControl)) { reply(id,token);currentId=null;cleanupWeb?.invoke();sheet.dismiss() }
                        // WDTT Plus gives up on a BOT or ERROR verdict at once, as its hidden WebView does.
                        plus && automatic && state.optString("status").uppercase() in listOf("BOT","ERROR_LIMIT","ERROR") -> fail("error:captcha_check_"+state.optString("status"))
                        state.optString("failure")=="slider" && automatic -> fail(if(plus) "error:slider_detected" else "error:slider detected")
                        state.optString("failure")=="error" -> fail("error:VK captcha error")
                        // VK's own cross: the user declined, as the original's "cancel and stop".
                        state.optBoolean("dismissed") && !automatic -> sheet.dismiss()
                    }
                }
                web.postDelayed(this,400)
            }
        }
        val fallback=Runnable {
            if(currentId==id && !host.isFinishing) {
                if(automatic) {
                    reply(id,"error:timeout");currentId=null;cleanupWeb?.invoke();sheet.dismiss()
                    return@Runnable
                }
                showOnResume=true
                if(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) sheet.show()
            }
        }
        // Like the original manual WebView: after a minute the client no longer waits for this answer.
        val expiry=android.os.Handler(android.os.Looper.getMainLooper())
        val expire=Runnable {
            if(currentId==id) { reply(id,"error:timeout");currentId=null;cleanupWeb?.invoke();sheet.dismiss() }
        }
        var cleaned=false
        val cleanup: () -> Unit = {
          if(!cleaned) {
            cleaned=true
            expiry.removeCallbacks(expire)
            web.removeCallbacks(polling)
            web.removeCallbacks(fallback)
            if(currentId==id) { reply(id,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.dismissal(automatic));currentId=null }
            if(automatic) (body.parent as? android.view.ViewGroup)?.removeView(body)
            web.stopLoading();web.destroy()
            if(dialog===sheet) dialog=null
            cleanupWeb=null
          }
        }
        cleanupWeb=cleanup
        sheet.setOnDismissListener { cleanup() }
        // In the original only an explicit close declines a visible captcha; back or a swipe does not.
        if(!automatic && !turn && oauthState==null) sheet.setCancelable(false)
        sheet.setContentView(body)
        // qWDTT first tries an invisible join for 22 seconds, then opens its WebView.
        if(automatic) {
            // A measured, attached WebView can execute VK's automatic challenge without
            // taking focus, receiving touches, or displaying a captcha sheet.
            (body.parent as? android.view.ViewGroup)?.removeView(body)
            body.alpha=0f
            body.translationX=-2f*host.resources.displayMetrics.widthPixels
            body.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (host.window.decorView as android.view.ViewGroup).addView(body,
                android.view.ViewGroup.LayoutParams(host.resources.displayMetrics.widthPixels,
                    (host.resources.displayMetrics.heightPixels*.65f).toInt()))
            web.postDelayed(fallback,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.automaticTimeout(client))
        } else if(turn) web.postDelayed(fallback,22_000) else {
            if(oauthState==null) expiry.postDelayed(expire,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.visibleTimeout(client))
            showOnResume=true
            if(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) sheet.show()
        }
        val startUrl=if(turn) VkTurnScripts.joinUrlCandidates(url).first() else url
        web.onResume()
        val cookiePairs=if(turn || oauthState!=null) app.qthrone.mobile.bg.carrier.VkRelayAccount.sessionCookies().filterKeys(::allowed)
            .flatMap { (address,header) -> header.split(';').map { address to it.trim() }.filter { it.second.isNotEmpty() } } else emptyList()
        // Original qWDTT clears VK's captcha cookies before every visible captcha (CSQTT does not); the account binding keeps its own.
        val stale=if(!automatic && !turn && oauthState==null && client=="qwdtt") captchaCookieHosts.flatMap { address ->
            android.webkit.CookieManager.getInstance().getCookie(address).orEmpty().split(';')
                .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.map { address to "$it=; Max-Age=0; Path=/" }
        } else emptyList()
        if(stale.isNotEmpty()) {
            val pending=java.util.concurrent.atomic.AtomicInteger(stale.size)
            for((address,cookie) in stale) android.webkit.CookieManager.getInstance().setCookie(address,cookie) {
                if(pending.decrementAndGet()==0 && currentId==id && !cleaned) { android.webkit.CookieManager.getInstance().flush();web.loadUrl(startUrl) }
            }
        } else if(cookiePairs.isEmpty()) web.loadUrl(startUrl) else {
            val pending=java.util.concurrent.atomic.AtomicInteger(cookiePairs.size)
            for((address,cookie) in cookiePairs) android.webkit.CookieManager.getInstance().setCookie(address,cookie) {
                if(pending.decrementAndGet()==0 && currentId==id && !cleaned) {
                    android.webkit.CookieManager.getInstance().flush()
                    web.loadUrl(startUrl)
                }
            }
        }
        web.postDelayed(polling,400)
    }
    /**
     * OpenFlux's provider check, as the original OpenFlux apps show it (CaptchaDialog/CaptchaBrowser): the page in
     * one sheet with "Готово, проверка пройдена" and "Позже". The page's cookies go back once the user confirms, or
     * on their own once the page has settled for 1.5 s on the document behind the check (a real browser is often
     * let through without one). One sheet at a time, like the VK captcha: a new request replaces the old one.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showOpenFlux(id: String,url: String) {
        if(app.qthrone.mobile.bg.carrier.CarrierUiRequests.pending()?.getStringExtra("id")!=id) return
        val previousSheet=dialog
        cleanupWeb?.invoke()
        previousSheet?.dismiss()
        currentId=id
        showOnResume=true
        val ui=ServerUi(host)
        val sheet=BottomSheetDialog(host)
        dialog=sheet
        val body=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.surface) }
        body.addView(TextView(host).apply { text="Яндекс просит пройти проверку";textSize=19f;setTextColor(MobilePalette.text);setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(4)) })
        body.addView(TextView(host).apply {
            text="Пройдите проверку, затем нажмите «Готово». Если страница откроется без проверки, OpenFlux передаст результат сам."
            textSize=13f;setTextColor(MobilePalette.muted);setPadding(ui.dp(16),0,ui.dp(16),ui.dp(10))
        })
        var cleaned=false
        var settledSince=0L
        val web=WebView(host).apply {
            setBackgroundColor(MobilePalette.surface)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.loadWithOverviewMode=true
            settings.useWideViewPort=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient=android.webkit.WebChromeClient()
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webViewClient=object: WebViewClient() {
                // The check may pass through Yandex's own pages; nothing else opens here.
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)=
                    !CaptchaAddressPolicy.allowedOpenFlux(request.url.toString())
            }
        }
        body.addView(web,LinearLayout.LayoutParams(-1,(host.resources.displayMetrics.heightPixels*.6f).toInt()))
        fun cookies(): String {
            val manager=android.webkit.CookieManager.getInstance()
            val merged=linkedMapOf<String,String>()
            for(address in listOfNotNull(url,web.url).filter(CaptchaAddressPolicy::allowedOpenFlux).distinct())
                manager.getCookie(address).orEmpty().split(';').map { it.trim() }.filter { '=' in it }
                    .forEach { merged[it.substringBefore('=')]=it.substringAfter('=') }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        fun submit() {
            if(currentId!=id) return
            val header=cookies()
            if(header.isEmpty() || header.length>16384 || header.any { it=='\n' || it=='\r' }) {
                android.widget.Toast.makeText(host,"Проверка ещё не пройдена",android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            reply(id,header);currentId=null;cleanupWeb?.invoke();sheet.dismiss()
        }
        val buttons=LinearLayout(host).apply { setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(16)) }
        buttons.addView(ui.button("Позже") { sheet.dismiss() },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=ui.dp(8) })
        buttons.addView(ui.button("Готово, проверка пройдена",primary=true) { submit() },LinearLayout.LayoutParams(0,-2,2f))
        body.addView(buttons)
        val polling=object: Runnable {
            override fun run() {
                if(currentId!=id || cleaned) return
                val address=web.url.orEmpty()
                val settled=web.progress==100 && CaptchaAddressPolicy.allowedOpenFlux(address) && !CaptchaAddressPolicy.openFluxCheckpoint(address)
                val now=android.os.SystemClock.uptimeMillis()
                if(!settled) settledSince=0L
                else if(settledSince==0L) settledSince=now
                else if(now-settledSince>=1_500L && cookies().isNotEmpty()) { submit();return }
                web.postDelayed(this,300)
            }
        }
        val expiry=android.os.Handler(android.os.Looper.getMainLooper())
        val expire=Runnable { if(currentId==id) { reply(id,"error:timeout");currentId=null;cleanupWeb?.invoke();sheet.dismiss() } }
        val cleanup: () -> Unit = {
            if(!cleaned) {
                cleaned=true
                expiry.removeCallbacks(expire)
                web.removeCallbacks(polling)
                // "Позже" or the sheet's own dismissal declines; the client stays quiet for a while.
                if(currentId==id) { reply(id,"error:cancelled");currentId=null }
                web.stopLoading();web.destroy()
                if(dialog===sheet) dialog=null
                cleanupWeb=null
            }
        }
        cleanupWeb=cleanup
        sheet.setOnDismissListener { cleanup() }
        sheet.setCancelable(false)
        sheet.setContentView(body)
        expiry.postDelayed(expire,app.qthrone.mobile.bg.carrier.NativeCaptchaPolicy.visibleTimeout("openflux"))
        if(host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) sheet.show()
        web.onResume()
        web.loadUrl(url)
        web.postDelayed(polling,300)
    }
    override fun close() { val sheet=dialog;cleanupWeb?.invoke();sheet?.dismiss();host.lifecycle.removeObserver(lifecycleObserver);runCatching { host.unregisterReceiver(receiver) } }
    /** The original's touch: a random point in the label, pressure 0.5-0.9, held 80-180 ms, released with a slight drift. */
    private fun touch(web: WebView,label: org.json.JSONArray) {
        val random=kotlin.random.Random
        val scale=if(web.width>0) web.width/label.getDouble(4).toFloat().coerceAtLeast(1f) else host.resources.displayMetrics.density
        val x=(label.getDouble(0)+label.getDouble(2)*(0.15+random.nextDouble()*0.7)).toFloat()*scale
        val y=(label.getDouble(1)+label.getDouble(3)*(0.25+random.nextDouble()*0.5)).toFloat()*scale
        val density=host.resources.displayMetrics.density
        val down=android.os.SystemClock.uptimeMillis()
        android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,x,y,0.5f+random.nextFloat()*0.4f,1f,0,1f,1f,0,0).also {
            it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;web.dispatchTouchEvent(it);it.recycle()
        }
        web.postDelayed({
            android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,
                x+(random.nextFloat()*2f-1f)*density,y+(random.nextFloat()-0.5f)*density,0f,1f,0,1f,1f,0,0).also {
                it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;web.dispatchTouchEvent(it);it.recycle()
            }
        },80L+random.nextLong(0,100))
    }
    /** WDTT Plus's touch: the checkbox square, a small move, held 150-320 ms. */
    private fun touchWdttPlus(web: WebView,target: org.json.JSONArray) {
        val random=kotlin.random.Random
        val scale=if(web.width>0) web.width/target.getDouble(5).toFloat().coerceAtLeast(1f) else host.resources.displayMetrics.density
        val left=target.getDouble(1).toFloat();val top=target.getDouble(2).toFloat()
        val width=target.getDouble(3).toFloat();val height=target.getDouble(4).toFloat()
        val cssX=if(target.getString(0)=="icon") left+width*(0.25f+random.nextFloat()*0.5f)
            else left+(height*(0.35f+random.nextFloat()*0.3f)).coerceAtMost(width*0.35f)
        val cssY=top+height*(0.25f+random.nextFloat()*0.5f)
        val x=cssX*scale;val y=cssY*scale;val density=host.resources.displayMetrics.density
        val pressure=0.5f+random.nextFloat()*0.4f
        val down=android.os.SystemClock.uptimeMillis()
        fun send(action:Int,px:Float,py:Float,p:Float) = android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,px,py,p,1f,0,1f,1f,0,0).also {
            it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;web.dispatchTouchEvent(it);it.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN,x,y,pressure)
        web.postDelayed({ send(android.view.MotionEvent.ACTION_MOVE,x+(random.nextFloat()*1.6f-0.8f)*density,y+(random.nextFloat()*1.2f-0.6f)*density,pressure) },70L+random.nextLong(0,90))
        web.postDelayed({ send(android.view.MotionEvent.ACTION_UP,x+(random.nextFloat()*2f-1f)*density,y+(random.nextFloat()-0.5f)*density,0f) },150L+random.nextLong(0,170))
    }
    private companion object {
        val captchaCookieHosts=listOf("https://id.vk.ru","https://id.vk.com","https://vk.ru","https://vk.com")
        // Capture only the captcha API's success field, without exposing a Java interface to page frames.
        val INTERCEPT="""(() => {
          if(window.__qthroneCaptchaHook) return; window.__qthroneCaptchaHook=true;
          const receive=(text)=>{try {const result=JSON.parse(text);const token=result.response?.success_token;if(typeof token==='string') window.__qthroneCaptchaToken=token;}catch(e){}};
          const fetchOriginal=window.fetch;
          window.fetch=async function(...args){const response=await fetchOriginal.apply(this,args);if(response.url.includes('captchaNotRobot.check')) response.clone().text().then(receive);return response;};
          const sendOriginal=XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send=function(...args){this.addEventListener('load',()=>{if(this.responseURL.includes('captchaNotRobot.check') && (!this.responseType || this.responseType==='text')) receive(this.responseText);});return sendOriginal.apply(this,args);};
        })();"""
    }
}
