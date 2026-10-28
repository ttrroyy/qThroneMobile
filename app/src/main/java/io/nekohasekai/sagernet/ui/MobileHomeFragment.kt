package app.qthrone.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.bg.test.TestSpec
import app.qthrone.mobile.ui.test.TestSessionClient
import androidx.room.InvalidationTracker
import app.qthrone.mobile.R
import app.qthrone.mobile.TrafficFormat
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.*
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ui.profile.profileSettingsIntent
import app.qthrone.mobile.update.UpdateActivity
import app.qthrone.mobile.update.UpdateDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile dashboard backed by the existing Room repositories and subscription queue. */
class MobileHomeFragment : ToolbarFragment() {
    private var nativeConnection:app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeTest:app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeReceiverRegistered=false
    private val nativeStatusReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            val stage=runCatching { app.qthrone.mobile.bg.carrier.NativeConnectionStage.valueOf(intent.getStringExtra("stage").orEmpty()) }.getOrNull() ?: return
            val snapshot=app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot(intent.getLongExtra("profileId",0),stage)
            if(intent.getBooleanExtra("testing",false)) nativeTest=snapshot else nativeConnection=snapshot
            updateState()
        }
    }
    // Palette preferences live in SQLite. Read them once per refresh, never once per widget/server.
    private var blue = Color.rgb(87,190,229)
    private var surface = Color.rgb(49,51,54)
    private var muted = Color.rgb(173,182,191)
    private var foregroundColor = Color.rgb(231,234,237)
    private var toolbarColor = Color.rgb(34,35,37)
    private var backgroundColor = Color.rgb(30,30,30)
    private fun readPalette() {
        blue=MobilePalette.accent(requireContext());surface=MobilePalette.surface;muted=MobilePalette.muted
        foregroundColor=MobilePalette.text;toolbarColor=MobilePalette.toolbar;backgroundColor=MobilePalette.background
    }
    private var content: LinearLayout? = null
    private var updateBanner: TextView? = null
    private fun updateReleaseBanner() {
        val offer = UpdateDiscovery.available.value
        updateBanner?.apply {
            visibility = if (offer == null) View.GONE else View.VISIBLE
            text = if (offer == null) "" else getString(R.string.update_home_banner, offer.versionName)
            contentDescription = if (offer == null) "" else getString(R.string.update_home_banner_description, offer.versionName)
            setOnClickListener { offer?.let { UpdateActivity.start(requireContext(), it) } }
        }
    }
    private var power: TextView? = null
    private var currentCheck: TextView? = null
    private var geoBanner: LinearLayout? = null
    private var geoStatus: TextView? = null
    private var geoProgress: ProgressBar? = null
    private var geoCheck: ImageView? = null
    private fun updateGeoStatus() {
        val states = app.qthrone.mobile.route.ProviderGeoAssets.states.value.values
        val active = states.firstOrNull { !it.complete } ?: states.lastOrNull()
        geoBanner?.visibility = if (active == null) View.GONE else View.VISIBLE
        geoProgress?.visibility = if (active != null && !active.complete && active.error.isBlank()) View.VISIBLE else View.GONE
        geoCheck?.visibility = if (active?.complete == true) View.VISIBLE else View.GONE
        geoStatus?.apply {
            text = when {
                active == null -> ""
                active.error.isNotBlank() -> active.error
                active.complete -> getString(R.string.provider_geo_complete)
                else -> getString(R.string.provider_geo_loading) + " " + active.file + if (active.total > 0) "\n${active.bytes * 100 / active.total}% · " + android.text.format.Formatter.formatShortFileSize(requireContext(),active.bytes) + " / " + android.text.format.Formatter.formatShortFileSize(requireContext(),active.total) else ""
            }
        }
    }
    private var currentPing: String? = null
    private var currentPingConnectionAt = 0L
    private var currentTestArmed = false
    private var currentResultJob: Job? = null
    private var lastSubscriptionRefresh = 0L
    private var powerPulse: android.animation.ObjectAnimator?=null
    private var pulseTarget: View?=null
    private var load: Job? = null
    private var refreshPending = false
    /** An import writes from the service process, which Room's invalidation does not always report here. */
    private val groupListener = object : GroupRepo.Listener {
        private fun changed() { activity?.runOnUiThread { refresh() } }
        override suspend fun groupAdd(group: ProxyGroup) = changed()
        override suspend fun groupUpdated(group: ProxyGroup) = changed()
        override suspend fun groupRemoved(groupId: Long) = changed()
        override suspend fun groupUpdated(groupId: Long) = changed()
        override suspend fun groupsReordered() = changed()
    }
    private fun reorderActive(active: Boolean) {
        reordering=active
        if(active) load?.cancel() else refresh()
    }
    private var reordering=false
    private var groupReorder: MobileReorder?=null
    private val profileReorders=mutableMapOf<Long,MobileReorder>()
    private fun saveReorder(groupId: Long?, ids: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(groupId==null) GroupRepo.setDisplayOrder(ids) else ProfileManager.setOrder(groupId,ids)
                }
            } finally { reordering=false;refresh() }
        }
    }
    private fun moveProfile(id: Long, groupId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { ProfileManager.moveToGroup(listOf(id),groupId) } }
            finally { reordering=false;refresh() }
        }
    }
    /**
     * An expanded subscription can be taller than the screen; dragging it then hides everything
     * and leaves no visible place to drop. Fold every group to its header while a group moves,
     * keeping the dragged header under the finger, and unfold to the saved state afterwards.
     */
    private fun setGroupRowsCompact(column: LinearLayout, scroll: ScrollView, item: View, compact: Boolean) {
        val before=item.top-scroll.scrollY
        groupViews.forEach { (_,entry) ->
            val box=entry.second as? LinearLayout ?: return@forEach
            if(box.childCount==0) return@forEach
            box.getChildAt(box.childCount-1).visibility=if(compact || entry.first.collapsed) View.GONE else View.VISIBLE
        }
        column.doOnPreDraw { scroll.scrollTo(0,(item.top-before).coerceAtLeast(0)) }
    }
    private val latencyViews=mutableMapOf<Long,FrameLayout>()
    private val collapsed = mutableSetOf<Long>()
    private val knownGroups = mutableSetOf<Long>()
    private var serverScrollView: ScrollView? = null
    private var subscriptionTop: View? = null
    private var serverRows: LinearLayout? = null
    private var chromeKey: String? = null
    private data class ProfileContent(val id: Long, val type: String, val name: String?, val json: String)
    private data class GroupContent(val group: ProxyGroup, val profiles: List<ProfileContent>, val collapsed: Boolean, val page: Int, val colors: List<Int>)
    private val groupViews = mutableMapOf<Long, Pair<GroupContent, View>>()
    private val profilePages = mutableMapOf<Long, Int>()
    private val refreshButtons = mutableMapOf<Long, ImageButton>()
    private var renderedSelectedProxy = Long.MIN_VALUE
    private var observer: InvalidationTracker.Observer? = null
    private var openSwipe: MobileSwipeTile? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun tile(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) androidx.core.graphics.ColorUtils.blendARGB(surface,blue,.16f) else surface)
        cornerRadius = dp(5).toFloat()
        setStroke(if(selected) dp(1) else 1, if (selected) blue else MobilePalette.outline)
    }
    private fun text(value: String, size: Float = 16f, color: Int = foregroundColor) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun action(label: String, callback: () -> Unit) = text(label, 16f, blue).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        isFocusable = true; setOnClickListener { callback() }
    }
    private fun iconAction(icon: Int, description: String, color: Int = Color.TRANSPARENT, callback: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(foregroundColor); setBackgroundColor(color); setPadding(dp(12),dp(12),dp(12),dp(12))
        contentDescription=description; setOnClickListener { callback() }; MobileFeedback.apply(this, circle=true)
    }
    private fun View.showUpdating(updating: Boolean) {
        val previous = tag as? android.animation.ObjectAnimator
        if (!updating) { previous?.cancel(); tag=null; rotation=0f; return }
        if (previous != null) return
        tag=android.animation.ObjectAnimator.ofFloat(this,View.ROTATION,0f,360f).apply {
            duration=900;repeatCount=android.animation.ValueAnimator.INFINITE
            interpolator=android.view.animation.LinearInterpolator(); start()
        }
        addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { (view.tag as? android.animation.ObjectAnimator)?.cancel(); view.tag=null; view.rotation=0f }
        })
    }
    private fun updateSubscriptionButtons() {
        refreshButtons.forEach { (id, button) ->
            val updating=SubscriptionClient.states.value.containsKey(id)
            button.isEnabled=!updating; button.showUpdating(updating)
        }
    }
    private fun host() = requireActivity() as MainActivity
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        readPalette()
        val scroll = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = MobileHomeBackdrop()
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1,-1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        return scroll
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        androidx.core.content.ContextCompat.registerReceiver(requireContext(),nativeStatusReceiver,
            android.content.IntentFilter(app.qthrone.mobile.bg.carrier.NativeConnectionStatus.action()),requireContext().packageName+".SERVICE",null,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        nativeReceiverRegistered=true
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshots=withContext(Dispatchers.IO) { app.qthrone.mobile.bg.carrier.NativeConnectionStatus.read() to app.qthrone.mobile.bg.carrier.NativeConnectionStatus.read(true) }
            nativeConnection=snapshots.first;nativeTest=snapshots.second;updateState()
        }
        collapsed.clear()
        collapsed.addAll(requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).getStringSet("collapsedGroups",emptySet()).orEmpty().mapNotNull { it.toLongOrNull() })
        state?.getLongArray("knownGroups")?.let { knownGroups.addAll(it.toList()) }
        state?.let { saved -> saved.getLongArray("pageGroups")?.zip(saved.getIntArray("profilePages")?.toList().orEmpty())?.let { profilePages.putAll(it) } }
        observer = object : InvalidationTracker.Observer("groups", "profiles") {
            override fun onInvalidated(tables: Set<String>) { activity?.runOnUiThread { refresh() } }
        }.also { SagerDatabase.instance.invalidationTracker.addObserver(it) }
        GroupRepo.addListener(groupListener)
        viewLifecycleOwner.lifecycleScope.launch { SubscriptionClient.states.collect { updateSubscriptionButtons() } }
        viewLifecycleOwner.lifecycleScope.launch { app.qthrone.mobile.route.ProviderGeoAssets.states.collect { updateGeoStatus() } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { UpdateDiscovery.available.collect { updateReleaseBanner() } }
                launch {
                    while (true) {
                        UpdateDiscovery.refresh()
                        kotlinx.coroutines.delay(60_000)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.queuedProfiles.collect { updateLatencies() } }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.state.collect {
            updateLatencies()
            if(currentTestArmed && DataStore.serviceState.connected && it.testCurrent && !it.running) {
                val ping=it.rows.values.firstOrNull { row -> row.ok && row.latency>0 }?.latency
                currentPing=if(ping!=null && !it.cancelled) getString(R.string.qmobile_ping_success,ping) else getString(R.string.qmobile_unavailable)
                currentCheck?.text=currentPing
                currentTestArmed=false
                updateState()
                currentResultJob?.cancel()
                currentResultJob=viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(2500)
                    currentPing=null
                    currentCheck?.text=getString(R.string.qmobile_check_connection)
                }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch {
            while(true) { updateState(); kotlinx.coroutines.delay(1000) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("collapsedGroups", collapsed.toLongArray())
        outState.putLongArray("knownGroups", knownGroups.toLongArray())
        outState.putLongArray("pageGroups", profilePages.keys.toLongArray())
        outState.putIntArray("profilePages", profilePages.values.toIntArray())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() {
        groupReorder?.cancel();profileReorders.values.forEach { it.cancel() }
        load?.cancel()
        powerPulse?.cancel(); powerPulse=null; pulseTarget=null
        power?.scaleX=1f; power?.scaleY=1f
        super.onPause()
    }
    override fun onDestroyView() {
        if(nativeReceiverRegistered) { requireContext().unregisterReceiver(nativeStatusReceiver);nativeReceiverRegistered=false }
        groupReorder?.cancel();groupReorder=null;profileReorders.values.forEach { it.cancel() };profileReorders.clear();reordering=false
        geoBanner = null; geoStatus = null; geoProgress = null; geoCheck = null
        updateBanner = null
        GroupRepo.removeListener(groupListener)
        observer?.let { SagerDatabase.instance.invalidationTracker.removeObserver(it) }
        observer = null; load?.cancel(); content = null; powerPulse?.cancel();powerPulse=null;pulseTarget=null; power = null
        currentResultJob?.cancel(); currentResultJob=null
        if(!currentTestArmed) currentPing=null
        openSwipe=null; renderedStructure=null
        serverScrollView=null; serverRows=null; chromeKey=null; subscriptionTop=null
        groupViews.clear(); refreshButtons.clear(); latencyViews.clear(); profileRows.clear()
        super.onDestroyView()
    }
    private val profileRows=mutableMapOf<Long,View>()
    private var serverScrollPosition = 0
    private var renderedStructure: List<GroupContent>? = null
    private fun ViewGroup.childrenList(): List<View> = (0 until childCount).map { getChildAt(it) }
    fun refresh() {
        if (content == null || !isAdded || !isResumed || isHidden || reordering) return
        if(load?.isActive==true) { refreshPending=true;return }
        refreshPending=false
        readPalette()
        val colors=listOf(surface,blue,foregroundColor,muted,toolbarColor,backgroundColor)
        val collapsedSnapshot=collapsed.toSet()
        val pageSnapshot=profilePages.toMap()
        val previousStructure=renderedStructure
        load = viewLifecycleOwner.lifecycleScope.launch {
            var completed=false
            val nextLatencyViews=latencyViews.toMutableMap()
            val nextProfileRows=profileRows.toMutableMap()
            val nextGroupViews=groupViews.toMutableMap()
            val nextRefreshButtons=refreshButtons.toMutableMap()
            val nextProfileReorders=profileReorders.toMutableMap()
            try {
            val selected=withContext(Dispatchers.IO) { DataStore.selectedProxy }
            val (groups,structure,windows)=withContext(Dispatchers.IO) {
                val pinned=pinnedGroups()
                val groups=GroupRepo.all().sortedBy { if(it.id in pinned) 0 else 1 }.map { it to SagerDatabase.proxyDao.getByGroup(it.id) }
                val windows=groups.associate { (group,profiles) -> group.id to MobileProfilePaging.window(profiles.map { it.id },pageSnapshot[group.id],selected) }
                val keys=groups.map { (group,profiles) -> GroupContent(group.copy(subOptions=group.subOptions.copy()),profiles.map { ProfileContent(it.id,it.type,it.name,it.outboundJson) },group.subOptions.allowCollapse && group.id in collapsedSnapshot,windows.getValue(group.id).page,colors) }
                // Parse only displayed profiles off the UI thread. A collapsed subscription creates no rows.
                if(previousStructure != keys) groups.forEach { (group,profiles) ->
                    if(!group.subOptions.allowCollapse || group.id !in collapsedSnapshot) {
                        val window=windows.getValue(group.id)
                        profiles.subList(window.start,window.end).forEach { it.displayName();it.displayType() }
                    }
                }
                Triple(groups,keys,windows)
            }
            if(reordering) return@launch
            val column = content ?: return@launch
            groups.forEach { (group, _) -> knownGroups.add(group.id) }
            val unchanged=withContext(Dispatchers.Default) { previousStructure == structure }
            if (unchanged) {
                groups.forEach { (_, profiles) -> profiles.forEach { profile ->
                    nextLatencyViews[profile.id]?.tag = profile.latency
                } }
                updateLatencies()
                updateState()
                completed=true
                return@launch
            }
            val hasServers = groups.any { it.second.isNotEmpty() || it.first.isSubscription }
            val hwid = app.qthrone.mobile.group.RequestIdentity.applyCustomHwidParams(app.qthrone.mobile.group.DeviceDetails.get(),DataStore.subCustomHwidParams).hwid
            val nextChromeKey="$surface:$blue:$hasServers:$hwid"
            if(chromeKey != nextChromeKey) {
                chromeKey=nextChromeKey
                groupReorder=null;nextProfileReorders.clear()
                column.removeAllViews(); nextLatencyViews.clear(); nextProfileRows.clear(); nextGroupViews.clear(); nextRefreshButtons.clear()
                // Landscape: the header and the power button in a side column, so the server list keeps the full height.
                val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
                column.orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                val side=if(landscape) LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL } else column
                val body=if(landscape) LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL } else column
                if(landscape) {
                    column.addView(side,LinearLayout.LayoutParams(dp(280),-1))
                    column.addView(body,LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=dp(12) })
                }
                openSwipe=null
                val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                fun headerIcon(icon: Int, description: String, callback: (View) -> Unit) = ImageButton(requireContext()).apply {
                    setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(foregroundColor); background = tile(); setPadding(dp(12),dp(12),dp(12),dp(12))
                    contentDescription = description; tooltipText = description; setOnClickListener { callback(it) }; MobileFeedback.apply(this)
                }
                header.addView(headerIcon(R.drawable.ic_mobile_settings, getString(R.string.qmobile_settings)) { settingsMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd=dp(8) })
                // the routing profile switch (global, Wi-Fi, mobile whitelist...) without a trip through the settings
                header.addView(headerIcon(R.drawable.ic_mobile_route, getString(R.string.route_profile_quick_switch)) { routingMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd=dp(8) })
                header.addView(headerIcon(R.drawable.ic_mobile_subscriptions, getString(R.string.qmobile_manage_groups)) { subscriptionsMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                updateBanner = text("", 12f, blue).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isFocusable = true
                    // Compact artwork inside a full 48dp touch target, next to Settings.
                    background = android.graphics.drawable.InsetDrawable(tile(selected = true), 0, dp(7), 0, dp(7))
                    val icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_update)
                    icon?.setBounds(0, 0, dp(18), dp(18))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(5)
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(blue))
                    MobileFeedback.apply(this, radius = 5f)
                    // Set this after the background, whose inset padding replaces view padding.
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                val updateSlot = FrameLayout(requireContext()).apply {
                    addView(updateBanner, FrameLayout.LayoutParams(-2, dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
                }
                header.addView(updateSlot, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                })
                header.addView(headerIcon(R.drawable.ic_mobile_add, getString(R.string.qmobile_add)) { addMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                side.addView(header)
                updateReleaseBanner()
                val centre = FrameLayout(requireContext())
                power = action("") { host().toggleService() }.apply {
                    contentDescription = getString(R.string.qmobile_connect)
                    background = MobilePowerArtwork(blue)
                    var pressAnimator: android.animation.ValueAnimator? = null
                    setOnTouchListener { _, event ->
                        val artwork=background as? MobilePowerArtwork
                        val target=when(event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> .94f
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> 1f
                            else -> null
                        }
                        if(artwork!=null && target!=null) {
                            pressAnimator?.cancel()
                            pressAnimator=android.animation.ValueAnimator.ofFloat(artwork.pressScale,target).apply {
                                duration=if(target<1f) 90 else 150
                                interpolator=android.view.animation.DecelerateInterpolator()
                                addUpdateListener { artwork.pressScale=it.animatedValue as Float }
                                start()
                            }
                        }
                        false
                    }
                }
                centre.addView(power, FrameLayout.LayoutParams(dp(188), dp(188), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(8) })
                side.addView(centre, LinearLayout.LayoutParams(-1, dp(206)))
                updateState()
                val imports = LinearLayout(requireContext())
                fun importButton(label: String, icon: Int, callback: () -> Unit) = LinearLayout(requireContext()).apply {
                    gravity=Gravity.CENTER; isFocusable=true; contentDescription=label
                    setOnClickListener { callback() }
                    background=GradientDrawable().apply { setColor(surface); cornerRadius=dp(16).toFloat();setStroke(1,MobilePalette.outline) }
                    addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(foregroundColor); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
                    addView(text(label,14f).apply { setPadding(0,0,0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
                imports.addView(importButton(getString(R.string.qmobile_clipboard), R.drawable.ic_mobile_clipboard) { importClipboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                imports.addView(importButton(getString(R.string.qmobile_qr), R.drawable.ic_mobile_qr) { startActivity(Intent(requireContext(), ScannerActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
                if (hasServers) {
                    val above=FrameLayout(requireContext())
                    above.addView(MobileSeasonalView(requireContext()),FrameLayout.LayoutParams(-1,dp(64)))
                    currentCheck=text(currentPing ?: getString(R.string.qmobile_check_connection),12f,muted).apply {
                        maxLines=2;setPadding(dp(2),dp(2),dp(2),dp(2));gravity=Gravity.BOTTOM
                        isFocusable=true
                        MobileFeedback.apply(this,white=true)
                        setOnClickListener { checkCurrentConnection() }
                    }
                    above.addView(currentCheck,FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.BOTTOM).apply { bottomMargin=dp(6) })
                    subscriptionTop=above
                    above.layoutParams=LinearLayout.LayoutParams(-1,dp(64))
                    updateState()
                } else { currentCheck=null;subscriptionTop=null }
                geoBanner = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12))
                    background = GradientDrawable().apply { setColor(surface); cornerRadius=dp(16).toFloat();setStroke(1,MobilePalette.outline) }
                    visibility = View.GONE
                    // A tap hides the card; a new download shows it again.
                    contentDescription = getString(R.string.update_action_hide)
                    setOnClickListener { app.qthrone.mobile.route.ProviderGeoAssets.dismiss() }
                }
                geoStatus = text("",14f).apply { setPadding(0,0,dp(12),0) }
                geoProgress = ProgressBar(requireContext()).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(blue); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoCheck = ImageView(requireContext()).apply { setImageResource(R.drawable.ic_baseline_check_circle_24); imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76,175,80)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoBanner!!.addView(geoStatus,LinearLayout.LayoutParams(0,-2,1f))
                geoBanner!!.addView(geoProgress,LinearLayout.LayoutParams(dp(28),dp(28)))
                geoBanner!!.addView(geoCheck,LinearLayout.LayoutParams(dp(28),dp(28)))
                body.addView(geoBanner,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
                updateGeoStatus()
                val serverColumn=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                subscriptionTop?.let { body.addView(it) }
                val serverScroll=ScrollView(requireContext()).apply {
                    isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
                    clipToPadding=false; addView(serverColumn,FrameLayout.LayoutParams(-1,-2))
                    setOnScrollChangeListener { _, _, y, _, _ -> serverScrollPosition=y }
                }
                body.addView(serverScroll,LinearLayout.LayoutParams(-1,0,1f))
                serverRows=serverColumn; serverScrollView=serverScroll
                if(!hasServers) {
                    body.addView(View(requireContext()), LinearLayout.LayoutParams(-1,0,1f))
                    val identity=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL }
                    identity.addView(text("HWID: $hwid",14f,muted).apply { setPadding(dp(4),dp(4),dp(4),dp(4));maxLines=1;maxWidth=(resources.displayMetrics.widthPixels-dp(96)).coerceAtLeast(dp(120));ellipsize=android.text.TextUtils.TruncateAt.MIDDLE;contentDescription="HWID: $hwid" },LinearLayout.LayoutParams(-2,-2))
                    identity.addView(iconAction(R.drawable.ic_mobile_copy_outline,"Скопировать HWID") { SagerNet.trySetPrimaryClip(hwid) }.apply {
                        imageTintList=android.content.res.ColorStateList.valueOf(blue)
                        setPadding(dp(13),dp(13),dp(13),dp(13))
                    },LinearLayout.LayoutParams(dp(40),dp(40)))
                    body.addView(identity,LinearLayout.LayoutParams(-1,-2))
                    body.addView(text(getString(R.string.qmobile_empty),14f).apply { gravity=Gravity.CENTER; setPadding(dp(16),dp(16),dp(16),dp(12)) })
                    body.addView(imports,LinearLayout.LayoutParams(-1,dp(48)))
                }
            }
            val serverColumn=serverRows ?: return@launch
            val serverScroll=serverScrollView ?: return@launch
            val anchor=serverColumn.childrenList().firstOrNull { it.bottom > serverScroll.scrollY }
            val anchorId=nextGroupViews.entries.firstOrNull { it.value.second === anchor }?.key
            val anchorOffset=(anchor?.top ?: 0)-serverScroll.scrollY
            val groupDrag=groupReorder ?: MobileReorder(serverColumn,serverScroll,
                { reorderActive(it) },{ saveReorder(null,it) },
                { first,second -> (first in pinnedGroups())==(second in pinnedGroups()) },
                { item -> setGroupRowsCompact(serverColumn,serverScroll,item,true) },
                { item -> setGroupRowsCompact(serverColumn,serverScroll,item,false) }).also { groupReorder=it }
            val desired=mutableListOf<View>()
            val liveIds=groups.flatMap { it.second }.map { it.id }.toSet()
            nextLatencyViews.keys.retainAll(liveIds); nextProfileRows.keys.retainAll(liveIds)
            val liveGroups=groups.map { it.first.id }.toSet()
            nextGroupViews.keys.retainAll(liveGroups); nextRefreshButtons.keys.retainAll(liveGroups)
            nextProfileReorders.keys.retainAll(liveGroups)
            profilePages.keys.retainAll(liveGroups)

            var firstGroup = true
            for ((groupIndex, pair) in groups.withIndex()) {
                val (group,profiles)=pair
                // An emptied group stays listed so it can still be renamed, filled or deleted; only a fresh install's
                // lone empty list is hidden.
                if (profiles.isEmpty() && !group.isSubscription && groups.all { it.second.isEmpty() }) continue
                val signature=structure[groupIndex]
                val window=windows.getValue(group.id)
                if(profilePages[group.id]==pageSnapshot[group.id]) profilePages[group.id]=window.page
                val visibleProfiles=if(signature.collapsed) emptyList() else profiles.subList(window.start,window.end)
                val retainedEntry=nextGroupViews[group.id]
                val retained=if(withContext(Dispatchers.Default) { retainedEntry?.first==signature }) retainedEntry?.second else null
                if(retained != null) {
                    val retainedBox=retained as LinearLayout
                    val retainedRows=retainedBox.getChildAt(retainedBox.childCount-1) as LinearLayout
                    visibleProfiles.forEachIndexed { index,profile ->
                        val swipe=nextProfileRows[profile.id]?.parent as? MobileSwipeTile
                        if(swipe!=null && swipe.parent===retainedRows && retainedRows.getChildAt(index)!==swipe) {
                            retainedRows.removeView(swipe);retainedRows.addView(swipe,index)
                        }
                    }
                    desired.add(retained); firstGroup=false
                    profiles.forEach { nextLatencyViews[it.id]?.tag=it.latency }
                    continue
                }
                // A collapsed group or another page must release its old row references as well as its views.
                profiles.forEach { nextLatencyViews.remove(it.id);nextProfileRows.remove(it.id) }
                val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = tile() }
                val rows = LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val profileDrag=MobileReorder(rows,serverScroll,{ reorderActive(it) },{
                    saveReorder(group.id,MobileProfilePaging.reorder(profiles.map { p -> p.id },visibleProfiles.map { p -> p.id },it))
                })
                profileDrag.movable=true
                // A profile dragged out of another group lands at the end of this one; subscriptions refill themselves.
                if(!group.isSubscription) profileDrag.acceptFrom(box) { id -> moveProfile(id,group.id) }
                nextProfileReorders[group.id]=profileDrag
                val title = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron); imageTintList=android.content.res.ColorStateList.valueOf(muted); rotation=if(signature.collapsed) 0f else 90f
                    visibility=if(group.subOptions.allowCollapse) View.VISIBLE else View.INVISIBLE
                }
                val heading = LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,0,0); isFocusable=true }
                heading.addView(arrow,LinearLayout.LayoutParams(dp(20),dp(20)))
                val names=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(4),dp(8)) }
                names.addView(text(group.displayName(),17f).apply { setPadding(0,0,0,0); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                if(group.isSubscription && group.subLastUpdate>0) names.addView(text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(group.subLastUpdate*1000)),10f,muted).apply { setPadding(0,dp(3),0,0) })
                heading.addView(names,LinearLayout.LayoutParams(0,-2,1f))
                heading.setOnClickListener {
                    if(!group.subOptions.allowCollapse) return@setOnClickListener
                    if (!collapsed.add(group.id)) collapsed.remove(group.id)
                    requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).edit().putStringSet("collapsedGroups",collapsed.map { it.toString() }.toSet()).apply()
                    arrow.animate().rotation(if(group.id in collapsed) 0f else 90f).setDuration(180).start()
                    refresh()
                }
                groupDrag.bind(heading,box,group.id,heading)
                title.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
                if (group.isSubscription) title.addView(iconAction(R.drawable.ic_mobile_refresh,getString(R.string.qmobile_refresh)) { SubscriptionClient.refreshGroup(group.id,false) }.apply { isEnabled=!SubscriptionClient.states.value.containsKey(group.id); alpha=1f;nextRefreshButtons[group.id]=this },LinearLayout.LayoutParams(dp(40),dp(48)))
                title.addView(iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests)) { testMenu(profiles.map { it.id }.toLongArray(),group.displayName(),group.id,true) },LinearLayout.LayoutParams(dp(40),dp(48)))
                lateinit var more: ImageButton
                more=iconAction(R.drawable.ic_mobile_more,getString(R.string.qmobile_more)) { groupMenu(group,more) }
                title.addView(more,LinearLayout.LayoutParams(dp(44),dp(48)))
                box.addView(title)
                if (group.isSubscription && group.subOptions.showInfoCard) {
                    val quota=group.subInfo
                    val traffic=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),dp(8)) }
                    traffic.addView(iconAction(R.drawable.ic_mobile_info,getString(R.string.qmobile_subscription_info)) {
                        val url = quota.webUrl.ifBlank { group.url }
                        val uri = android.net.Uri.parse(url)
                        if (uri.scheme == "https" || uri.scheme == "http") runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val bar=FrameLayout(requireContext()).apply { background=GradientDrawable().apply { setColor(toolbarColor); cornerRadius=dp(3).toFloat(); setStroke(dp(1),muted) } }
                    var usage=getString(R.string.qmobile_quota_unknown)
                    if (quota.valid && quota.hasQuota) {
                        val upload=quota.upload.coerceAtLeast(0); val download=quota.download.coerceAtLeast(0)
                        val bytes=if(download>Long.MAX_VALUE-upload) Long.MAX_VALUE else upload+download
                        val used=TrafficFormat.format(bytes)
                        val total=if(quota.total>0) TrafficFormat.format(quota.total) else "\u221e"
                        usage="$used / $total"
                    }
                    bar.addView(text(usage,11f).apply {
                        gravity=Gravity.CENTER; setPadding(dp(6),dp(2),dp(6),dp(2))
                        setSingleLine(true)
                        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                    },FrameLayout.LayoutParams(-1,-1))
                    traffic.addView(bar,LinearLayout.LayoutParams(0,dp(28),1f).apply { marginStart=dp(8) })
                    if (quota.supportUrl.isNotBlank()) traffic.addView(iconAction(R.drawable.ic_mobile_globe,getString(R.string.qmobile_subscription_info)) {
                        val uri = android.net.Uri.parse(quota.supportUrl)
                        if (uri.scheme == "https" || uri.scheme == "http") runCatching { startActivity(Intent(Intent.ACTION_VIEW,uri)) }
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    box.addView(traffic)
                    if (quota.expire > 0) box.addView(text(getString(R.string.qmobile_expiry,java.text.DateFormat.getDateInstance().format(java.util.Date(quota.expire*1000))),11f,muted).apply { setPadding(dp(14),0,dp(14),dp(8)) })
                    if (quota.announce.isNotBlank()) box.addView(text(quota.announce,12f,muted).apply { setPadding(dp(14),0,dp(14),dp(10)) })
                }
                for ((profileIndex,profile) in visibleProfiles.withIndex()) {
                    // Give input and animation callbacks a turn even when a page contains complex rows.
                    if(profileIndex>0 && profileIndex%12==0) kotlinx.coroutines.delay(1)
                    val row = LinearLayout(requireContext()).apply {
                        gravity = Gravity.CENTER_VERTICAL; background = tile(profile.id == selected)
                        isFocusable = true
                        setOnClickListener {
                            DataStore.selectedProxy = profile.id
                            if (DataStore.serviceState.canStop) SagerNet.reloadService()
                            this@MobileHomeFragment.profileRows.forEach { (id,view) -> view.background=tile(id==profile.id); MobileFeedback.apply(view) }
                        }
                    }
                    nextProfileRows[profile.id]=row
                    val displayName=profile.displayName().trim()
                    val first=if(displayName.isNotEmpty()) displayName.codePointAt(0) else 0
                    val firstLength=Character.charCount(first)
                    val second=if(displayName.length>firstLength) displayName.codePointAt(firstLength) else 0
                    val hasFlag=first in 0x1f1e6..0x1f1ff && second in 0x1f1e6..0x1f1ff
                    val flagLength=if(hasFlag) firstLength+Character.charCount(second) else 0
                    val badgeBackground=GradientDrawable().apply { setColor(toolbarColor);cornerRadius=dp(7).toFloat() }
                    val countryCode=if(hasFlag) "${('a'.code+first-0x1f1e6).toChar()}${('a'.code+second-0x1f1e6).toChar()}" else ""
                    val flag=if(hasFlag) MobileCountryFlags.bitmap(requireContext(),countryCode) else null
                    val badge=ImageView(requireContext()).apply {
                        if(flag!=null) {
                            setImageBitmap(flag);scaleType=ImageView.ScaleType.FIT_XY
                            contentDescription="Country flag: ${countryCode.uppercase(java.util.Locale.ROOT)}"
                        } else {
                            setImageResource(R.drawable.ic_mobile_globe)
                            imageTintList=android.content.res.ColorStateList.valueOf(muted)
                            setPadding(dp(5),dp(5),dp(5),dp(5))
                        }
                    }
                    badge.background=badgeBackground;badge.clipToOutline=true
                    row.addView(badge,LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginStart=dp(10) })
                    val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(6), dp(6)) }
                    labels.addView(text(if(hasFlag) displayName.substring(flagLength).trimStart() else displayName, 15f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    labels.addView(text(profile.displayType(), 12f, muted).apply { setPadding(0,0,0,0); maxLines=1 })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    val latency=FrameLayout(requireContext()).apply { tag=profile.latency }
                    nextLatencyViews[profile.id]=latency
                    row.addView(latency,LinearLayout.LayoutParams(dp(62),dp(32)))
                    if(!group.isSubscription || !group.subOptions.hideServerSettings) row.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_chevron); setPadding(dp(6),dp(6),dp(6),dp(6)); contentDescription=getString(R.string.qmobile_edit); setOnClickListener { startActivity(profile.profileSettingsIntent(requireContext(),group.isSubscription)) } },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val swipe = MobileSwipeTile(requireContext(), row,
                        iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests),Color.rgb(36,171,85)) { openSwipe?.closeActions(); testMenu(longArrayOf(profile.id),profile.displayName(),group.id) },
                        iconAction(R.drawable.ic_mobile_share,getString(R.string.qmobile_share),Color.rgb(60,132,190)) { shareProfile(profile) },
                        iconAction(R.drawable.ic_mobile_delete,getString(R.string.qmobile_delete),Color.rgb(220,66,61)) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result=withContext(Dispatchers.IO) { ProfileManager.batchDeleteProfiles(listOf(profile.id),stopRunning=true) }
                                if(!result.ok) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
                                else if(profile.type=="qwdtt" && profile.id !in result.kept) app.qthrone.mobile.ktx.runOnDefaultDispatcher {
                                    if(app.qthrone.mobile.deploy.QwdttProfileApi.releaseDeleted(listOf(profile))>0) withContext(Dispatchers.Main) {
                                        Toast.makeText(app.qthrone.mobile.ktx.app,"Устройство отвязано от сервера",Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }).apply { onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions(); openSwipe=opened } }
                    profileDrag.bind(row,swipe,profile.id)
                    rows.addView(swipe, LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(5),0,dp(5),dp(3)) })
                }
                if(!signature.collapsed && profiles.size>MobileProfilePaging.PAGE_SIZE) {
                    val paging=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL }
                    fun pageButton(label:Int,target:Int,enabled:Boolean)=action(getString(label)) {
                        profilePages[group.id]=target;refresh()
                    }.apply { isEnabled=enabled;alpha=if(enabled) 1f else .4f;setPadding(dp(8),dp(12),dp(8),dp(12)) }
                    paging.addView(pageButton(R.string.dashboard_previous_servers,window.page-1,window.hasPrevious),LinearLayout.LayoutParams(-2,-2))
                    paging.addView(text(getString(R.string.dashboard_server_page,window.start+1,window.end,window.total),12f,muted).apply { gravity=Gravity.CENTER;setPadding(dp(4),dp(12),dp(4),dp(12)) },LinearLayout.LayoutParams(0,-2,1f))
                    paging.addView(pageButton(R.string.dashboard_next_servers,window.page+1,window.hasNext),LinearLayout.LayoutParams(-2,-2))
                    rows.addView(paging)
                }
                rows.visibility=if(signature.collapsed) View.GONE else View.VISIBLE
                box.addView(rows)

                box.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=if(firstGroup) 0 else dp(16) }
                nextGroupViews[group.id]=signature to box
                desired.add(box)
                firstGroup = false
            }
            serverColumn.childrenList().filter { it !in desired }.forEach { serverColumn.removeView(it) }
            desired.forEachIndexed { index, box ->
                (box.layoutParams as LinearLayout.LayoutParams).topMargin=if(index==0) 0 else dp(16)
                if(serverColumn.getChildAt(index) !== box) {
                    (box.parent as? ViewGroup)?.removeView(box)
                    serverColumn.addView(box,index)
                }
            }
            groupDrag.prune()
            // Restore before drawing, after layout knows the new row heights.
            serverScroll.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    serverScroll.viewTreeObserver.removeOnPreDrawListener(this)
                    val retained=anchorId?.let { nextGroupViews[it]?.second }
                    if(retained != null) serverScroll.scrollTo(0,(retained.top-anchorOffset).coerceAtLeast(0))
                    return true
                }
            })
            latencyViews.clear();latencyViews.putAll(nextLatencyViews)
            profileRows.clear();profileRows.putAll(nextProfileRows)
            groupViews.clear();groupViews.putAll(nextGroupViews)
            refreshButtons.clear();refreshButtons.putAll(nextRefreshButtons)
            profileReorders.clear();profileReorders.putAll(nextProfileReorders)
            updateSubscriptionButtons()
            updateLatencies()
            updateState()
            // Commit only a completed render: a cancelled page build must be retried.
            renderedStructure=structure
            completed=true
            } finally {
                if(!completed) { renderedStructure=null;chromeKey=null }
                if(refreshPending) {
                    refreshPending=false
                    view?.post { refresh() }
                }
            }
        }
    }
    private fun checkCurrentConnection() {
        if(!DataStore.serviceState.connected) return
        val session=TestSessionClient.state.value
        val accepted=if(session.running) session.testCurrent && session.kind==TestSpec.KIND_URL
            else TestSessionClient.startCurrent(TestSpec.KIND_URL)
        if(!accepted) return
        currentResultJob?.cancel(); currentResultJob=null
        currentTestArmed=true
        currentPing=getString(R.string.qmobile_ping_checking)
        currentCheck?.text=currentPing
        updateState()
    }
    fun updateState() {
        if(!isAdded || !isResumed || isHidden || power==null) return
        if(renderedSelectedProxy != DataStore.selectedProxy) {
            renderedSelectedProxy=DataStore.selectedProxy
            profileRows.forEach { (id, row) -> row.background=tile(id==renderedSelectedProxy); MobileFeedback.apply(row) }
        }
        val state = DataStore.serviceState
        if(!state.connected || currentPingConnectionAt != DataStore.mobileConnectedAt || lastSubscriptionRefresh != SubscriptionReportDialog.refreshEpoch) {
            lastSubscriptionRefresh = SubscriptionReportDialog.refreshEpoch
            currentPing=null; currentTestArmed=false
            currentResultJob?.cancel(); currentResultJob=null
            currentPingConnectionAt=if(state.connected) DataStore.mobileConnectedAt else 0L
            currentCheck?.text=getString(R.string.qmobile_check_connection)
        }
        power?.isEnabled = state != app.qthrone.mobile.bg.BaseService.State.Stopping
        val test=TestSessionClient.state.value
        val testing=test.running && test.kind==TestSpec.KIND_URL
        val snapshot=if(testing && !test.testCurrent) nativeTest?.takeIf {
            test.rows.containsKey(it.profileId) && test.rows[it.profileId]?.phase!=app.qthrone.mobile.ui.test.RowPhase.DONE
        } else nativeConnection?.takeIf {
            it.profileId==(if(state.connected) DataStore.currentProfile else DataStore.selectedProxy)
        }
        val stage=if(testing && test.testCurrent) null else snapshot?.stage
        val caption=if(state == app.qthrone.mobile.bg.BaseService.State.Stopping) getString(R.string.stopping)
        else if(testing || state.canStop || state.connected) getString(when(stage) {
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.AUTHORIZING -> R.string.qmobile_vk_authorizing
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.CAPTCHA -> R.string.qmobile_vk_captcha
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.WAITING_VK -> R.string.qmobile_vk_waiting
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.RECONNECTING -> R.string.qmobile_reconnecting
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.PREPARING, app.qthrone.mobile.bg.carrier.NativeConnectionStage.CONNECTING -> R.string.qmobile_connecting
            else -> if(testing || currentTestArmed) R.string.qmobile_ping_checking else if(state.connected) R.string.qmobile_connected else R.string.qmobile_connecting
        }) else ""
        val description=getString(R.string.qmobile_connect) + ", " + caption.ifEmpty { getString(R.string.qmobile_disconnected) }
        if(power?.contentDescription != description) power?.contentDescription=description
        power?.setTextColor(if (state.connected) Color.rgb(128,192,139) else blue)
        (power?.background as? MobilePowerArtwork)?.connected=state.connected
        currentCheck?.visibility=if(state.connected) View.VISIBLE else View.GONE
        val button=power
        if(!state.connected || pulseTarget!==button) {
            powerPulse?.cancel();powerPulse=null;pulseTarget=null
            button?.scaleX=1f;button?.scaleY=1f
        }
        if(state.connected && button!=null && powerPulse==null) {
            pulseTarget=button
            powerPulse=android.animation.ObjectAnimator.ofPropertyValuesHolder(button,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X,.995f,1.015f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y,.995f,1.015f)).apply {
                duration=1800;repeatCount=android.animation.ValueAnimator.INFINITE;repeatMode=android.animation.ValueAnimator.REVERSE
                interpolator=android.view.animation.AccelerateDecelerateInterpolator();start()
            }
        }
        power?.apply {
            textSize=13f;gravity=Gravity.CENTER;setPadding(0,dp(75),0,0)
            val seconds=((android.os.SystemClock.elapsedRealtime()-DataStore.mobileConnectedAt)/1000).coerceAtLeast(0)
            val fontSize=textSize
            (background as? MobilePowerArtwork)?.apply {
                captionSize=fontSize
                statusText=caption
                elapsedText=String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
            }
        }
    }
    private fun updateLatencies() {
        val state=TestSessionClient.state.value
        latencyViews.forEach { (id, holder) ->
            val result=state.rows[id].takeUnless { state.testCurrent }
            val busy=id in TestSessionClient.queuedProfiles.value || (state.running && result != null && result.phase != app.qthrone.mobile.ui.test.RowPhase.DONE)
            val latency=result?.takeIf { it.ok }?.latency ?: (holder.tag as? Int ?: 0)
            val unavailable=result?.failed==true || (result?.error?.isNotEmpty()==true && !ProxyEntity.isTestAborted(result.error))
            val label=if(unavailable) getString(R.string.qmobile_unavailable) else if(latency>0) "$latency ms" else ""
            val marker=if(busy) "busy" else label
            val previous=holder.contentDescription?.toString()
            if(previous==marker) return@forEach
            holder.contentDescription=marker
            holder.removeAllViews()
            if(busy) holder.addView(ProgressBar(requireContext()).apply {
                indeterminateTintList=android.content.res.ColorStateList.valueOf(blue)
            },FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
            else holder.addView(text(label,12f,if(unavailable) muted else Color.rgb(128,192,139)).apply {
                gravity=Gravity.CENTER;setPadding(0,0,0,0)
                if(previous=="busy") { alpha=0f;animate().alpha(1f).setDuration(180).start() }
            },FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun testMenu(ids: LongArray, label: String, gid: Long, wholeGroup: Boolean=false) {
        TestSessionClient.start(TestSpec(TestSpec.KIND_URL, profileIds=ids,scopeLabel=label,sortGroupId=if(wholeGroup) gid else 0L),gid)
        // A repeated ping starts its spinner over at once.
        ids.forEach { latencyViews[it]?.contentDescription=null }
        updateLatencies()
    }
    private val importFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) SubscribeFlows.importFile(requireActivity(), uri)
    }
    private fun importClipboard() {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (!value.isNullOrBlank()) SubscribeFlows.importText(requireActivity(), value)
        else Toast.makeText(requireContext(), R.string.qmobile_clipboard_empty, Toast.LENGTH_SHORT).show()
    }
    private fun pinnedGroups() = DataStore.mobilePinnedGroups.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    private fun shareProfile(profile:ProxyEntity) {
        val swipe=openSwipe ?: return
        fun option(label:String,icon:Int,callback:()->Unit):View {
            return LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;contentDescription=label;setBackgroundColor(Color.rgb(105,93,176))
                setOnClickListener { swipe.closeActions();callback() }
                addView(ImageView(requireContext()).apply { setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(23),dp(23)))
                addView(text(label,10f,Color.WHITE).apply { setPadding(0,dp(4),0,0);maxLines=1;includeFontPadding=false;gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
        }
        fun copy(value:String) { Toast.makeText(requireContext(),if(SagerNet.trySetPrimaryClip(value)) R.string.action_export_msg else R.string.action_export_err,Toast.LENGTH_SHORT).show() }
        val link=profile.exportLink().ifBlank { profile.exportJsonLink() }
        swipe.showShareChoices(
            option("URL",R.drawable.ic_mobile_link) { copy(link) },
            option(getString(R.string.qmobile_qr),R.drawable.ic_mobile_qr) { app.qthrone.mobile.widget.QRCodeDialog(link,profile.displayName()).show(parentFragmentManager,"profile-qr") },
            option("JSON",R.drawable.ic_mobile_json) { copy(org.json.JSONObject(profile.outbound.exportToJson().toCompact()).toString(2)) },
        )
    }
    private fun groupMenu(group: ProxyGroup, anchor: View) {
        val dialog=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(24)); setBackgroundColor(backgroundColor)
        }
        val handle=FrameLayout(requireContext()).apply {
            contentDescription=getString(android.R.string.cancel);isFocusable=true;setOnClickListener { dialog.dismiss() }
            addView(View(requireContext()).apply { background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(2).toFloat() } },
                FrameLayout.LayoutParams(dp(40),dp(4),Gravity.CENTER))
        }
        body.addView(handle,LinearLayout.LayoutParams(-1,dp(48)))
        dialog.dragOnlyFrom(handle)
        body.addView(text(group.displayName(),18f).apply { setTypeface(null,Typeface.BOLD) })
        fun entry(label:String,icon:Int,action:()->Unit) {
            val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56);background=tile(); isFocusable=true; contentDescription=label; setOnClickListener { dialog.dismiss();action() }; MobileFeedback.apply(this) }
            row.addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(blue) },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginStart=dp(14) })
            row.addView(text(label,17f).apply { gravity=Gravity.CENTER_VERTICAL; setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(-1,-2))
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        if(group.isSubscription) entry(getString(R.string.qmobile_refresh),R.drawable.ic_mobile_refresh) { SubscriptionClient.refreshGroup(group.id,false) }
        if(group.subOptions.routingProvider.isNotBlank()) entry(getString(R.string.provider_routing_title),R.drawable.ic_mobile_route) {
            startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group.id))
        }
        entry(getString(R.string.qmobile_ping),R.drawable.ic_mobile_test) { viewLifecycleOwner.lifecycleScope.launch { val ids=withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(group.id).map { it.id }.toLongArray() };testMenu(ids,group.displayName(),group.id,true) } }
        entry(getString(R.string.qmobile_edit),R.drawable.ic_mobile_edit) { editGroup(group,false) }
        if(group.isSubscription) entry(getString(R.string.settings),R.drawable.ic_mobile_settings) { editGroup(group,true) }
        val pinned=group.id in pinnedGroups()
        entry(getString(if(pinned) R.string.qmobile_unpin else R.string.qmobile_pin),R.drawable.ic_mobile_pin) { val ids=pinnedGroups().toMutableSet();if(pinned) ids.remove(group.id) else ids.add(group.id);DataStore.mobilePinnedGroups=ids.joinToString(",");refresh() }
        entry(getString(R.string.qmobile_delete),R.drawable.ic_mobile_delete) {
            viewLifecycleOwner.lifecycleScope.launch {
                val deleted=withContext(Dispatchers.IO) { GroupRepo.delete(group.id) }
                if(!deleted) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
            }
        }
        dialog.setContentView(androidx.core.widget.NestedScrollView(requireContext()).apply { addView(body) });dialog.show()
    }
    private fun editGroup(group: ProxyGroup, settings: Boolean) {
        startActivity(Intent(requireContext(), if(settings) GroupSettingsActivity::class.java else SubscriptionEditorActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            .putExtra(GroupSettingsActivity.EXTRA_SETTINGS, settings))
    }
    private fun addMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(getString(R.string.qmobile_new_group)).setOnMenuItemClickListener { addSubscription();true }
        popup.menu.add(getString(R.string.qmobile_clipboard)).setOnMenuItemClickListener { importClipboard(); true }
        popup.menu.add(getString(R.string.qmobile_qr)).setOnMenuItemClickListener { startActivity(Intent(requireContext(), ScannerActivity::class.java)); true }
        popup.menu.add(getString(R.string.qmobile_file)).setOnMenuItemClickListener { importFile.launch("*/*");true }
        popup.menu.add(getString(R.string.qmobile_manual)).setOnMenuItemClickListener { manualProfile();true }
        popup.menu.add(getString(R.string.qmobile_paste_json)).setOnMenuItemClickListener { importClipboard();true }
        popup.menu.add(getString(R.string.qmobile_copy_json)).setOnMenuItemClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val json=withContext(Dispatchers.IO) {
                    org.json.JSONArray().apply { SagerDatabase.proxyDao.getAll().forEach { put(org.json.JSONObject(it.outbound.exportToJson().toCompact())) } }.toString(2)
                }
                SagerNet.trySetPrimaryClip(json)
            };true
        }
        popup.show()
    }
    private fun addSubscription() {
        addGroup.launch(Intent(requireContext(), SubscriptionEditorActivity::class.java))
    }
    private val addGroup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val id=result.data?.getLongExtra(GroupSettingsActivity.EXTRA_GROUP_ID,0L) ?: 0L
        if(result.resultCode==android.app.Activity.RESULT_OK && id>0) viewLifecycleOwner.lifecycleScope.launch {
            val group=withContext(Dispatchers.IO) { GroupRepo.get(id) }
            if(group?.isSubscription==true) SubscriptionClient.refreshGroup(id,false)
        }
    }
    private fun settingsMenu(anchor: View) {
        val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
        popup.menu.add(R.string.settings).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_settings);true }
        popup.menu.add(R.string.settings_dns).setOnMenuItemClickListener { host().openSettingsScreen(app.qthrone.mobile.ui.settings.DnsSettingsFragment::class.java.name,getString(R.string.settings_dns));true }
        popup.menu.add(R.string.menu_log).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_logcat);true }
        popup.show()
    }
    private fun routingMenu(anchor: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val routes=withContext(Dispatchers.IO) { RouteManager.usable() }
            if(!anchor.isAttachedToWindow) return@launch
            val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
            routes.forEach { route ->
                popup.menu.add(route.name).apply {
                    isCheckable=true;isChecked=route.id==DataStore.currentRouteId
                    setOnMenuItemClickListener {
                        app.qthrone.mobile.ui.route.RouteQuickSwitch.switchTo(route.id)
                        app.qthrone.mobile.ui.route.RouteQuickSwitch.switched(this@MobileHomeFragment,route.name);true
                    }
                }
            }
            popup.menu.add(R.string.warp_enable).apply {
                isCheckable=true;isChecked=DataStore.enableWarp
                setOnMenuItemClickListener { app.qthrone.mobile.ui.route.RouteQuickSwitch.setWarpEnabled(this@MobileHomeFragment,!isChecked);true }
            }
            popup.menu.add(R.string.settings).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_route);true }
            popup.show()
        }
    }
    private fun subscriptionsMenu(anchor: View) {
        val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
        fun collapse(all: Boolean) {
            if(all) groupViews.keys.forEach { collapsed.add(it) } else collapsed.clear()
            requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).edit()
                .putStringSet("collapsedGroups",collapsed.map { it.toString() }.toSet()).apply()
            refresh()
        }
        popup.menu.add(R.string.qmobile_collapse_all).setOnMenuItemClickListener { collapse(true);true }
        popup.menu.add(R.string.qmobile_expand_all).setOnMenuItemClickListener { collapse(false);true }
        popup.menu.add(R.string.qmobile_refresh).setOnMenuItemClickListener {
            viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { GroupRepo.all().filter { it.isSubscription }.forEach { SubscriptionClient.refreshGroup(it.id,false) } } };true
        }
        popup.menu.add(R.string.qmobile_manage_groups).setOnMenuItemClickListener { host().displayFragmentWithId(R.id.nav_group);true }
        popup.menu.add(R.string.qmobile_new_group).setOnMenuItemClickListener { addSubscription();true }
        popup.show()
    }
    private fun manualProfile() {
        val sheet=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),0)
            setBackgroundColor(backgroundColor)
        }
        body.addView(View(requireContext()).apply {
            background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(2).toFloat() }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(40),dp(4)).apply {
            gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(20)
        })
        body.addView(text(getString(R.string.qmobile_manual),20f).apply {
            setTypeface(null,Typeface.BOLD)
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val protocols=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(24))
        }
        // Nested scrolling lets the sheet consume a downward drag only at the top of the list.
        // The handle and title stay outside the scrolling content.
        body.addView(androidx.core.widget.NestedScrollView(requireContext()).apply {
            isFillViewport=true;isVerticalScrollBarEnabled=false
            addView(protocols,ViewGroup.LayoutParams(-1,-2))
        },LinearLayout.LayoutParams(-1,0,1f))
        val nativeTypes = listOf("qwdtt" to "qWDTT", "csqtt" to "CSQTT", "wdttplus" to "WDTT Plus", "openflux" to "OpenFlux", "openfluxkey" to "OpenFlux Key", "olcrtc" to "olcRTC",
            "masterdns" to "MasterDNS", "cottendns" to "CottenDNS", "soar" to "Soar", "vaydns" to "VayDNS", "dnstt" to "DNSTT", "slipstream" to "SlipStream", "s3bypass" to "S3 Bypass", "sstp" to "SSTP")
        // Keep the same editors and localized labels as the original manual-entry menu.
        val originalTypes = listOf(
            "autoselector" to R.string.action_auto_selector,
            "socks" to R.string.action_socks,
            "http" to R.string.action_http,
            "shadowsocks" to R.string.action_shadowsocks,
            "vmess" to R.string.action_vmess,
            "vless" to R.string.action_vless,
            "xrayvless" to R.string.action_vless_xray,
            "trojan" to R.string.action_trojan,
            "mieru" to R.string.action_mieru,
            "naive" to R.string.action_naive,
            "hysteria" to R.string.action_hysteria,
            "tuic" to R.string.action_tuic,
            "juicity" to R.string.action_juicity,
            "shadowtls" to R.string.action_shadowtls,
            "anytls" to R.string.action_anytls,
            "trusttunnel" to R.string.action_trusttunnel,
            "snell" to R.string.action_snell,
            "ssh" to R.string.action_ssh,
            "wireguard" to R.string.action_wireguard,
            "masque" to R.string.action_masque,
            "openvpn" to R.string.action_openvpn,
            "openconnect" to R.string.action_openconnect,
            "direct" to R.string.action_direct,
            "custom" to R.string.custom_config,
            "chain" to R.string.proxy_chain,
        ).map { (type, title) -> type to getString(title) }
        for((type, title) in nativeTypes + originalTypes) {
            protocols.addView(androidx.appcompat.widget.AppCompatButton(requireContext()).apply {
                text=title;isAllCaps=false;textSize=18f;setTypeface(null,Typeface.BOLD)
                setTextColor(foregroundColor);background=tile();stateListAnimator=null
                supportBackgroundTintList=null
                gravity=Gravity.CENTER_VERTICAL or Gravity.START;includeFontPadding=false
                minimumHeight=dp(56);setPadding(dp(16),dp(14),dp(16),dp(14))
                MobileFeedback.apply(this,radius=5f)
                setOnClickListener {
                    isEnabled=false
                    sheet.dismiss()
                    ImportTargetActivity.start(requireActivity(),"manual",type)
                }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        sheet.setContentView(body,ViewGroup.LayoutParams(-1,(resources.displayMetrics.heightPixels*.9f).toInt()))
        sheet.setOnShowListener {
            val panel=sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            val available=(panel?.parent as? View)?.height?.takeIf { it>0 }
                ?: resources.displayMetrics.heightPixels
            body.layoutParams=body.layoutParams.apply { height=(available*.9f).toInt() }
            sheet.behavior.apply {
                skipCollapsed=true
                state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.show()
    }
}
