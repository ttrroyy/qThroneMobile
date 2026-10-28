package app.qthrone.mobile.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.content.res.use
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.Key
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.SendHwid
import app.qthrone.mobile.database.SubscriptionOptions
import app.qthrone.mobile.database.ServerSortRule
import app.qthrone.mobile.database.GroupSort
import app.qthrone.mobile.database.GroupSortAction
import app.qthrone.mobile.database.GroupSortMethod
import app.qthrone.mobile.database.preference.OnPreferenceDataStoreChangeListener
import app.qthrone.mobile.group.DeviceDetails
import app.qthrone.mobile.group.RequestIdentity
import app.qthrone.mobile.group.SubscriptionScheduler
import app.qthrone.mobile.group.SubscriptionNameFilter
import app.qthrone.mobile.group.ProviderLinks
import app.qthrone.mobile.ktx.FixedLinearLayoutManager
import app.qthrone.mobile.ui.settings.DefaultSummaryProvider
import app.qthrone.mobile.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Subscription identity/options editor and a separate settings screen share the same cached document.
 * Hidden fields stay intact when either screen saves; creating a group does not start a refresh.
 */
open class GroupSettingsActivity : ServerScreenActivity(R.layout.layout_config_settings), OnPreferenceDataStoreChangeListener {

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_SETTINGS = "settings"

        /** Ignored: a group created here is never updated right away (DialogEditGroup). */
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"

        /** A new group starts as a subscription with this URL. */
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"

        /** Values of the "Type" menu (@array/group_types). */
        const val TYPE_BASIC = 0
        const val TYPE_SUBSCRIPTION = 1

        private const val AUTO_SELECTOR = "autoselector"

        private const val KEY_FRONT = "groupFrontProxyId"
        private const val KEY_LANDING = "groupLandingProxyId"
        private const val KEY_AUTO_CLEAR = "groupAutoClearUnavailable"
        private const val KEY_SUBSCRIPTION = "groupSubscription"
        private const val KEY_SKIP_AUTO_UPDATE = "groupSkipAutoUpdate"
        private const val KEY_SHOW_INFO_CARD = "groupSubShowInfoCard"
        private const val KEY_USER_AGENT = "groupSubUserAgent"
        private const val KEY_SEND_HWID = "groupSubSendHwid"
        private const val KEY_HWID = "groupSubHwid"
        private const val KEY_HWID_OS = "groupSubHwidOs"
        private const val KEY_HWID_OS_VERSION = "groupSubHwidOsVersion"
        private const val KEY_HWID_MODEL = "groupSubHwidModel"
        private const val KEY_KEEP_WORKING = "groupSubKeepWorking"
        private const val KEY_REMOVE_DUPLICATES = "groupSubRemoveDuplicates"
        private const val KEY_REMOVE_INSECURE = "groupSubRemoveInsecure"
        private const val KEY_REMOVE_INVALID = "groupSubRemoveInvalid"
        private const val KEY_URL_TEST = "groupSubUrlTest"
        private const val KEY_REMOVE_UNAVAILABLE = "groupSubRemoveUnavailable"
        private const val KEY_SORT_RULE = "groupServerSortRule"
        private const val KEY_TIMEOUT = "groupSubRequestTimeout"
        private const val KEY_AUTO_UPDATE_MINUTES = "groupSubAutoUpdateMinutes"
        private const val KEY_HWID_COOKIE = "groupSubHwidCookie"
        private const val KEY_INSECURE_REQUESTS = "groupSubInsecureRequests"
        private const val KEY_IGNORE_FILTERS = "groupSubIgnoreNameFilters"
        private const val KEY_ALLOW_COLLAPSE = "groupSubAllowCollapse"
        private const val KEY_HIDE_SERVER_SETTINGS = "groupSubHideServerSettings"
        private const val KEY_NAME_INCLUDE = "groupSubNameInclude"
        private const val KEY_NAME_EXCLUDE = "groupSubNameExclude"
        private val NAME_FILTERS = listOf(
            KEY_NAME_INCLUDE to R.string.grp_filter_include,
            KEY_NAME_EXCLUDE to R.string.grp_filter_exclude,
        )

