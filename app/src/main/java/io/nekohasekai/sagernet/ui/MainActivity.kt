package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePaddingRelative
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceDataStore
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.Key
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.aidl.ISagerNetService
import app.qthrone.mobile.aidl.SpeedDisplayData
import app.qthrone.mobile.aidl.TrafficDataBatch
import app.qthrone.mobile.bg.BaseService
import app.qthrone.mobile.bg.SagerConnection
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.SettingsRegistry
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.preference.OnPreferenceDataStoreChangeListener
import app.qthrone.mobile.database.backup.BackupEntry
import app.qthrone.mobile.databinding.LayoutMainBinding
import app.qthrone.mobile.ktx.alert
import app.qthrone.mobile.ktx.isPreview
import app.qthrone.mobile.ktx.launchCustomTab
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ui.profile.ProfileTextImport
import app.qthrone.mobile.ui.route.RouteImports
import app.qthrone.mobile.ui.settings.DnsSettingsFragment
import app.qthrone.mobile.ui.settings.XrayGeoSettingsFragment
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.ui.MessageStore
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.utils.AppShortcuts
import app.qthrone.mobile.utils.PlatformNotifications
import app.qthrone.mobile.utils.Theme
import app.qthrone.mobile.utils.WifiStateAccess
import app.qthrone.mobile.widget.bars
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    companion object {
        /** Texts to import (the QR scanner): URLs and deep links one by one, the rest as one import. */
        const val EXTRA_IMPORT_TEXTS = "importTexts"
        const val EXTRA_IMPORT_TARGET = "importTarget"
    }

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captchaController=MobileCaptchaController(this)
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        // White theme: a dark FAB with a light icon (white on white is unreadable)
        if (Theme.isWhiteTheme()) {
            binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_white_theme_fab)
            )
        }
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black,
                // The White theme and its night fallback read ?itemShapeFillColor too, so the selection stays visible
                R.style.Theme_SagerNet_White,
                R.style.Theme_SagerNet_White_Night
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        binding.drawerLayout.closeDrawers()
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        navigation.visibility = View.GONE
        supportFragmentManager.addOnBackStackChangedListener {
            currentMainFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        // NavigationView pads its header and menu itself (top/bottom); its side follows side bars and cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.bars()
            val rtl = navigation.layoutDirection == View.LAYOUT_DIRECTION_RTL
            navigation.updatePaddingRelative(start = if (rtl) bars.right else bars.left)
            insets
        }
        // The drawer's surface runs under the status bar: the icons follow it while the drawer is mostly open.
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var overDrawer = false
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if ((slideOffset > 0.5f) == overDrawer) return
                overDrawer = !overDrawer
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    if (overDrawer) !Theme.usingNightMode() else lightStatusBar
            }
        })

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_mobile_home)
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        onBackPressedDispatcher.addCallback {
            val settings = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
            if (settings != null && settings.childFragmentManager.backStackEntryCount > 0) {
                settings.childFragmentManager.popBackStack()
            } else if(supportFragmentManager.backStackEntryCount>0) {
                supportFragmentManager.popBackStack()
            } else if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is MobileHomeFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_mobile_home)
            }
        }

        binding.fab.setOnClickListener { toggleService() }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        OpenConnectAuthDialog(this).start()
        DataStore.configurationStore.registerChangeListener(this)

        if (savedInstanceState == null) intent?.let(::handleImportIntent)
        SubscriptionReportDialog.observe(this)
        SubscriptionStartup.run(this)

        refreshNavMenu(DataStore.apiDashboardEnabled)

        if (savedInstanceState == null) {
            firstStart.start()
            if (intent?.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false) == true) wifiFlow.run()
        }
        runOnDefaultDispatcher { AppShortcuts.publish(applicationContext) }

        if (isPreview && DataStore.previewHintDismissedVersion != BuildConfig.VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.VERSION_NAME
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)
        DataStore.serviceError.takeIf { it.isNotEmpty() }?.let(::showServiceError)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    /** The dashboard item follows the sing-box API (core_box_api_port), which serves the dashboard. */
    fun refreshNavMenu(dashboard: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = dashboard
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false)) {
            wifiFlow.run()
            return
        }

        handleImportIntent(intent)
    }

    /** VIEW links and files, SEND streams and texts, and the texts of the QR scanner ([EXTRA_IMPORT_TEXTS]). */
    private fun handleImportIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_IMPORT_TARGET)?.let {
            intent.removeExtra(EXTRA_IMPORT_TARGET)
            ImportTargetActivity.consume(this, it)
            return
        }
        intent.getStringArrayListExtra(EXTRA_IMPORT_TEXTS)?.let {
            SubscribeFlows.importTexts(this, it)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> openLink(intent.data ?: return)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    openFile(stream)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { SubscribeFlows.importText(this, it) }
                }
            }
        }
    }

    /**
     * Files go to the backup restore or the text import; subscription links show the Groups screen and ask; route and
     * other throne:// commands follow the desktop's deep links; profile links are confirmed before the import.
     */
    private fun openLink(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file") return openFile(uri)
        val link = uri.toString()
        if (SubscribeFlows.subscribeLink(link) != null) displayFragmentWithId(R.id.nav_mobile_home)
        if (scheme == "happ" || scheme == "incy" || scheme == "http" || scheme == "https" || scheme == "clash" || scheme == "throne" && !uri.host.equals("add", ignoreCase = true)) {
            SubscribeFlows.importText(this, link)
        } else {
            runOnDefaultDispatcher { importProfile(uri) }
        }
    }

    private fun openFile(uri: Uri) {
        // Sniffing the backup magic may read a cloud file over the network.
        runOnDefaultDispatcher {
            if (!BackupEntry.open(this@MainActivity, uri)) SubscribeFlows.importUri(this@MainActivity, uri)
        }
    }

    /** throne://route and throne://remoteroute links (deep link, QR code, clipboard); the Routes screen shows the result. */
    fun importRouteLink(text: String) {
        RouteImports.importLink(this, text) { displayFragmentWithId(R.id.nav_route) }
    }

    /** Opens one settings sub-screen, e.g. Settings вЂє Routing, with the settings root below it. */
    fun openSettingsScreen(fragmentClass: String, title: CharSequence) {
        val settings = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
        if (settings != null) settings.openScreen(fragmentClass, title)
        else displayFragment(SettingsFragment.forScreen(fragmentClass, title))
        navigation.menu.findItem(R.id.nav_settings).isChecked = true
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    /** A profile link opened from outside: confirmed, then imported into the current group. */
    suspend fun importProfile(uri: Uri) {
        val link = uri.toString()
        val profile = try {
            ProfileTextImport.parse(link).firstOrNull() ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    displayFragmentWithId(R.id.nav_mobile_home)
                    SubscribeFlows.importText(this@MainActivity, link)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment is MobileHomeFragment) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        currentMainFragment = fragment
        val transaction=supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.settings_enter, R.anim.settings_exit, R.anim.settings_pop_enter, R.anim.settings_pop_exit)
        val previous = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (previous is MobileHomeFragment && fragment !is MobileHomeFragment) {
            transaction.hide(previous)
                .setMaxLifecycle(previous, androidx.lifecycle.Lifecycle.State.STARTED)
                .add(R.id.fragment_holder, fragment)
        } else {
            transaction.replace(R.id.fragment_holder, fragment)
        }
        if(fragment !is MobileHomeFragment && supportFragmentManager.findFragmentById(R.id.fragment_holder)!=null) transaction.addToBackStack(null)
        transaction.commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment
        // Hidden legacy controls must not intercept taps on the dashboard's bottom rows.
        binding.stats.visibility = if (showControls) View.VISIBLE else View.INVISIBLE
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        binding.stats.syncMainControls(
            showControls,
            DataStore.serviceState,
            showWhenConnected,
            animate,
        )
        binding.fab.animate().cancel()
        if (showControls) {
            binding.fab.show()
            // a start error shown on another screen sits at the bottom, where the FAB now comes back
            errorBar?.takeIf { it.isShown && it.anchorView == null }?.anchorAboveFab()
        } else {
            binding.fab.hideProgress()
            binding.fabProgress.hide()
            binding.fabProgress.visibility = View.INVISIBLE
            if (animate && binding.fab.isLaidOut) {
                binding.fab.hide()
            } else {
                binding.fab.visibility = View.INVISIBLE
            }
        }
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_mobile_home -> displayFragment(MobileHomeFragment())
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(DashboardFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://throneproj.github.io")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.menu.findItem(id)?.isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        when (val fragment = currentMainFragment ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)) {
            is MobileHomeFragment -> fragment.updateState()
            is RouteFragment -> fragment.onServiceStateChanged()
            is DashboardFragment -> fragment.onServiceStateChanged()
        }

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (state == BaseService.State.Connecting) errorBar?.dismiss()
        if (msg == null) return
        if (msg == DataStore.serviceError) showServiceError(msg) else snackbar(getString(R.string.vpn_error, msg)).show()
    }

    private var errorBar: Snackbar? = null
    private var errorShown = ""

    /** Show a failed start briefly, with a direct action for known configuration errors. */
    private fun showServiceError(message: String) {
        if (errorBar?.isShownOrQueued == true && errorShown == message) return
        errorShown = message
        val warpTransportError = message.contains(app.qthrone.mobile.outbound.config.WarpHop.TCP_ONLY_ERROR)
        val bar = snackbar(if (warpTransportError) getString(R.string.warp_tcp_only_error) else message).setDuration(Snackbar.LENGTH_LONG)
        if (warpTransportError) {
            bar.setAction(R.string.warp_settings) {
                openSettingsScreen(app.qthrone.mobile.ui.settings.WarpSettingsFragment::class.java.name, getString(R.string.warp_settings))
            }
        } else if (DataStore.serviceErrorDns) {
            bar.setAction(R.string.settings_dns) {
                openSettingsScreen(DnsSettingsFragment::class.java.name, getString(R.string.settings_dns))
            }
        } else if (DataStore.serviceErrorGeo) {
            bar.setAction(R.string.xray_geo_assets_action) {
                openSettingsScreen(XrayGeoSettingsFragment::class.java.name, getString(R.string.xray_geo_assets))
            }
        } else if (DataStore.serviceErrorRuleSets) {
            bar.setAction(R.string.rule_set_deferred_action) { confirmStartWithoutRuleSets() }
        } else {
            bar.setAction(R.string.menu_log) { displayFragmentWithId(R.id.nav_logcat) }
        }
        errorBar = bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_ACTION || event == DISMISS_EVENT_TIMEOUT) {
                    DataStore.serviceError = ""
                    DataStore.serviceErrorDns = false
                    DataStore.serviceErrorGeo = false
                    DataStore.serviceErrorRuleSets = false
                }
            }
        }).also { it.show() }
    }

    /** The next start, within a few minutes, goes without the rule-sets it could not download (DeferredRuleSets). */
    private fun confirmStartWithoutRuleSets() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_deferred_title)
            .setMessage(R.string.rule_set_deferred_message)
            .setPositiveButton(R.string.connect) { _, _ ->
                if (DataStore.serviceState.canStop) return@setPositiveButton
                DataStore.startWithoutRuleSets = System.currentTimeMillis()
                startFromUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.visibility == View.VISIBLE) anchorAboveFab()
        }
    }

    // Visibility, not isShown: the saved start error is shown from the first onResume, before the window is attached.
    // The layout listener places the bar once the FAB has been laid out.
    private fun Snackbar.anchorAboveFab() {
        anchorView = binding.fab
        isAnchorViewLayoutListenerEnabled = true
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    private val firstStart = FirstStartPrompts(this)
    private val wifiFlow = WifiPermissionFlow(this)

    fun toggleService() {
        if (DataStore.serviceState.canStop) SagerNet.stopService() else {
            app.qthrone.mobile.ui.test.TestSessionClient.stop()
            startFromUi()
        }
    }

    /** Wi-Fi rules of the current route profile ask for location access once before a start from here. */
    private fun startFromUi() {
        if (DataStore.wifiPermissionAsked || WifiStateAccess.status(this) == WifiStateAccess.Status.OK) {
            return launchConnect()
        }
        lifecycleScope.launch {
            val usesWifi = withContext(Dispatchers.IO) {
                runCatching {
                    RouteManager.current().rules.any { rule ->
                        !rule.disabled && (rule.wifi_ssid.any { it.isNotBlank() } || rule.wifi_bssid.any { it.isNotBlank() })
                    }
                }.getOrDefault(false)
            }
            if (usesWifi) {
                DataStore.wifiPermissionAsked = true
                wifiFlow.run { launchConnect() }
            } else {
                launchConnect()
            }
        }
    }

    private fun launchConnect() {
        try {
            connect.launch(null)
        } catch (_: ActivityNotFoundException) {
            VpnRequestActivity.showConsentUnavailable(this)
        } catch (_: SecurityException) {
            VpnRequestActivity.showConsentUnavailable(this)
        }
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            // The JSON editors of custom_inbound and dns_object write here from their own activity.
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL,
            SettingsRegistry.CUSTOM_INBOUND.key, SettingsRegistry.DNS_OBJECT.key -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    private var captchaController: MobileCaptchaController?=null

    override fun onDestroy() {
        captchaController?.close();captchaController=null
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!binding.drawerLayout.isOpen && focusMainControls()) return true

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (SagerNet.isTv) {
                toggleService()
                return true
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    /** Where focus would move inside the current screen, when that view is on screen (other tabs' pages are not). */
    private fun screenFocusTarget(direction: Int): View? {
        val focused = currentFocus ?: return null
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view as? ViewGroup ?: return null
        if (!focused.isInside(root)) return null
        return FocusFinder.getInstance().findNextFocus(root, focused, direction)
            ?.takeIf { it.getGlobalVisibleRect(Rect()) }
    }

    /** D-pad down past the end of the screen's content reaches the FAB, then the stats bar (they float over it). */
    private fun focusMainControls(): Boolean {
        val focused = currentFocus ?: return false
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view ?: return false
        if (!focused.isInside(root)) return false
        var parent = focused.parent
        while (parent is View && parent !== root) {
            if (parent.canScrollVertically(1)) return false
            parent = parent.parent
        }
        // Other tabs' pages beside the pager are focusable too: go to what is visible below, if anything.
        screenFocusTarget(View.FOCUS_DOWN)?.let { return it.requestFocus() }
        return when {
            binding.fab.isShown && binding.fab.isFocusable -> binding.fab.requestFocus()
            binding.stats.isShown && binding.stats.isFocusable -> binding.stats.requestFocus()
            else -> false
        }
    }

    private fun View.isInside(root: View): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === root) return true
            view = view.parent as? View
        }
        return false
    }

}
