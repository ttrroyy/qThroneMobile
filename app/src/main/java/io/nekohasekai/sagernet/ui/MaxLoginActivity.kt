package app.rawline.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.bg.carrier.MaxAccount
import app.rawline.mobile.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The MAX binding, as the original MRTT app does it (MaxAuthDialog, MaxAuthSession): the user signs in to web.max.ru
 * and opens a group; only the token and the group's ID are taken. The page is refused every device permission,
 * files and other sites: it reaches nothing of the phone.
 */
class MaxLoginActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var error: TextView
    private lateinit var retry: TextView
    private lateinit var done: TextView
    private val ui by lazy { ServerUi(this) }
    private var required = ""
    private var finished = false
    private var paused = false
    private var tokenDetected = false
    private var targetOpened = false
    private var uiReady = false
    private var startedAt = 0L
    private var pageStartedAt = 0L
    private var pausedAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        required = intent.getStringExtra("group").orEmpty().takeIf(MaxAccount::validGroup).orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Привязка MAX"; setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
        }
        status = ui.text("Ожидание входа. Войдите в свой аккаунт MAX.",13f,MobilePalette.muted).apply { setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8)) }
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(MobilePalette.screen) < .5
        web = WebView(this).apply {
            setBackgroundColor(MobilePalette.screen)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false; allowContentAccess = false; setSupportMultipleWindows(false)
                useWideViewPort = true; loadWithOverviewMode = true; setSupportZoom(false); builtInZoomControls = false
                setGeolocationEnabled(false)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView,url: String?,favicon: Bitmap?) {
                    if(finished) return
                    pageStartedAt = SystemClock.elapsedRealtime(); uiReady = false; showLoading(true)
                }
                override fun onPageFinished(view: WebView,url: String) {
                    if(finished) return
                    if(MaxAccount.isMaxUrl(url)) view.evaluateJavascript(themeScript(dark),null)
                    readPage()
                }
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest) = !allowed(request.url)
                override fun onReceivedError(view: WebView,request: WebResourceRequest,failure: WebResourceError) {
                    if(request.isForMainFrame || request.url.path.orEmpty().endsWith(".js")) fail("Не удалось загрузить MAX. Проверьте подключение к интернету.")
                }
                override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,response: WebResourceResponse) {
                    if(request.isForMainFrame || request.url.path.orEmpty().endsWith(".js")) fail("MAX не смог загрузить страницу входа. Попробуйте ещё раз.")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?,callback: GeolocationPermissions.Callback?) { callback?.invoke(origin,false,false) }
            }
        }
        spinner = ProgressBar(this)
        error = ui.text("",13f,android.graphics.Color.rgb(232,120,112)).apply { visibility = View.GONE; setPadding(ui.dp(16),ui.dp(6),ui.dp(16),ui.dp(6)) }
        retry = ui.button("Повторить") { retryLogin() }.apply { visibility = View.GONE }
        done = ui.button("Готово",primary = true) { setResult(RESULT_OK); finish() }.apply { visibility = View.GONE }
        val page = FrameLayout(this).apply {
            addView(web,FrameLayout.LayoutParams(-1,-1))
            addView(spinner,FrameLayout.LayoutParams(-2,-2,android.view.Gravity.CENTER))
        }
        root.addView(toolbar)
        root.addView(status)
        root.addView(page,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(error)
        root.addView(retry,LinearLayout.LayoutParams(-1,-2).apply { setMargins(ui.dp(16),0,ui.dp(16),ui.dp(8)) })
        root.addView(done,LinearLayout.LayoutParams(-1,-2).apply { setMargins(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(16)) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        startedAt = SystemClock.elapsedRealtime(); pageStartedAt = startedAt
        web.loadUrl(BASE)
        schedule()
    }
    private fun showLoading(loading: Boolean) { spinner.visibility = if(loading) View.VISIBLE else View.GONE }
    private fun allowed(uri: android.net.Uri): Boolean {
        if(uri.toString()=="about:blank") return true
        if(!uri.scheme.equals("https",ignoreCase=true)) return false
        val host = uri.host?.lowercase() ?: return false
        return MaxAccount.isMaxUrl(uri.toString()) || host=="vk.com" || host.endsWith(".vk.com") || host=="vk.ru" || host.endsWith(".vk.ru")
    }
    private val tick = object : Runnable {
        override fun run() {
            if(finished || paused) return
            val now = SystemClock.elapsedRealtime()
            if(!uiReady && now-pageStartedAt>PAGE_TIMEOUT) { fail("Форма входа MAX не загрузилась. Попробуйте ещё раз."); return }
            if(!tokenDetected && now-startedAt>TOKEN_TIMEOUT) { fail("Не удалось дождаться входа в MAX. Попробуйте ещё раз."); return }
            readPage(); schedule()
        }
    }
    private fun schedule() {
        handler.removeCallbacks(tick)
        if(!finished && !paused) handler.postDelayed(tick,MONITOR)
    }
    private fun readPage() {
        if(finished || paused) return
        val url = web.url.orEmpty()
        if(!MaxAccount.isMaxUrl(url)) return
        web.evaluateJavascript(READ_PAGE) { result ->
            if(finished || paused) return@evaluateJavascript
            val snapshot = runCatching { JSONObject(result.orEmpty()) }.getOrNull() ?: return@evaluateJavascript
            if(!snapshot.optBoolean("ready")) return@evaluateJavascript
            if(!uiReady) { uiReady = true; showLoading(false) }
            val current = snapshot.optString("url")
            val token = snapshot.opt("token") as? String ?: return@evaluateJavascript
            if(!MaxAccount.isMaxUrl(current) || !MaxAccount.validToken(token)) return@evaluateJavascript
            if(!tokenDetected) {
                tokenDetected = true
                status.text = if(required.isNotEmpty()) "Токен получен. Открываем группу из ссылки подключения…" else "Токен получен. Откройте группу для привязки."
            }
            val group = MaxAccount.groupIdFromUrl(current)
            if(required.isNotEmpty() && group!=required && !targetOpened) {
                targetOpened = true; web.loadUrl("$BASE/$required"); return@evaluateJavascript
            }
            if(group==null || (required.isNotEmpty() && group!=required)) return@evaluateJavascript
            finished = true
            handler.removeCallbacksAndMessages(null)
            web.onPause()
            status.text = "Токен получен. Группа найдена — сохраняем привязку…"
            showLoading(true)
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { MaxAccount.save(MaxAccount.Binding(token,group)) } }
                showLoading(false)
                if(saved.isSuccess) {
                    CookieManager.getInstance().flush()
                    status.text = "Токен и группа привязаны. MAX готов к подключению."
                    status.setTextColor(MobilePalette.accent(this@MaxLoginActivity))
                    done.visibility = View.VISIBLE
                } else fail("Не удалось сохранить привязку. Попробуйте ещё раз.",force = true)
            }
        }
    }
    private fun fail(message: String,force: Boolean = false) {
        if(finished && !force) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        showLoading(false)
        error.text = message; error.visibility = View.VISIBLE; retry.visibility = View.VISIBLE
    }
    private fun retryLogin() {
        finished = false; tokenDetected = false; targetOpened = false; uiReady = false
        error.visibility = View.GONE; retry.visibility = View.GONE
        status.text = "Ожидание входа. Войдите в свой аккаунт MAX."
        startedAt = SystemClock.elapsedRealtime(); pageStartedAt = startedAt
        web.onResume(); web.loadUrl(BASE); schedule()
    }
    override fun onStop() {
        super.onStop()
        if(finished || paused) return
        paused = true; pausedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick); web.onPause()
    }
    override fun onStart() {
        super.onStart()
        if(finished || !paused) return
        val pause = (SystemClock.elapsedRealtime()-pausedAt).coerceAtLeast(0L)
        startedAt += pause; pageStartedAt += pause; paused = false
        web.onResume(); schedule()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Closing the window keeps MAX's cookies and storage for the next login.
        runCatching { CookieManager.getInstance().flush() }
        web.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); destroy() }
        super.onDestroy()
    }
    private fun themeScript(dark: Boolean) = """(function() { var scheme = '${if(dark) "dark" else "light"}'; try {
        var theme = JSON.parse(localStorage.getItem('__oneme_theme') || '{}');
        if (theme.colorScheme !== scheme) { theme.colorScheme = scheme; localStorage.setItem('__oneme_theme', JSON.stringify(theme)); }
    } catch (e) {}
    window.__rawlineScheme = scheme;
    function apply() { var root = document.documentElement; if (root.dataset.colorScheme !== window.__rawlineScheme) root.dataset.colorScheme = window.__rawlineScheme; root.style.colorScheme = window.__rawlineScheme; }
    apply();
    if (!window.__rawlineObserver) { window.__rawlineObserver = new MutationObserver(apply); window.__rawlineObserver.observe(document.documentElement, {attributes: true, attributeFilter: ['data-color-scheme']}); }
    })();"""
    companion object {
        private const val BASE = "https://web.max.ru"
        private const val MONITOR = 1500L
        private const val PAGE_TIMEOUT = 45_000L
        private const val TOKEN_TIMEOUT = 12L*60L*1000L
        private const val RISK_KEY = "maxRiskAcknowledged"
        private const val READ_PAGE = """(function() { try {
            var token = '';
            try { var raw = localStorage.getItem('__oneme_auth'); token = raw ? JSON.parse(raw).token : ''; } catch (e) {}
            var app = document.getElementById('app') || document.body;
            var ready = !!(app && app.querySelector('button, input, [role="button"]') && app.innerText.trim().length > 20);
            return {url: location.href, token: typeof token === 'string' ? token : '', ready: ready};
        } catch (e) { return null; } })();"""

        /** The original's MaxTransportRiskDialog: five seconds before going on, and the choice not to be warned again. */
        fun confirmRisk(context: Context,onContinue: () -> Unit) {
            if(DataStore.configurationStore.getBoolean(RISK_KEY,false)) { onContinue(); return }
            val ui = ServerUi(context)
            val never = CheckBox(context).apply { text = "Больше не предупреждать"; setTextColor(MobilePalette.text) }
            val body = ui.column(16).apply {
                addView(never)
                listOf("Транспорт MAX работает через ваш личный аккаунт MAX: туннель создаёт звонок в группе и держит его открытым, используя неофициальные методы веб-клиента.",
                    "Такое использование нарушает правила сервиса и заметно повышает риск ограничения или блокировки аккаунта MAX, вплоть до потери привязанного номера. Оценить вероятность бана заранее невозможно.",
                    "Используйте отдельный, второстепенный аккаунт и не привязывайте основной. Продолжая, вы принимаете эти риски на себя.").forEach {
                    addView(ui.text(it,13f,MobilePalette.muted).apply { setPadding(0,ui.dp(6),0,0) })
                }
            }
            val dialog = MobileAlertDialogBuilder(context).setTitle("Внимание!").setView(android.widget.ScrollView(context).apply { addView(body) })
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Всё равно продолжить") { _,_ ->
                    if(never.isChecked) DataStore.configurationStore.putBoolean(RISK_KEY,true)
                    onContinue()
                }.setCancelable(true).create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            val accept = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            val timer = Handler(Looper.getMainLooper())
            var left = 5
            fun show() { accept.isEnabled = left<=0; accept.text = if(left>0) "Всё равно продолжить ($left)" else "Всё равно продолжить" }
            val step = object : Runnable { override fun run() { left--; show(); if(left>0 && dialog.isShowing) timer.postDelayed(this,1000) } }
            show(); timer.postDelayed(step,1000)
            dialog.setOnDismissListener { timer.removeCallbacksAndMessages(null) }
        }
    }
}