        private val HWID_KEYS = listOf(KEY_HWID, KEY_HWID_OS, KEY_HWID_OS_VERSION, KEY_HWID_MODEL)
    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "•".repeat(text.length)
            }
        }
    }

    private enum class SaveResult { OK, GONE, URL_REQUIRED }

    private var editingId = 0L
    private var loaded = false
    private var originalSignature = ""
    private var editorSheet: View? = null
    private var editorHandle: View? = null
    private var editorBehavior: com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.LinearLayout>? = null

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val bounds = android.graphics.Rect()
            editorBehavior?.isDraggable = editorHandle?.getGlobalVisibleRect(bounds) == true &&
                bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }
        return super.dispatchTouchEvent(event)
    }
    private val settingsMode get() = intent.getBooleanExtra(EXTRA_SETTINGS, false)
    override val isDialog get() = !settingsMode

    /** ResolveIdentity(nullptr), resolved while loading: the Advanced placeholders and the "Keep Default" state. */
    private var defaultUserAgent = ""
    private var defaultDevice = DeviceDetails("", "", "", "")
    private var globalSendHwid = false

    /** "[group] name" of the profiles the front/landing menus offer. */
    private val proxyLabels = HashMap<Long, String>()

    private val fragment get() = supportFragmentManager.findFragmentById(R.id.settings) as? GroupPreferenceFragment

    private val selectFront = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onProfilePicked(KEY_FRONT, it)
    }

    private val selectLanding = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onProfilePicked(KEY_LANDING, it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!settingsMode) createEditorSheet()
        if (!settingsMode) findViewById<View>(R.id.appbar).apply {
            // This toolbar is inside the sheet, below the status bar.
            ViewCompat.setOnApplyWindowInsetsListener(this, null)
            setPadding(0, 0, 0, 0)
            layoutParams = layoutParams.apply { height = (56 * resources.displayMetrics.density).toInt() }
            setBackgroundColor(MobilePalette.background)
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).apply {
            setSubtitleTextColor(MobilePalette.muted)
            if (!settingsMode) {
                setBackgroundColor(MobilePalette.background)
                setPadding(0,0,0,0)
                contentInsetStartWithNavigation = 0
            }
        }
        supportActionBar?.apply {
            setTitle(if (settingsMode) R.string.settings else R.string.grp_edit_group)
            setDisplayHomeAsUpEnabled(settingsMode)
            if (settingsMode) setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        onBackPressedDispatcher.addCallback(this) { close() }

        val fresh = savedInstanceState == null
        val link = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext null else ProxyGroup()
                if (editingId > 0) resetDanglingProxies(group)
                if (fresh) {
                    writeToCache(group)
                    originalSignature = editSignature()
                    val prefilled = editingId == 0L && !link.isNullOrBlank()
                    if (prefilled) {
                        DataStore.groupType = TYPE_SUBSCRIPTION
                        DataStore.subscriptionLink = link!!.trim()
                    }
                    DataStore.dirty = prefilled
                } else originalSignature = savedInstanceState?.getString("originalSignature") ?: editSignature()
                val identity = RequestIdentity.resolve(null)
                defaultUserAgent = identity.userAgent
                defaultDevice = identity.device
                globalSendHwid = identity.sendHwid
                for (key in listOf(KEY_FRONT, KEY_LANDING)) {
                    val id = proxyId(key)
                    if (id > 0) proxyLabel(id)?.let { proxyLabels[id] = it }
                }
                group
            }
            if (found == null) {
                finish()
                return@launch
            }
            loaded = true
            if (settingsMode) supportActionBar?.subtitle = found.displayName()
            invalidateOptionsMenu()
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, GroupPreferenceFragment())
                .commitNowAllowingStateLoss()
            editorSheet?.doOnPreDraw { sheet ->
                sheet.visibility = View.VISIBLE
                if (fresh) {
                    val enter = obtainStyledAttributes(
                        com.google.android.material.R.style.Animation_MaterialComponents_BottomSheetDialog,
                        intArrayOf(android.R.attr.windowEnterAnimation)).use { it.getResourceId(0,0) }
                    sheet.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this@GroupSettingsActivity,enter))
                }
            }
            DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("originalSignature", originalSignature)
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROFILE_DIRTY) return
        DataStore.dirty = editSignature() != originalSignature
        runOnUiThread { fragment?.refreshState(); invalidateOptionsMenu() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_delete)?.isVisible = false
        // An existing group saves itself on close; only a new one is added explicitly.
        menu.findItem(R.id.action_apply)?.isVisible = loaded && DataStore.dirty && editingId == 0L
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                if (loaded) lifecycleScope.launch { save() }
                return true
            }

        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun close() {
        if (!loaded || !DataStore.dirty) {
            finish()
            return
        }
        if (editingId > 0) {
            lifecycleScope.launch { save() }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.save) { _, _ -> lifecycleScope.launch { save() } }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun createEditorSheet() {
        val content=findViewById<android.view.ViewGroup>(android.R.id.content)
        val form=content.getChildAt(0) as android.widget.LinearLayout
        editorSheet=form
        form.visibility=View.INVISIBLE
        content.removeView(form)
        val density=resources.displayMetrics.density
        form.setPadding(0,(12*density).toInt(),0,0)
        val coordinator=object: androidx.coordinatorlayout.widget.CoordinatorLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                form.layoutParams.height=(View.MeasureSpec.getSize(heightMeasureSpec)*.88f).toInt()
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }
        coordinator.setOnClickListener { close() }
        form.isClickable=true
        form.background=android.graphics.drawable.GradientDrawable().apply {
            setColor(MobilePalette.background);cornerRadius=24*density
        }
        form.clipToOutline=true
        form.findViewById<View>(R.id.settings).layoutParams=android.widget.LinearLayout.LayoutParams(-1,0,1f)
        val handle=android.widget.FrameLayout(this).apply {
            minimumHeight=(48*density).toInt()
            contentDescription=getString(android.R.string.cancel)
            isFocusable=true
            setOnClickListener { close() }
            addView(View(this@GroupSettingsActivity).apply {
            background=android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.muted);cornerRadius=2*density }
            },android.widget.FrameLayout.LayoutParams((40*density).toInt(),(4*density).toInt(),android.view.Gravity.CENTER))
        }
        editorHandle=handle
        form.addView(handle,0,android.widget.LinearLayout.LayoutParams(-1,(48*density).toInt()))
        val behavior=com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.LinearLayout>().apply {
            isDraggable=false
            isHideable=true;skipCollapsed=true;state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            addBottomSheetCallback(object: com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if(newState==com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN) {
                        close()
                        state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            })
        }
        editorBehavior=behavior
        coordinator.addView(form,androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1,-1).apply {
            // The behavior supplies the bottom offset; bottom gravity would apply it twice.
            gravity=android.view.Gravity.TOP;this.behavior=behavior
        })
        setContentView(coordinator)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(-1,-1)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes=window.attributes.apply { dimAmount=.55f }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setWindowAnimations(com.google.android.material.R.style.Animation_MaterialComponents_BottomSheetDialog)
        // Animate the panel after its fields load; Android may replace an activity's window animation.
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,0,0)
        else overridePendingTransition(0,0)
    }

    // ------------------------------------------------------------------------------------------------ load / save

    /** dialog_edit_group.cpp:42-49: a front/landing profile that no longer exists is reset and saved on open. */
    private suspend fun resetDanglingProxies(group: ProxyGroup) {
        var changed = false
        if (group.frontProxyId > 0 && ProfileManager.getProfile(group.frontProxyId) == null) {
            group.frontProxyId = -1L
            changed = true
        }
        if (group.landingProxyId > 0 && ProfileManager.getProfile(group.landingProxyId) == null) {
            group.landingProxyId = -1L
            changed = true
        }
        if (changed) GroupRepo.save(group)
    }

    private fun writeToCache(group: ProxyGroup) {
        val store = DataStore.profileCacheStore
        DataStore.groupName = group.name
        DataStore.groupType = if (group.isSubscription) TYPE_SUBSCRIPTION else TYPE_BASIC
        DataStore.subscriptionLink = group.url
        store.putString(KEY_FRONT, group.frontProxyId.coerceAtLeast(-1L).toString())
        store.putString(KEY_LANDING, group.landingProxyId.coerceAtLeast(-1L).toString())
        store.putBoolean(KEY_AUTO_CLEAR, group.autoClearUnavailable)
        store.putBoolean(KEY_SKIP_AUTO_UPDATE, group.skipAutoUpdate)
        store.putBoolean(KEY_SHOW_INFO_CARD, group.subOptions.showInfoCard)
        val options = group.subOptions
        store.putString(KEY_USER_AGENT, options.userAgent)
        store.putString(KEY_SEND_HWID, options.sendHwid.value.toString())
        store.putString(KEY_HWID, options.hwid)
        store.putString(KEY_HWID_OS, options.hwidOs)
        store.putString(KEY_HWID_OS_VERSION, options.hwidOsVersion)
        store.putString(KEY_HWID_MODEL, options.hwidModel)
        store.putBoolean(KEY_KEEP_WORKING, options.keepWorking)
        store.putBoolean(KEY_REMOVE_DUPLICATES, options.removeDuplicates)
        store.putBoolean(KEY_REMOVE_INSECURE, options.removeInsecure)
        store.putBoolean(KEY_REMOVE_INVALID, options.removeInvalid)
        store.putBoolean(KEY_URL_TEST, options.urlTest)
        store.putBoolean(KEY_REMOVE_UNAVAILABLE, options.removeUnavailable)
        store.putString(KEY_SORT_RULE, options.effectiveSortRule.value.toString())
        store.putString(KEY_TIMEOUT, options.requestTimeoutSeconds.toString())
        store.putString(KEY_AUTO_UPDATE_MINUTES, options.autoUpdateMinutes.toString())
        store.putBoolean(KEY_HWID_COOKIE, options.sendHwidInCookie)
        store.putBoolean(KEY_INSECURE_REQUESTS, options.allowInsecureRequests)
        store.putBoolean(KEY_IGNORE_FILTERS, options.ignoreNameFilters)
        store.putBoolean(KEY_ALLOW_COLLAPSE, options.allowCollapse)
        store.putBoolean(KEY_HIDE_SERVER_SETTINGS, options.hideServerSettings)
        store.putString(KEY_NAME_INCLUDE, options.nameInclude)
        store.putString(KEY_NAME_EXCLUDE, options.nameExclude)
    }

    /** DialogEditGroupAdvanced::accept: the strings trimmed, disabled fields kept. */
    private fun optionsFromCache(): SubscriptionOptions {
        val store = DataStore.profileCacheStore
        fun text(key: String) = store.getString(key).orEmpty().trim()
        fun flag(key: String) = store.getBoolean(key, false)
        return SubscriptionOptions(
            userAgent = text(KEY_USER_AGENT),
            sendHwid = sendHwidMode(),
            hwid = text(KEY_HWID),
            hwidOs = text(KEY_HWID_OS),
            hwidOsVersion = text(KEY_HWID_OS_VERSION),
            hwidModel = text(KEY_HWID_MODEL),
            keepWorking = flag(KEY_KEEP_WORKING),
            removeDuplicates = flag(KEY_REMOVE_DUPLICATES),
            removeInsecure = flag(KEY_REMOVE_INSECURE),
            removeInvalid = flag(KEY_REMOVE_INVALID),
            urlTest = flag(KEY_URL_TEST),
            removeUnavailable = flag(KEY_REMOVE_UNAVAILABLE),
            serverSortRule = ServerSortRule.of(text(KEY_SORT_RULE).toIntOrNull() ?: 0),
            requestTimeoutSeconds = text(KEY_TIMEOUT).toIntOrNull()?.takeIf { it in 5..300 } ?: 0,
            autoUpdateMinutes = text(KEY_AUTO_UPDATE_MINUTES).toIntOrNull()?.takeIf { it >= SubscriptionScheduler.MIN_INTERVAL_MINUTES } ?: 0,
            sendHwidInCookie = flag(KEY_HWID_COOKIE),
            allowInsecureRequests = flag(KEY_INSECURE_REQUESTS),
            ignoreNameFilters = flag(KEY_IGNORE_FILTERS),
            allowCollapse = store.getBoolean(KEY_ALLOW_COLLAPSE, true),
            hideServerSettings = flag(KEY_HIDE_SERVER_SETTINGS),
            nameInclude = text(KEY_NAME_INCLUDE),
            nameExclude = text(KEY_NAME_EXCLUDE),
            showInfoCard = store.getBoolean(KEY_SHOW_INFO_CARD, true),
        )
    }

    private fun editSignature(): String = app.qthrone.mobile.outbound.json.jsonObjectOf(
        "name" to DataStore.groupName.trim(), "type" to DataStore.groupType,
        "url" to DataStore.subscriptionLink.trim(), "front" to proxyId(KEY_FRONT), "landing" to proxyId(KEY_LANDING),
        "clear" to DataStore.profileCacheStore.getBoolean(KEY_AUTO_CLEAR, false),
        "skip" to DataStore.profileCacheStore.getBoolean(KEY_SKIP_AUTO_UPDATE, false),
        "options" to optionsFromCache().toJson(),
    ).toCompact()

    /** A subscription's name filters must compile, like the auto selector's name filter (edit_autoselector onEnd). */
    private fun nameFilterError(): String? {
        if (DataStore.groupType != TYPE_SUBSCRIPTION) return null
        val store = DataStore.profileCacheStore
        if (store.getBoolean(KEY_IGNORE_FILTERS, false)) return null
        for ((key, title) in NAME_FILTERS) {
            val error = SubscriptionNameFilter.error(store.getString(key).orEmpty().trim()) ?: continue
            return getString(R.string.grp_filter_bad_regex, getString(title), error)
        }
        return null
    }

    private fun sendHwidMode(): SendHwid =
        SendHwid.of(DataStore.profileCacheStore.getString(KEY_SEND_HWID)?.toIntOrNull() ?: SendHwid.KEEP_DEFAULT.value)

    private fun proxyId(key: String): Long =
        DataStore.profileCacheStore.getString(key)?.toLongOrNull()?.takeIf { it > 0 } ?: -1L

    /** DialogEditGroup::accept; an existing subscription keeps a URL ("Please input URL"). */
    private suspend fun save() {
        nameFilterError()?.let {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.grp_filter)
                .setMessage(it)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        var savedGroupId = 0L
        val result = withContext(Dispatchers.IO) {
            val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext SaveResult.GONE else ProxyGroup()
            val url = DataStore.subscriptionLink.trim()
            if (DataStore.groupType==TYPE_SUBSCRIPTION && url.isEmpty()) return@withContext SaveResult.URL_REQUIRED
            val store = DataStore.profileCacheStore
            group.name = DataStore.groupName.trim()
            group.autoClearUnavailable = store.getBoolean(KEY_AUTO_CLEAR, false)
            group.url = if (DataStore.groupType == TYPE_SUBSCRIPTION) url else ""
            group.skipAutoUpdate = store.getBoolean(KEY_SKIP_AUTO_UPDATE, false)
            group.subOptions = optionsFromCache().also {
                it.routingProvider = group.subOptions.routingProvider
                it.providerRoutingEnabled = group.subOptions.providerRoutingEnabled
                it.providerRoutingManual = group.subOptions.providerRoutingManual
                it.providerRouting = group.subOptions.providerRouting
                it.providerRoutingProfiles = group.subOptions.providerRoutingProfiles
                it.providerIgnoreImport = group.subOptions.providerIgnoreImport
            }
            group.frontProxyId = proxyId(KEY_FRONT)
            group.landingProxyId = proxyId(KEY_LANDING)
            if (editingId > 0) GroupRepo.save(group) else GroupRepo.add(group)
            savedGroupId = group.id
            if (editingId > 0) when (group.subOptions.effectiveSortRule) {
                ServerSortRule.PING -> GroupSort.sortProfiles(group.id, GroupSortAction(GroupSortMethod.BY_LATENCY))
                ServerSortRule.NAME -> GroupSort.sortProfiles(group.id, GroupSortAction(GroupSortMethod.BY_NAME))
                ServerSortRule.NONE -> Unit
            }
            SaveResult.OK
        }
        if (result == SaveResult.OK) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_GROUP_ID, savedGroupId))
            SubscriptionScheduler.schedule()
        }
        when (result) {
            SaveResult.URL_REQUIRED -> Toast.makeText(this, R.string.group_url_required, Toast.LENGTH_SHORT).show()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------------------------------------ front / landing

    /** get_proxy_name: "[group] name"; null when the profile or its group is gone. */
    private fun proxyLabel(id: Long): String? {
        val profile = ProfileManager.getProfile(id) ?: return null
        val group = GroupRepo.get(profile.groupId) ?: return null
        return "[" + group.displayName() + "] " + profile.displayName()
    }

    private fun pickProfile(key: String) {
        val launcher: ActivityResultLauncher<Intent> = if (key == KEY_FRONT) selectFront else selectLanding
        val current = proxyId(key)
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) { ProfileManager.getProfile(current) }
            launcher.launch(Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java).apply {
                selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
            })
        }
    }

    /** Auto selectors are refused: they move server on their own (dialog_edit_group.cpp:63). */
    private fun onProfilePicked(key: String, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: return
        if (id <= 0) return
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) {
                val profile = ProfileManager.getProfile(id) ?: return@withContext null
                profile to proxyLabel(id)
            } ?: return@launch
            if (picked.first.type == AUTO_SELECTOR) {
                Toast.makeText(this@GroupSettingsActivity, R.string.grp_no_auto_selector, Toast.LENGTH_LONG).show()
                return@launch
            }
            picked.second?.let { proxyLabels[id] = it }
            fragment?.setProxy(key, id)
        }
    }

    // ------------------------------------------------------------------------------------------------ screen

    class GroupPreferenceFragment : PreferenceFragmentCompat() {

        private val host get() = requireActivity() as GroupSettingsActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            // A fragment restored before the activity reloaded its data stays empty; the activity replaces it.
            if (!host.loaded) return
            addPreferencesFromResource(R.xml.group_preferences)

            findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!.isEnabled = host.editingId == 0L
            setupProxyMenu(KEY_FRONT)
            setupProxyMenu(KEY_LANDING)

            findPreference<EditTextPreference>(KEY_USER_AGENT)!!.placeholder(host.defaultUserAgent)
            findPreference<EditTextPreference>(KEY_TIMEOUT)!!.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    val seconds = it.text?.toIntOrNull() ?: 0
                    if (seconds == 0) getString(R.string.sub_settings_timeout_inherit, DataStore.subRequestTimeoutSeconds)
                    else getString(R.string.sub_settings_timeout_summary, seconds)
                }
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER; it.setSingleLine() }
                setOnPreferenceChangeListener { _, value ->
                    val seconds = value?.toString()?.trim()?.toIntOrNull()
                    val valid = seconds != null && (seconds == 0 || seconds in 5..300)
                    if (!valid) Toast.makeText(requireContext(), R.string.sub_settings_timeout_override_error, Toast.LENGTH_SHORT).show()
                    valid
                }
            }
            findPreference<EditTextPreference>(KEY_AUTO_UPDATE_MINUTES)!!.apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    val minutes = it.text?.toIntOrNull() ?: 0
                    if (minutes > 0) getString(R.string.auto_update_interval_sum, minutes)
                    else getString(R.string.grp_auto_update_interval_inherit, DataStore.subAutoUpdate.takeIf { it > 0 }
                        ?.let { global -> getString(R.string.auto_update_interval_sum, global) } ?: getString(R.string.off))
                }
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER; it.setSingleLine() }
                setOnPreferenceChangeListener { _, value ->
                    val minutes = value?.toString()?.trim()?.toIntOrNull()
                    val valid = minutes == 0 || (minutes != null && minutes >= SubscriptionScheduler.MIN_INTERVAL_MINUTES)
                    if (!valid) Toast.makeText(requireContext(), getString(R.string.auto_update_interval_invalid, SubscriptionScheduler.MIN_INTERVAL_MINUTES), Toast.LENGTH_SHORT).show()
                    valid
                }
            }
            findPreference<SimpleMenuPreference>(KEY_SORT_RULE)!!.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                it.entry ?: ""
            }
            val device = host.defaultDevice
            findPreference<EditTextPreference>(KEY_HWID)!!.placeholder(device.hwid)
            findPreference<EditTextPreference>(KEY_HWID_OS)!!.placeholder(device.os)
            findPreference<EditTextPreference>(KEY_HWID_OS_VERSION)!!.placeholder(device.osVersion)
            findPreference<EditTextPreference>(KEY_HWID_MODEL)!!.placeholder(device.model)

            findPreference<SimpleMenuPreference>(KEY_SEND_HWID)!!.apply {
                val state = getString(if (host.globalSendHwid) R.string.on else R.string.off)
                entries = arrayOf(getString(R.string.grp_keep_default_state, state), getString(R.string.on), getString(R.string.off))
                if (value !in listOf("0", "1", "2")) value = SendHwid.KEEP_DEFAULT.value.toString()
                summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                    (it.entry ?: "").toString() + "\n" + getString(R.string.grp_send_hwid_tip)
                }
            }

            nameFilter(KEY_NAME_INCLUDE, R.string.grp_filter_include_empty)
            nameFilter(KEY_NAME_EXCLUDE, R.string.grp_filter_exclude_empty)

            refreshState()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            MobileSettingsStyle.apply(this, view)
            listView.layoutManager = FixedLinearLayoutManager(listView)
            // The list scrolls itself; only the handle may move the editor sheet.
            if (!host.settingsMode) listView.isNestedScrollingEnabled = false
            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        /** Empty means the resolved global value, shown as the summary and as the input hint. */
        private fun EditTextPreference.placeholder(value: String) {
            val shown = value.ifEmpty { getString(R.string.grp_not_available) }
            summaryProvider = DefaultSummaryProvider(shown)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT
                it.setSingleLine()
                it.hint = shown
            }
        }

        /** The pattern, or what an empty filter means; an invalid one says so before the save refuses it. */
        private fun nameFilter(key: String, empty: Int) {
            val preference = findPreference<EditTextPreference>(key) ?: return
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val text = it.text?.trim().orEmpty()
                val error = SubscriptionNameFilter.error(text)
                when {
                    text.isEmpty() -> getString(empty)
                    error != null -> getString(R.string.grp_filter_invalid, text, error)
                    else -> text
                }
            }
            preference.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                it.setSingleLine()
            }
        }

        private fun proxyText(id: Long): String = when {
            id <= 0 -> getString(R.string.grp_none)
            else -> host.proxyLabels[id] ?: getString(R.string.grp_missing_profile, id)
        }

        /** "None", the current profile as "[group] name", then "Select Profile…" which opens the profile picker. */
        private fun setupProxyMenu(key: String) {
            val preference = findPreference<Preference>(key) ?: return
            preference.summary = proxyText(host.proxyId(key))
            preference.setOnPreferenceClickListener {
                val current = host.proxyId(key)
                val items = arrayListOf<CharSequence>(getString(R.string.grp_none))
                if (current > 0) items.add(proxyText(current))
                items.add(getString(R.string.route_profile))
                app.qthrone.mobile.ui.MobileSelector.show(preference,items,if(current > 0) 1 else 0) { which ->
                    when(which) { 0 -> setProxy(key,-1L); items.lastIndex -> host.pickProfile(key) }
                }
                true
            }
        }

        fun setProxy(key: String, id: Long) {
            if (host.proxyId(key) != id) DataStore.profileCacheStore.putString(key, id.toString())
            findPreference<Preference>(key)?.summary = proxyText(id)
        }

        /** Section visibility, the HWID fields' enable rule and the count of set Advanced options. */
        fun refreshState() {
            if (preferenceScreen == null) return
            val store = DataStore.profileCacheStore
            val subscription = DataStore.groupType == TYPE_SUBSCRIPTION
            val editing = !host.settingsMode
            val settings = host.settingsMode || !subscription
            findPreference<Preference>(Key.GROUP_NAME)?.isVisible = editing
            findPreference<Preference>(Key.GROUP_TYPE)?.isVisible = editing && host.editingId == 0L
            findPreference<Preference>(Key.SUBSCRIPTION_LINK)?.isVisible = subscription && editing
            findPreference<PreferenceCategory>("groupEditOptions")?.isVisible = subscription && editing
            findPreference<PreferenceCategory>("groupCommon")?.apply {
                isVisible = editing
                setTitle(if(subscription) R.string.group_title_url else R.string.grp_common)
            }
            // The sort rule is part of the editor, next to the name and link.
            findPreference<Preference>(KEY_SORT_RULE)?.isVisible = editing
            for (key in listOf(KEY_AUTO_CLEAR, KEY_FRONT, KEY_LANDING))
                findPreference<Preference>(key)?.isVisible = settings
            for (key in listOf(KEY_SKIP_AUTO_UPDATE, KEY_AUTO_UPDATE_MINUTES, KEY_SHOW_INFO_CARD, KEY_ALLOW_COLLAPSE, KEY_KEEP_WORKING,
                KEY_REMOVE_DUPLICATES, KEY_REMOVE_INSECURE, KEY_REMOVE_INVALID, KEY_URL_TEST, KEY_REMOVE_UNAVAILABLE,
                KEY_IGNORE_FILTERS) + NAME_FILTERS.map { it.first })
                findPreference<Preference>(key)?.isVisible = subscription && settings
            findPreference<Preference>(KEY_AUTO_UPDATE_MINUTES)?.isEnabled = !store.getBoolean(KEY_SKIP_AUTO_UPDATE, false)
            findPreference<PreferenceCategory>(KEY_SUBSCRIPTION)?.apply {
                isVisible = settings
                title = if (subscription) null else getString(R.string.settings)
            }
            findPreference<androidx.preference.SwitchPreference>("groupEncryptedSubscription")?.isChecked = ProviderLinks.isEncrypted(DataStore.subscriptionLink)
            val http = DataStore.subscriptionLink.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) || ProviderLinks.isProviderLink(it) }
            for(key in listOf(KEY_TIMEOUT, KEY_USER_AGENT, KEY_SEND_HWID)+HWID_KEYS)
                findPreference<Preference>(key)?.isVisible = subscription && settings && http

            val mode = host.sendHwidMode()
            val sent = mode == SendHwid.ON || (mode == SendHwid.KEEP_DEFAULT && host.globalSendHwid)
            for (key in HWID_KEYS) findPreference<Preference>(key)?.isEnabled = sent
            findPreference<Preference>(KEY_HWID_COOKIE)?.isEnabled = sent
            val filtersEnabled = !store.getBoolean(KEY_IGNORE_FILTERS, false)
            for ((key, _) in NAME_FILTERS) findPreference<Preference>(key)?.isEnabled = filtersEnabled

        }
    }
}
