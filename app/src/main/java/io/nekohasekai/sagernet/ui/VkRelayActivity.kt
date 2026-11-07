package app.rawline.mobile.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.*
import app.rawline.mobile.R
import app.rawline.mobile.bg.carrier.VkRelayAccount
import app.rawline.mobile.bg.carrier.VkRelayLogin
import app.rawline.mobile.database.DataStore
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class VkRelayActivity : ThemedActivity() {
    private var web: WebView? = null
    @SuppressLint("SetJavaScriptEnabled") override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Настройки VK-релея"; setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
        }
        val content = FrameLayout(this).apply { id = android.R.id.content + 2 }
        root.addView(toolbar); root.addView(content,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        if(intent.getBooleanExtra("login",false)) {
            val cookieDomains = listOf("vk.com","vk.ru","m.vk.com","m.vk.ru","login.vk.com","login.vk.ru","oauth.vk.com","oauth.vk.ru","id.vk.com","id.vk.ru")
            fun cookies(): Map<String,String> = cookieDomains.associate { host -> "https://$host" to CookieManager.getInstance().getCookie("https://$host").orEmpty() }
            var loginAttempt = 0
            var completedLogin = false
            var lastRetry = -1
            var retryingLogin = false
            fun applySettings(view: WebView) {
                view.settings.apply {
                    javaScriptEnabled = true;domStorageEnabled = true;databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false;loadWithOverviewMode = true;useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT;javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                    userAgentString = VkRelayLogin.userAgent(WebSettings.getDefaultUserAgent(this@VkRelayActivity),loginAttempt)
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(view,true)
            }
            fun retryLogin() {
                val view = web ?: return
                if(completedLogin || retryingLogin || lastRetry == loginAttempt) return
                lastRetry = loginAttempt
                if(loginAttempt >= 2) {
                    MobileAlertDialogBuilder(this).setTitle("Вход VK")
                        .setMessage("VK отклонил все три варианта входа. Попробуйте привязать аккаунт позже.")
                        .setPositiveButton(android.R.string.ok,null).show()
                    return
                }
                retryingLogin=true;loginAttempt++
                val manager=CookieManager.getInstance()
                manager.removeAllCookies {
                    if(isFinishing || isDestroyed) return@removeAllCookies
                    manager.flush();applySettings(view)
                    view.loadUrl(VkRelayLogin.startUrl(loginAttempt),VkRelayLogin.headers)
                }
            }
            web = WebView(this).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                applySettings(this)
                addJavascriptInterface(object {
                    @JavascriptInterface fun onLoginError() {
                        runOnUiThread {
                            val current=web?.url
                            if(current != null && runCatching { app.rawline.mobile.bg.carrier.VkRelayOAuth.trustedHttps(java.net.URI(current)) }.getOrDefault(false))
                                retryLogin()
                        }
                    }
                },"RawlineVkLogin")
                webChromeClient = object : WebChromeClient() {
                    override fun onJsAlert(view: WebView?,url: String?,message: String?,result: JsResult?): Boolean {
                        if(message.orEmpty().contains("Unknown method",ignoreCase=true)) {
                            result?.cancel();retryLogin();return true
                        }
                        return false
                    }
                    override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: android.os.Message): Boolean {
                        val popup=WebView(this@VkRelayActivity)
                        popup.webViewClient=object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(child: WebView,request: WebResourceRequest): Boolean {
                                val target=request.url.toString()
                                if(runCatching { app.rawline.mobile.bg.carrier.VkRelayOAuth.trustedHttps(java.net.URI(target)) }.getOrDefault(false))
                                    view.loadUrl(target,VkRelayLogin.headers)
                                child.destroy();return true
                            }
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView=popup;resultMsg.sendToTarget()
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView,url: String?) {
                        retryingLogin=false
                        if(url != null && runCatching { app.rawline.mobile.bg.carrier.VkRelayOAuth.trustedHttps(java.net.URI(url)) }.getOrDefault(false))
                            view.evaluateJavascript(VkRelayLogin.errorWatcher,null)
                        if(!completedLogin && !VkRelayLogin.awaitingLogin(url.orEmpty()) && cookies().values.any { value -> value.split(';').any { it.trim().startsWith("remixsid=") && it.substringAfter('=').trim().length >= 8 } }) {
                            completedLogin=true
                            CookieManager.getInstance().flush()
                            val sessionCookies=cookies()
                            val userAgent=view.settings.userAgentString
                            lifecycleScope.launch {
                                val result=withContext(Dispatchers.IO) { runCatching { VkRelayAccount.linkSession(sessionCookies,userAgent) } }
                                if(result.isSuccess) { Toast.makeText(this@VkRelayActivity,"Аккаунт VK привязан",Toast.LENGTH_SHORT).show();finish() }
                                else { completedLogin=false;Toast.makeText(this@VkRelayActivity,"Не удалось сохранить сессию VK",Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if(uri.scheme != "https") return true
                        if(uri.host in listOf("oauth.vk.ru","oauth.vk.com") && uri.path == "/blank.html") {
                            return true
                        }
                        val host = uri.host.orEmpty()
                        return !(host == "vk.ru" || host.endsWith(".vk.ru") || host == "vk.com" || host.endsWith(".vk.com"))
                    }
                }
                loadUrl(VkRelayLogin.startUrl(loginAttempt),VkRelayLogin.headers)
            }
            content.addView(web,FrameLayout.LayoutParams(-1,-1))
        } else if(state == null) supportFragmentManager.beginTransaction().replace(content.id,Screen()).commit()
    }
    override fun onDestroy() { web?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }; web = null; super.onDestroy() }
    class Screen : PreferenceFragmentCompat() {
        override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? =
            MobileScreenMotion.create(this, nextAnim)
        private var firstResume=true
        private lateinit var accountGroup: PreferenceCategory
        override fun onCreatePreferences(state: Bundle?,rootKey: String?) { preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()); build() }
        override fun onResume() { super.onResume();if(firstResume) firstResume=false else VkRelayPreferences.bind(this,accountGroup) }
        override fun onViewCreated(view: android.view.View,state: Bundle?) {
            super.onViewCreated(view,state);MobileSettingsStyle.apply(this,view)
        }
        private fun build() {
            preferenceScreen.removeAll()
            val account = PreferenceCategory(requireContext()).apply { title = "Аккаунт и хеши" }
            accountGroup=account
            preferenceScreen.addPreference(account);VkRelayPreferences.bind(this,account)
            val deploy = PreferenceCategory(requireContext()).apply { title = "Управление серверами" }
            preferenceScreen.addPreference(deploy)
            for(kind in listOf("mrtt","qwdtt","csqtt","wdttplus","freeturn")) deploy.addPreference(Preference(requireContext()).apply {
                title = "Деплой " + DeployActivity.title(kind)
                setIcon(R.drawable.ic_mobile_core);widgetLayoutResource=R.layout.qmobile_preference_arrow
                fragment=DeployListFragment::class.java.name
                extras.putString("kind",kind)
                setOnPreferenceClickListener {
                    if(parentFragment is SettingsFragment) false else {
                        startActivity(android.content.Intent(requireContext(),DeployActivity::class.java).putExtra("kind",kind));true
                    }
                }
            })
        }
    }
}
