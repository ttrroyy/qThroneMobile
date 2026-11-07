package app.rawline.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.ui.test.TestSessionClient
import androidx.room.InvalidationTracker
import app.rawline.mobile.R
import app.rawline.mobile.Key
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.TrafficFormat
import app.rawline.mobile.SagerNet
import app.rawline.mobile.database.*
import app.rawline.mobile.group.SubscriptionClient
import app.rawline.mobile.ui.profile.profileSettingsIntent
import app.rawline.mobile.update.UpdateActivity
import app.rawline.mobile.update.UpdateDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile dashboard backed by the existing Room repositories and subscription queue. */

class MobileHomeFragment : ToolbarFragment() {
    private var nativeConnection:app.rawline.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeTest:app.rawline.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeReceiverRegistered=false
    private val nativeStatusReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            val stage=runCatching { app.rawline.mobile.bg.carrier.NativeConnectionStage.valueOf(intent.getStringExtra("stage").orEmpty()) }.getOrNull() ?: return
            val snapshot=app.rawline.mobile.bg.carrier.NativeConnectionStatus.Snapshot(intent.getLongExtra("profileId",0),stage,
                intent.getIntExtra("active",0),intent.getIntExtra("total",0))
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
    private var power: TextView? = null
    private var currentCheck: TextView? = null
    private var updatesTile: ImageButton? = null
    /** Standard tiles have no updates tile: a found version shows as a button between Settings and Add, one tap to update. */
    private var updateBanner: TextView? = null
    private var homeTitle: TextView? = null
    private var modeSwitches: Map<String,androidx.appcompat.widget.SwitchCompat> = emptyMap()
    private var restartAfterStop = false
    private fun controlAtBottom()=MobileHomeLayout.controlBottom
    private fun chipAtBottom()=MobileHomeLayout.chipBottom
    private fun chipNearList()=MobileHomeLayout.chipNearList
    private fun homeSwitchesOn()=DataStore.configurationStore.getString("qmobileHomeControl")=="switches"
    /** A switch starts or stops its own mode; turning on the other one moves the running connection over. */
    private fun pickMode(mode: String) {
        val state=DataStore.serviceState
        if(powerCovered() || state==app.rawline.mobile.bg.BaseService.State.Stopping) return
        if(state.canStop) {
            restartAfterStop=DataStore.serviceMode!=mode
            SagerNet.stopService()
            DataStore.serviceMode=mode
        } else {
            DataStore.serviceMode=mode
            host().toggleService()
        }
    }
    private fun updateReleaseBanner() {
        val offer = UpdateDiscovery.available.value?.takeIf { DataStore.updateCheckAuto }
        updateBanner?.apply {
            visibility = if (offer == null) View.GONE else View.VISIBLE
            homeTitle?.visibility = if (offer == null) View.VISIBLE else View.GONE
            text = if (offer == null) "" else getString(R.string.update_home_banner, offer.versionName)
            contentDescription = if (offer == null) "" else getString(R.string.update_home_banner_description, offer.versionName)
            setOnClickListener { offer?.let { UpdateActivity.start(requireContext(), it) } }
        }
    }
    /** A dot in the accent colour on the updates tile while a newer version is offered. */
    private fun updateUpdatesBadge() {
        val tile=updatesTile ?: return
        val offered=DataStore.updateCheckAuto && UpdateDiscovery.available.value!=null
        tile.foreground=if(!offered) null else object:android.graphics.drawable.Drawable() {
            private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=MobilePalette.accent(tile.context) }
            override fun draw(canvas: android.graphics.Canvas) {
                val r=dp(4).toFloat()
                canvas.drawCircle(bounds.right-r-dp(5),bounds.top+r+dp(5),r,paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
        }
    }
    private var geoBanner: LinearLayout? = null
    private var geoStatus: TextView? = null
    private var geoProgress: ProgressBar? = null
    private var geoCheck: ImageView? = null
    private fun updateGeoStatus() {
        val states = app.rawline.mobile.route.ProviderGeoAssets.states.value.values
        val active = states.firstOrNull { !it.complete } ?: states.lastOrNull()
        slideGeoBanner(active != null)
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
    private var geoSlide: android.animation.ValueAnimator? = null
    /** Appearing or leaving, the banner changes height gradually: the list under it never jumps. */
    private fun slideGeoBanner(show: Boolean) {
        val banner=geoBanner ?: return
        if(show==(banner.visibility==View.VISIBLE) && geoSlide==null) return
        geoSlide?.cancel()
        val width=(banner.parent as? View)?.width ?: 0
        if(width==0 || !banner.isAttachedToWindow) { banner.visibility=if(show) View.VISIBLE else View.GONE;return }
        banner.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val full=banner.measuredHeight
        val from=if(banner.visibility==View.VISIBLE) banner.height else 0
        banner.visibility=View.VISIBLE
        geoSlide=android.animation.ValueAnimator.ofInt(from,if(show) full else 0).apply {
            duration=220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener { banner.layoutParams=banner.layoutParams.apply { height=it.animatedValue as Int };banner.alpha=(it.animatedValue as Int).toFloat()/full.coerceAtLeast(1) }
            addListener(object: android.animation.AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled=true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if(cancelled) return
                    geoSlide=null
                    banner.layoutParams=banner.layoutParams.apply { height=ViewGroup.LayoutParams.WRAP_CONTENT };banner.alpha=1f
                    banner.visibility=if(show) View.VISIBLE else View.GONE
                }
            })
            start()
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
    private val refreshSoon = Runnable { refresh() }
    /** A new subscription's first update writes its group and profiles many times in a row; one rebuild instead of a flicker. */
    private fun scheduleRefresh() { activity?.runOnUiThread { view?.run { removeCallbacks(refreshSoon); postDelayed(refreshSoon, 150) } } }
    /** An import writes from the service process, which Room's invalidation does not always report here. */
    private val groupListener = object : GroupRepo.Listener {
        private fun changed() = scheduleRefresh()
        override suspend fun groupAdd(group: ProxyGroup) = changed()
        override suspend fun groupUpdated(group: ProxyGroup) = changed()
        override suspend fun groupRemoved(groupId: Long) = changed()
        override suspend fun groupUpdated(groupId: Long) = changed()
        override suspend fun groupsReordered() = changed()
    }
    private fun reorderActive(active: Boolean) {
        reordering=active
        if(active) load?.cancel() else {
            stopGroupScroll()
            groupStrip?.removeCallbacks(previewHoveredGroup)
            groupDragPreview.finish()?.let { groupScrollPositions[it.groupId]=it.scrollY }
            activeProfileDrag=null
            profileRows.values.forEach { (it.parent as? View)?.visibility=View.VISIBLE }
            if(!profileTransferPending) refresh()
        }
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
            } finally { reorderActive(false) }
        }
    }
    private fun moveProfile(id: Long, groupId: Long, beforeId: Long? = null) {
        profileTransferPending=true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val index=withContext(Dispatchers.IO) {
                    ProfileManager.moveToGroup(listOf(id),groupId,beforeId)
                    if(DataStore.mobileListMode=="groups") GroupRepo.setCurrent(groupId)
                    SagerDatabase.proxyDao.getIdsByGroup(groupId).indexOf(id)
                }
                if(index>=0) { profilePages[groupId]=index/MobileProfilePaging.PAGE_SIZE;pendingMovedProfile=id }
            }
            finally { profileTransferPending=false;reorderActive(false) }
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
    /** Subscriptions whose description is shown whole; the rest show two lines, so a long one does not hide the servers. */
    private val openNotes = mutableSetOf<Long>()
    private val knownGroups = mutableSetOf<Long>()
    private var serverScrollView: ScrollView? = null
    /** The focused subscription's header: above the list, so that only the servers scroll. */
    private var serverHeader: FrameLayout? = null
    private val groupHeads=mutableMapOf<Long,View>()
    private var subscriptionTop: View? = null
    private var serverRows: LinearLayout? = null
    private var groupStrip: LinearLayout? = null
    private var groupStripScroll: HorizontalScrollView? = null
    private var groupLeftIndicator: ImageView? = null
    private var groupRightIndicator: ImageView? = null
    private var serverDropArea: FrameLayout? = null
    private var pendingMovedProfile: Long? = null
    private var renderedGroupOrder=emptyList<Long>()
    private val groupDragPreview=MobileGroupDrag()
    private var activeProfileDrag: MobileReorder? = null
    private var profileTransferPending=false
    private var groupDragX=0f
    private val previewHoveredGroup=Runnable {
        if(reordering && groupDragPreview.previewHovered()) refresh()
    }
    private fun hoverGroup(id: Long?) {
        if(!groupDragPreview.hover(id)) return
        groupStrip?.removeCallbacks(previewHoveredGroup)
        if(id!=null) groupStrip?.postDelayed(previewHoveredGroup,180)
    }
    private fun hoveredTab() {
        val scroll=groupStripScroll ?: return
        val x=scroll.scrollX+groupDragX
        val tab=groupStrip?.childrenList()?.firstOrNull { x>=it.left && x<it.right }
        hoverGroup(tab?.tag as? Long)
    }
    private fun updateGroupIndicators() {
        val scroll=groupStripScroll ?: return
        val left=scroll.canScrollHorizontally(-1)
        val right=scroll.canScrollHorizontally(1)
        groupLeftIndicator?.alpha=if(left) 1f else .35f
        groupRightIndicator?.alpha=if(right) 1f else .35f
    }
    private val groupScrollPositions=mutableMapOf<Long,Int>()
    private var renderedGroupId=0L
    private var groupScrollDirection=0
    private val scrollGroups=object:Runnable {
        override fun run() {
            val scroll=groupStripScroll ?: return
            if(!reordering || groupScrollDirection==0) return
            scroll.scrollBy(dp(9)*groupScrollDirection,0)
            hoveredTab()
            scroll.postOnAnimation(this)
        }
    }
    private fun stopGroupScroll() {
        groupScrollDirection=0
        groupStripScroll?.removeCallbacks(scrollGroups)
    }
    private fun followGroupDrag(zone: View,event: DragEvent) {
        val scroll=groupStripScroll ?: return
        when(event.action) {
            DragEvent.ACTION_DRAG_ENTERED,DragEvent.ACTION_DRAG_LOCATION -> {
                val at=IntArray(2);zone.getLocationOnScreen(at)
                val viewport=IntArray(2);scroll.getLocationOnScreen(viewport)
                val x=at[0]+event.x-viewport[0]
                groupDragX=x.coerceIn(0f,(scroll.width-1).coerceAtLeast(0).toFloat())
                hoveredTab()
                groupScrollDirection=when { x<dp(44) -> -1; x>scroll.width-dp(44) -> 1; else -> 0 }
                scroll.removeCallbacks(scrollGroups)
                if(groupScrollDirection!=0) scroll.postOnAnimation(scrollGroups)
            }
            DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DROP,DragEvent.ACTION_DRAG_ENDED -> {
                stopGroupScroll()
                hoverGroup(null)
            }
        }
    }
    private fun selectGroup(id: Long) {
        if(reordering || id==renderedGroupId) return
        groupScrollPositions[renderedGroupId]=serverScrollView?.scrollY ?: 0
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { GroupRepo.setCurrent(id) }
            refresh()
        }
    }
    private fun sizeGroupTabs() {
        val strip=groupStrip ?: return
        val width=groupStripScroll?.width?.takeIf { it>0 } ?: return
        val visible=minOf(strip.childCount,3,maxOf(1,(width+dp(6))/(dp(144)+dp(6))))
        if(visible==0) return
        val tabWidth=(width-dp(6)*(visible-1))/visible
        strip.childrenList().forEachIndexed { index,tab ->
            val params=tab.layoutParams as LinearLayout.LayoutParams
            val gap=if(index==strip.childCount-1) 0 else dp(6)
            if(params.width!=tabWidth || params.marginEnd!=gap) {
                tab.layoutParams=params.apply { this.width=tabWidth;marginEnd=gap }
            }
        }
        strip.post { updateGroupIndicators() }
    }
    private fun renderGroupStrip(groups: List<ProxyGroup>, selected: Long, profiles: MobileReorder, reorder: MobileReorder) {
        val strip=groupStrip ?: return
        val scroll=groupStripScroll ?: return
        renderedGroupOrder=groups.map { it.id }
        listOfNotNull(groupLeftIndicator,groupRightIndicator).forEach {
            profiles.acceptFrom(it,false,::followGroupDrag,allowSameList=true) { _,_ -> }
        }
        if(reordering && groupDragPreview.origin!=null && strip.childCount==groups.size) {
            strip.childrenList().forEach { tab ->
                tab.isSelected=tab.tag==selected
                tab.background=tile(tab.isSelected)
                MobileFeedback.apply(tab)
            }
            updateGroupIndicators()
            return
        }
        val previous=scroll.scrollX
        val selectionChanged=renderedGroupId!=selected
        strip.removeAllViews()
        var active:View?=null
        groups.forEach { group ->
            val tab=LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(14),dp(8),dp(14),dp(8))
                background=tile(group.id==selected);isFocusable=true;isSelected=group.id==selected
                tag=group.id
                contentDescription=group.displayName()
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this,group.displayName())
                addView(text(group.displayName(),14f).apply {
                    setPadding(0,0,0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(-1,-2))
                addView(text(getString(if(group.isSubscription) R.string.subscription else R.string.group_basic),10f,muted).apply {
                    setPadding(0,dp(2),0,0);maxLines=1
                },LinearLayout.LayoutParams(-1,-2))
                setOnClickListener { selectGroup(group.id) };MobileFeedback.apply(this)
            }
            strip.addView(tab,LinearLayout.LayoutParams(dp(144),dp(54)))
            reorder.bind(tab,tab,group.id)
            profiles.acceptFrom(tab,group.id!=selected,::followGroupDrag,allowSameList=true) { id,_ -> moveProfile(id,group.id) }
            if(group.id==selected) active=tab
        }
        sizeGroupTabs()
        reorder.prune()
        strip.doOnPreDraw {
            scroll.scrollTo(previous,0)
            if(selectionChanged) active?.let {
                scroll.smoothScrollTo((it.left-(scroll.width-it.width)/2).coerceAtLeast(0),0)
            }
            updateGroupIndicators()
        }
    }
    private var chromeKey: String? = null
    private fun tilesAtBottom()=MobileHomeLayout.tilesBottom
    /** Every tile, or only the corner ones. */
    private fun extendedTiles()=MobileHomeLayout.extendedTiles
    private data class ProfileContent(val id: Long, val type: String, val name: String?, val json: String)
    private data class GroupContent(val group: ProxyGroup, val profiles: List<ProfileContent>, val collapsed: Boolean, val page: Int, val colors: List<Int>)
    private val groupViews = mutableMapOf<Long, Pair<GroupContent, View>>()
    private val profilePages = mutableMapOf<Long, Int>()
    private val refreshButtons = mutableMapOf<Long, ImageButton>()
    private var renderedSelectedProxy = Long.MIN_VALUE
    private var observer: InvalidationTracker.Observer? = null
    private var openSwipe: MobileSwipeTile? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun tile(selected: Boolean = false): android.graphics.drawable.Drawable {
        if(MobileStyle.new) return if(selected) MobilePalette.selectedShape(requireContext(),blue) else MobilePalette.rowShape(requireContext())
        val shape = GradientDrawable().apply {
            setColor(if (selected) androidx.core.graphics.ColorUtils.blendARGB(surface,blue,.12f) else surface)
            cornerRadius=dp(MobilePalette.rowDp).toFloat()
            if(!MobileStyle.new) setStroke(if(selected) dp(1) else 1, if (selected) blue else MobilePalette.outline)
        }
        return shape
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
        contentDescription=description; setOnClickListener { callback() }
        if(MobileStyle.new && color!=Color.TRANSPARENT) {
            background=MobilePalette.actionShape(context,color)
            imageTintList=android.content.res.ColorStateList.valueOf(color)
            MobileFeedback.apply(this)
            // A background's insets replace the view's padding.
            setPadding(dp(20),dp(12),dp(20),dp(12))
        } else MobileFeedback.apply(this, circle=true)
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
    /** The server's country flag, any flag at the start of its name, or a globe. */
    private fun flagBadge(displayName: String): ImageView {
        val first=if(displayName.isNotEmpty()) displayName.codePointAt(0) else 0
        val firstLength=Character.charCount(first)
        val second=if(displayName.length>firstLength) displayName.codePointAt(firstLength) else 0
        val hasFlag=first in 0x1f1e6..0x1f1ff && second in 0x1f1e6..0x1f1ff
        val leadingFlag=MobileCountryFlags.leading(displayName)
        val countryCode=if(hasFlag) "${('a'.code+first-0x1f1e6).toChar()}${('a'.code+second-0x1f1e6).toChar()}" else ""
        val flag=(if(hasFlag) MobileCountryFlags.bitmap(requireContext(),countryCode) else null)
            ?: if(leadingFlag.isNotEmpty()) MobileCountryFlags.emoji(requireContext(),leadingFlag) else null
        return ImageView(requireContext()).apply {
            if(flag!=null) {
                setImageBitmap(flag);scaleType=ImageView.ScaleType.FIT_XY
                contentDescription="Flag: ${countryCode.uppercase(java.util.Locale.ROOT).ifEmpty { leadingFlag }}"
            } else {
                setImageResource(R.drawable.ic_mobile_globe)
                imageTintList=android.content.res.ColorStateList.valueOf(muted)
                setPadding(dp(5),dp(5),dp(5),dp(5))
            }
            background=GradientDrawable().apply { setColor(toolbarColor);cornerRadius=dp(if(MobileStyle.new) 10 else 7).toFloat() };clipToOutline=true
            // White in a flag would melt into a light theme without an edge, and black into a dark one.
            if(MobileStyle.new && flag!=null) foreground=GradientDrawable().apply { cornerRadius=dp(10).toFloat();setStroke(dp(1).coerceAtLeast(1),androidx.core.graphics.ColorUtils.setAlphaComponent(MobilePalette.muted,if(MobilePalette.light) 70 else 110)) }
        }
    }
    private var groupChip: TextView? = null
    private var groupTabs: View? = null
    private var pickerGroups: List<ProxyGroup> = emptyList()
    private var sheetDown: TextView? = null
    private var sheetUp: TextView? = null
    private var sheetSpeed: View? = null
    private var lastDown = 0L
    private var lastUp = 0L
    private fun rateText(value: Long) = android.text.format.Formatter.formatShortFileSize(requireContext(),value)+"/s"
    private fun revealGroupTabs(show: Boolean) {
        val tabs=groupTabs ?: return
        android.animation.ValueAnimator.ofInt(tabs.height,if(show) dp(60) else 0).apply {
            duration=220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener { tabs.layoutParams=tabs.layoutParams.apply { height=it.animatedValue as Int } }
            start()
        }
    }
    /** The new style's subscriptions: one sheet to switch between them, add one, or manage them. */
    private fun groupPicker() {
        val sheet=MobileSheetDialog(requireContext())
        val current=renderedGroupId
        val body=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
        val handle=View(requireContext()).apply { background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(2).toFloat() } }
        body.addView(handle,LinearLayout.LayoutParams(dp(36),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(14) })
        val list=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
        val scroller=androidx.core.widget.NestedScrollView(requireContext()).apply { isVerticalScrollBarEnabled=false;addView(list) }
        val drag=MobileReorder(list,scroller,{ reorderActive(it) },{ saveReorder(null,it) },{ first,second -> (first in pinnedGroups())==(second in pinnedGroups()) })
        var opened: MobileSwipeTile?=null
        pickerGroups.forEachIndexed { index,group ->
            val row=LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(60);setPadding(dp(16),dp(8),dp(12),dp(8))
                background=tile(group.id==current);isFocusable=true
                addView(text(group.displayName(),15f).apply { setTypeface(typeface,Typeface.BOLD);setPadding(0,0,0,dp(2));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
                addView(text(getString(if(group.isSubscription) R.string.subscription else R.string.group_basic),12f,muted).apply { setPadding(0,0,0,0) })
                setOnClickListener { sheet.dismiss();selectGroup(group.id) }
                MobileFeedback.apply(this)
            }
            val swipe=MobileSwipeTile(requireContext(),row,null,
                iconAction(R.drawable.ic_mobile_copy_outline,getString(R.string.action_copy),Color.rgb(60,132,190)) { opened?.let { shareGroup(group,it) } },
                iconAction(R.drawable.ic_mobile_delete,getString(R.string.qmobile_delete),Color.rgb(220,66,61)) {
                    val tile=opened
                    viewLifecycleOwner.lifecycleScope.launch {
                        val deleted=withContext(Dispatchers.IO) { GroupRepo.delete(group.id,groupAfterRemoval(renderedGroupOrder,group.id)) }
                        if(!deleted) { Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show();tile?.closeActions() }
                        else {
                            list.removeView(tile);drag.prune()
                            if(list.childCount==0) sheet.dismiss()
                        }
                    }
                }).apply { onOpened={ tile -> opened?.takeIf { it!==tile }?.closeActions();opened=tile } }
            drag.bind(row,swipe,group.id)
            list.addView(swipe,LinearLayout.LayoutParams(-1,-2).apply { if(index>0) topMargin=dp(6) })
        }
        body.addView(scroller,LinearLayout.LayoutParams(-1,-2,1f))
        body.addView(LinearLayout(requireContext()).apply {
            isBaselineAligned=false
            fun action(label: String, callback: (View) -> Unit)=text(label,14f).apply {
                gravity=Gravity.CENTER;minHeight=dp(52);setPadding(dp(10),dp(8),dp(10),dp(8));background=tile();isFocusable=true
                setOnClickListener(callback);MobileFeedback.apply(this)
            }
            addView(action(getString(R.string.qmobile_add_group)) { sheet.dismiss();addSubscription() },LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(6) })
            addView(action(getString(R.string.qmobile_manage_groups)) { sheet.dismiss();host().displayFragmentWithId(R.id.nav_group) },LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=dp(6) })
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        sheet.setContentView(body)
        sheet.show()
    }
    private var routeChipView: TextView?=null
    /** The routing profile beside the subscription: the same card, its own sheet. */
    private fun routeChip()=text("",16f).apply {
        setTypeface(typeface,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(14),0,dp(10),0);background=tile();isFocusable=true;compoundDrawablePadding=dp(8)
        contentDescription=getString(R.string.qmobile_route_chip)
        val icon=androidx.core.content.ContextCompat.getDrawable(requireContext(),R.drawable.ic_mobile_route)?.mutate()?.apply { setTint(foregroundColor);setBounds(0,0,dp(20),dp(20)) }
        val arrow=androidx.core.content.ContextCompat.getDrawable(requireContext(),R.drawable.ic_mobile_chevron)?.mutate()?.apply { setBounds(0,0,dp(22),dp(22)) }
        setCompoundDrawablesRelative(icon,null,android.graphics.drawable.RotateDrawable().apply { drawable=arrow;fromDegrees=90f;toDegrees=90f;level=1;setBounds(0,0,dp(22),dp(22)) },null)
        setOnClickListener { routePicker() };MobileFeedback.apply(this)
        routeChipView=this
        viewLifecycleOwner.lifecycleScope.launch {
            val name=withContext(Dispatchers.IO) { runCatching { RouteManager.current().name }.getOrNull() }
            if(name!=null) text=name
        }
    }
    /** The routing profiles as the subscriptions' sheet shows them: switch, add, manage; hold one to move it, in the same order as the routing settings. */
    private fun routePicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val routes=withContext(Dispatchers.IO) { RouteManager.usable() }
            if(!isAdded) return@launch
            val sheet=MobileSheetDialog(requireContext())
            val body=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
            val handle=View(requireContext()).apply { background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(2).toFloat() } }
            body.addView(handle,LinearLayout.LayoutParams(dp(36),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(14) })
            val list=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
            val scroller=androidx.core.widget.NestedScrollView(requireContext()).apply { isVerticalScrollBarEnabled=false;addView(list) }
            val drag=MobileReorder(list,scroller,{},{ ids ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // Profiles the sheet does not show keep their places.
                    val moved=ids.iterator()
                    RouteManager.saveOrder(RouteManager.all().map { if(it.id in ids && moved.hasNext()) moved.next() else it.id })
                }
            })
            routes.forEachIndexed { index,route ->
                val row=LinearLayout(requireContext()).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(60);setPadding(dp(16),dp(8),dp(12),dp(8))
                    background=tile(route.id==DataStore.currentRouteId);isFocusable=true
                    addView(text(route.name,15f).apply { setTypeface(typeface,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
                    if(route.id==DataStore.currentRouteId) addView(text(getString(R.string.route_profile_current),12f,muted))
                    setOnClickListener {
                        sheet.dismiss()
                        app.rawline.mobile.ui.route.RouteQuickSwitch.switchTo(route.id)
                        app.rawline.mobile.ui.route.RouteQuickSwitch.switched(this@MobileHomeFragment,route.name)
                        routeChipView?.text=route.name
                    }
                    MobileFeedback.apply(this)
                }
                drag.bind(row,row,route.id)
                list.addView(row,LinearLayout.LayoutParams(-1,-2).apply { if(index>0) topMargin=dp(6) })
            }
            body.addView(scroller,LinearLayout.LayoutParams(-1,-2,1f))
            body.addView(LinearLayout(requireContext()).apply {
                isBaselineAligned=false
                fun action(label: String, callback: (View) -> Unit)=text(label,14f).apply {
                    gravity=Gravity.CENTER;minHeight=dp(52);setPadding(dp(10),dp(8),dp(10),dp(8));background=tile();isFocusable=true
                    setOnClickListener(callback);MobileFeedback.apply(this)
                }
                addView(action(getString(R.string.qmobile_route_profile_add)) {
                    sheet.dismiss();startActivity(Intent(requireContext(),app.rawline.mobile.ui.route.RouteProfileActivity::class.java))
                },LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(6) })
                addView(action(getString(R.string.qmobile_route_manage)) { sheet.dismiss();host().displayFragmentWithId(R.id.nav_route) },LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=dp(6) })
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            sheet.setContentView(body)
            sheet.show()
        }
    }
    /** Live speed in the closed sheet, like the classic notification shows it. */
    fun updateSpeed(down: Long, up: Long) {
        if(!isAdded) return
        lastDown=down;lastUp=up
        sheetSpeed?.visibility=View.VISIBLE
        sheetDown?.text="↓ "+rateText(down)
        sheetUp?.text="↑ "+rateText(up)
    }
    private var risenGroup = Long.MIN_VALUE
    private fun powerCovered(): Boolean {
        return power?.hasWindowFocus()==false ||
            android.os.SystemClock.uptimeMillis()-MobileSheetDialog.closedAt<350
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
        scroll.setOnDragListener { _,event ->
            val state=event.localState as? MobileReorder ?: return@setOnDragListener false
            when(event.action) {
                DragEvent.ACTION_DRAG_STARTED -> if(state.movable && (DataStore.mobileListMode=="groups" || MobileStyle.new)) {
                    activeProfileDrag=state
                    revealGroupTabs(true)
                    groupDragPreview.start(renderedGroupId,serverScrollView?.scrollY ?: 0)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    revealGroupTabs(false)
                    stopGroupScroll()
                    state.endDrag(event.result)
                }
            }
            true
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        return scroll
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        androidx.core.content.ContextCompat.registerReceiver(requireContext(),nativeStatusReceiver,
            android.content.IntentFilter(app.rawline.mobile.bg.carrier.NativeConnectionStatus.action()),requireContext().packageName+".SERVICE",null,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        nativeReceiverRegistered=true
        // The screen appears whole, not piece by piece: nothing is drawn before its first render, for a second at most.
        val shownAt=android.os.SystemClock.uptimeMillis()
        view.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if(renderedStructure==null && !isHidden && android.os.SystemClock.uptimeMillis()-shownAt<1000) return false
                view.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        readNativeStatus()
        collapsed.clear()
        collapsed.addAll(requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).getStringSet("collapsedGroups",emptySet()).orEmpty().mapNotNull { it.toLongOrNull() })
        state?.getLongArray("knownGroups")?.let { knownGroups.addAll(it.toList()) }
        state?.let { saved -> saved.getLongArray("pageGroups")?.zip(saved.getIntArray("profilePages")?.toList().orEmpty())?.let { profilePages.putAll(it) } }
        state?.let { saved -> saved.getLongArray("scrollGroups")?.zip(saved.getIntArray("groupScrollPositions")?.toList().orEmpty())?.let { groupScrollPositions.putAll(it) } }
        observer = object : InvalidationTracker.Observer("groups", "profiles") {
            override fun onInvalidated(tables: Set<String>) = scheduleRefresh()
        }.also { SagerDatabase.instance.invalidationTracker.addObserver(it) }
        GroupRepo.addListener(groupListener)
        viewLifecycleOwner.lifecycleScope.launch { SubscriptionClient.states.collect { updateSubscriptionButtons() } }
        viewLifecycleOwner.lifecycleScope.launch { app.rawline.mobile.route.ProviderGeoAssets.states.collect { updateGeoStatus() } }
        viewLifecycleOwner.lifecycleScope.launch { UpdateDiscovery.available.collect { updateUpdatesBadge();updateReleaseBanner() } }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.queuedProfiles.collect { updateLatencies() } }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.state.collect {
            updateLatencies()
            if(currentTestArmed && DataStore.serviceState.connected && it.testCurrent && !it.running) {
                val ping=it.rows.values.firstOrNull { row -> row.ok && row.latency>0 }?.latency
                // The number stays with its unit when the line wraps.
                currentPing=if(ping!=null && !it.cancelled) getString(R.string.qmobile_ping_success,ping).let { text -> text.substringBeforeLast(' ')+'\u00A0'+text.substringAfterLast(' ') } else getString(R.string.qmobile_unavailable)
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
        outState.putLongArray("scrollGroups",groupScrollPositions.keys.toLongArray())
        outState.putIntArray("groupScrollPositions",groupScrollPositions.values.toIntArray())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); refresh(); readNativeStatus() }
    override fun onPause() {
        activeProfileDrag?.cancel()
        groupReorder?.cancel();profileReorders.values.forEach { it.cancel() }
        load?.cancel()
        powerPulse?.cancel(); powerPulse=null; pulseTarget=null
        power?.scaleX=1f; power?.scaleY=1f
        super.onPause()
    }
    /** Broadcasts only carry changes, so a screen made anew starts from what the service last wrote down. */
    private fun readNativeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshots=withContext(Dispatchers.IO) { app.rawline.mobile.bg.carrier.NativeConnectionStatus.read() to app.rawline.mobile.bg.carrier.NativeConnectionStatus.read(true) }
            nativeConnection=snapshots.first;nativeTest=snapshots.second;updateState()
        }
    }
    override fun onDestroyView() {
        activeProfileDrag?.cancel()
        if(nativeReceiverRegistered) { requireContext().unregisterReceiver(nativeStatusReceiver);nativeReceiverRegistered=false }
        groupReorder?.cancel();groupReorder=null;profileReorders.values.forEach { it.cancel() };profileReorders.clear();reordering=false
        groupChip = null; groupTabs = null; sheetDown = null; sheetUp = null
        geoBanner = null; updatesTile = null; updateBanner = null; geoStatus = null; geoProgress = null; geoCheck = null
        GroupRepo.removeListener(groupListener)
        observer?.let { SagerDatabase.instance.invalidationTracker.removeObserver(it) }
        observer = null; load?.cancel(); content = null; powerPulse?.cancel();powerPulse=null;pulseTarget=null; power = null
        currentResultJob?.cancel(); currentResultJob=null
        if(!currentTestArmed) currentPing=null
        openSwipe=null; renderedStructure=null
        stopGroupScroll()
        serverScrollView=null; serverRows=null; chromeKey=null; subscriptionTop=null
        serverHeader=null; groupHeads.clear()
        groupStrip?.removeCallbacks(previewHoveredGroup)
        groupStrip=null;groupStripScroll=null;groupLeftIndicator=null;groupRightIndicator=null;serverDropArea=null;renderedGroupId=0L
        pendingMovedProfile=null;renderedGroupOrder=emptyList()
        groupViews.clear(); refreshButtons.clear(); latencyViews.clear(); profileRows.clear()
        super.onDestroyView()
    }
    private val profileRows=mutableMapOf<Long,View>()
    private var serverScrollPosition = 0
    private var renderedStructure: List<GroupContent>? = null
    private fun ViewGroup.childrenList(): List<View> = (0 until childCount).map { getChildAt(it) }
    fun refresh() {
        val previewGroup=groupDragPreview.previewGroupId
        if (content == null || !isAdded || !isResumed || isHidden || profileTransferPending ||
            (reordering && (previewGroup==null || previewGroup==renderedGroupId))) return
        if(load?.isActive==true) { refreshPending=true;return }
        refreshPending=false
        readPalette()
        val colors=listOf(surface,blue,foregroundColor,muted,toolbarColor,backgroundColor)
        val collapsedSnapshot=collapsed.toSet()
        val pageSnapshot=profilePages.toMap()
        val previousStructure=renderedStructure
        val byGroups=DataStore.mobileListMode=="groups" || MobileStyle.new
        load = viewLifecycleOwner.lifecycleScope.launch {
            var completed=false
            val nextLatencyViews=latencyViews.toMutableMap()
            val nextProfileRows=profileRows.toMutableMap()
            val nextGroupViews=groupViews.toMutableMap()
            val nextRefreshButtons=refreshButtons.toMutableMap()
            val nextProfileReorders=profileReorders.toMutableMap()
            try {
            val selected=withContext(Dispatchers.IO) { DataStore.selectedProxy }
            val focusedGroup=withContext(Dispatchers.IO) { if(byGroups) previewGroup ?: GroupRepo.currentId() else 0L }
            val (groups,structure,windows)=withContext(Dispatchers.IO) {
                val pinned=pinnedGroups()
                val groups=GroupRepo.all().sortedBy { if(it.id in pinned) 0 else 1 }.map {
                    it to if(!byGroups || it.id==focusedGroup) SagerDatabase.proxyDao.getByGroup(it.id) else emptyList()
                }.filterNot { (group,profiles) ->
                    !byGroups && !group.isSubscription && group.name==SagerDatabase.defaultGroupName() && profiles.isEmpty()
                }
                val windows=groups.associate { (group,profiles) -> group.id to MobileProfilePaging.window(profiles.map { it.id },pageSnapshot[group.id],selected) }
                val keys=groups.map { (group,profiles) -> GroupContent(group.copy(subOptions=group.subOptions.copy()),profiles.map { ProfileContent(it.id,it.type,it.name,it.outboundJson) },!byGroups && group.subOptions.allowCollapse && group.id in collapsedSnapshot,windows.getValue(group.id).page,colors) }
                // Parse only displayed profiles off the UI thread. A collapsed subscription creates no rows.
                if(previousStructure != keys) groups.forEach { (group,profiles) ->
                    if((!byGroups || group.id==focusedGroup) && (byGroups || !group.subOptions.allowCollapse || group.id !in collapsedSnapshot)) {
                        val window=windows.getValue(group.id)
                        profiles.subList(window.start,window.end).forEach { it.displayName();it.displayProtocol() }
                    }
                }
                Triple(groups,keys,windows)
            }
            if((reordering && previewGroup==null) || (previewGroup!=null && previewGroup!=groupDragPreview.previewGroupId)) return@launch
            val column = content ?: return@launch
            groups.forEach { (group, _) -> knownGroups.add(group.id) }
            val tilesBottom=tilesAtBottom()
            val tilesKey="${extendedTiles()}:$tilesBottom:$byGroups:${homeSwitchesOn()}:${controlAtBottom()}:${chipAtBottom()}:${chipNearList()}:${MobileHomeLayout.extendedChip}"
            // A moved tile bar rebuilds the screen even when the server list is the same.
            val unchanged=withContext(Dispatchers.Default) { previousStructure == structure } && renderedGroupId==focusedGroup && chromeKey?.endsWith(":$tilesKey")!=false
            if (unchanged) {
                groups.forEach { (_, profiles) -> profiles.forEach { profile ->
                    nextLatencyViews[profile.id]?.tag = profile.latency
                } }
                updateLatencies()
                updateState()
                completed=true
                return@launch
            }
            // Both styles and both list modes: nothing to show is the lone server list with nothing in it.
            val hasServers = (byGroups && groups.size>1) || groups.any { it.second.isNotEmpty() || it.first.isSubscription }
            val hwid = app.rawline.mobile.group.RequestIdentity.applyCustomHwidParams(app.rawline.mobile.group.DeviceDetails.get(),DataStore.subCustomHwidParams).hwid
            val nextChromeKey="$surface:$blue:$hasServers:$hwid:$tilesKey"
            if(chromeKey != nextChromeKey) {
                chromeKey=nextChromeKey
                groupReorder=null;nextProfileReorders.clear()
                groupStrip=null;groupStripScroll=null;groupLeftIndicator=null;groupRightIndicator=null;serverDropArea=null
                column.removeAllViews(); nextLatencyViews.clear(); nextProfileRows.clear(); nextGroupViews.clear(); nextRefreshButtons.clear()
                // Landscape: the header and the power button in a side column, so the server list keeps the full height.
                val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
                column.orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                val side0=if(landscape) LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL } else column
                val body=if(landscape) LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL } else column
                val side=side0
                sheetSpeed=null
                if(landscape) {
                    column.addView(ScrollView(requireContext()).apply { isVerticalScrollBarEnabled=false;addView(side,FrameLayout.LayoutParams(-1,-2)) },LinearLayout.LayoutParams(dp(280),-1))
                    column.addView(body,LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=dp(12) })
                }
                openSwipe=null
                val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                fun headerIcon(icon: Int, description: String, callback: (View) -> Unit) = ImageButton(requireContext()).apply {
                    setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(foregroundColor); background = tile(); setPadding(dp(12),dp(12),dp(12),dp(12))
                    contentDescription = description; androidx.appcompat.widget.TooltipCompat.setTooltipText(this,description); setOnClickListener { callback(it) }; MobileFeedback.apply(this)
                }
                val extended=extendedTiles()
                updatesTile=null;updateBanner=null;homeTitle=null;modeSwitches=emptyMap();groupChip=null
                val settingsTile=headerIcon(R.drawable.ic_mobile_settings, getString(R.string.qmobile_settings)) { host().displayFragmentWithId(R.id.nav_settings) }
                val addTile=headerIcon(R.drawable.ic_mobile_add, getString(R.string.qmobile_add)) { addMenu(it) }
                val quick = if(extended) listOf(
                    headerIcon(R.drawable.ic_mobile_tun, getString(R.string.service_mode)) { modeMenu(it) },
                    headerIcon(R.drawable.ic_mobile_route, getString(R.string.route_profile_quick_switch)) { routingMenu(it) },
                    headerIcon(R.drawable.ic_mobile_subscriptions, getString(R.string.qmobile_manage_groups)) { subscriptionsMenu(it) },
                    headerIcon(R.drawable.ic_mobile_update, getString(R.string.qmobile_updates)) { updatesMenu(it) }.also { updatesTile=it;updateUpdatesBadge() },
                ) else emptyList()
                // The new style has no tile bar: a corner menu reaches the same four menus, adding sits with the subscriptions.
                val quickMenu=if(MobileStyle.new && !extended) headerIcon(R.drawable.ic_mobile_more, getString(R.string.qmobile_quick_menu)) { anchor ->
                    val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
                    popup.menu.add(R.string.service_mode).setOnMenuItemClickListener { modeMenu(anchor); true }
                    popup.menu.add(R.string.route_profile_quick_switch).setOnMenuItemClickListener { routingMenu(anchor); true }
                    popup.menu.add(R.string.qmobile_manage_groups).setOnMenuItemClickListener { subscriptionsMenu(anchor); true }
                    popup.menu.add(R.string.qmobile_updates).setOnMenuItemClickListener { updatesMenu(anchor); true }
                    popup.show()
                }.also { updatesTile=it;updateUpdatesBadge() } else null
                val tiles = if(quickMenu!=null) listOf(quickMenu, settingsTile) else if(extended) listOf(settingsTile) + quick + (if(MobileStyle.new) emptyList() else listOf(addTile)) else listOf(settingsTile, addTile)
                tiles.forEachIndexed { index, button ->
                    header.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index < tiles.lastIndex && extended) marginEnd = dp(8)
                    })
                    // Standard: the two tiles sit in the corners.
                    if (!extended && index == 0) header.addView(FrameLayout(requireContext()).apply {
                        homeTitle=text(getString(R.string.app_name),18f).apply { setTypeface(typeface,Typeface.BOLD); gravity=Gravity.CENTER; setPadding(0,0,0,0); maxLines=1 }
                        addView(homeTitle,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                        if(!extended && !MobileStyle.new) updateBanner = text("", 12f, blue).apply {
                            gravity = Gravity.CENTER
                            minHeight = dp(48)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            isFocusable = true
                            // Compact artwork inside a full 48dp touch target.
                            background = android.graphics.drawable.InsetDrawable(tile(selected = true), 0, dp(7), 0, dp(7))
                            val icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_update)
                            icon?.setBounds(0, 0, dp(18), dp(18))
                            setCompoundDrawablesRelative(icon, null, null, null)
                            compoundDrawablePadding = dp(5)
                            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(blue))
                            MobileFeedback.apply(this)
                            // Set this after the background, whose inset padding replaces view padding.
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        }
                        if(!extended && !MobileStyle.new) { addView(updateBanner, FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER)); updateReleaseBanner() }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
                }
                header.gravity = Gravity.CENTER
                header.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left != oldRight - oldLeft) {
                        val size = minOf(dp(48), ((right - left - dp(8) * (tiles.size - 1)) / tiles.size).coerceAtLeast(1))
                        tiles.forEach { button ->
                            button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                                width = size; height = size; weight = 0f
                            }
                        }
                    }
                }
                if(!tilesBottom) side.addView(header, LinearLayout.LayoutParams(-1, -2))
                // The subscription in use, as one card with a list behind it, and adding beside it.
                val chipBottom=chipAtBottom() && !landscape
                val chipRow=if(MobileStyle.new) LinearLayout(requireContext()).apply {
                    gravity=Gravity.CENTER_VERTICAL
                    groupChip=text("",16f).apply {
                        setTypeface(typeface,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
                        setPadding(dp(16),0,dp(12),0);background=tile();isFocusable=true
                        val arrow=androidx.core.content.ContextCompat.getDrawable(requireContext(),R.drawable.ic_mobile_chevron)?.mutate()
                        arrow?.setBounds(0,0,dp(22),dp(22))
                        setCompoundDrawablesRelative(null,null,android.graphics.drawable.RotateDrawable().apply { drawable=arrow;fromDegrees=90f;toDegrees=90f;level=1;setBounds(0,0,dp(22),dp(22)) },null)
                        setOnClickListener { groupPicker() };MobileFeedback.apply(this)
                    }
                    if(MobileHomeLayout.extendedChip) addView(routeChip(),LinearLayout.LayoutParams(0,dp(48),.8f).apply { marginEnd=dp(8) })
                    addView(groupChip,LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(8) })
                    addView(addTile,LinearLayout.LayoutParams(dp(48),dp(48)))
                } else null
                val chipNear=chipNearList()
                if(!chipBottom && !chipNear) chipRow?.let { side.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) }) }
                val centre = FrameLayout(requireContext())
                var statusRow: LinearLayout?=null
                val dockBottom=controlAtBottom() && !landscape
                val controlParent=if(dockBottom) LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL } else side
                var dockLeft: FrameLayout?=null
                if(homeSwitchesOn()) {
                    val rows=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                    // Side by side in one row: the server list keeps the room the button took.
                    val pair=LinearLayout(requireContext()).apply { isBaselineAligned=false;if(landscape) orientation=LinearLayout.VERTICAL }
                    val switches=listOf(Key.MODE_VPN to R.string.service_mode_vpn,Key.MODE_PROXY to R.string.service_mode_proxy).mapIndexed { index,(mode,label) ->
                        // The switch only shows the state of the service, so a declined VPN request leaves it off.
                        val switch=androidx.appcompat.widget.SwitchCompat(requireContext()).apply { isClickable=false;isFocusable=false }
                        pair.addView(LinearLayout(requireContext()).apply {
                            gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(52);setPadding(dp(14),dp(2),dp(8),dp(2));background=tile();isFocusable=true
                            addView(text(getString(label),14f).apply { setPadding(0,dp(4),dp(6),dp(4)) },LinearLayout.LayoutParams(0,-2,1f))
                            addView(switch)
                            setOnClickListener { pickMode(mode) }
                            MobileFeedback.apply(this)
                        },(if(landscape) LinearLayout.LayoutParams(-1,-2).apply { if(index>0) topMargin=dp(6) } else LinearLayout.LayoutParams(0,-1,1f).apply { if(index>0) marginStart=dp(8) }))
                        mode to switch
                    }
                    rows.addView(pair,LinearLayout.LayoutParams(-1,-2))
                    modeSwitches=switches.toMap()
                    power=text("",13f,muted).apply { gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
                    rows.addView(power,LinearLayout.LayoutParams(-1,-2))
                    controlParent.addView(rows,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(if(dockBottom) 2 else 10) })
                } else {
                power = action("") { if(!powerCovered()) host().toggleService() }.apply {
                    contentDescription = getString(R.string.qmobile_connect)
                    background = if(MobileStyle.new) MobileOrbArtwork(requireContext(),getString(R.string.qmobile_orb_idle)) else MobilePowerArtwork(blue)
                    // A remote or keyboard has no ripple to show where it stands.
                    if(MobileStyle.new) setOnFocusChangeListener { button,focused -> (button.background as? MobileOrbArtwork)?.focused=focused }
                    var pressAnimator: android.animation.ValueAnimator? = null
                    setOnTouchListener { _, event ->
                        val artwork=background as? MobilePowerArtwork
                        val target=when(event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> if(MobileStyle.new) .92f else .94f
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
                if(MobileStyle.new) {
                    // A smaller button right under the subscription: the servers and the subscription's actions keep the screen.
                    centre.addView(power, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                    if(dockBottom) centre.background=MobileDockGlass(MobilePalette.cardDp*resources.displayMetrics.density,MobileOrbArtwork.centerY(dp(190)),MobileOrbArtwork.radius(dp(190))*1.26f,dp(104).toFloat())
                    controlParent.addView(centre, LinearLayout.LayoutParams(-1, dp(if(dockBottom) 194 else 190)).apply { topMargin=dp(4) })
                } else {
                centre.addView(power, FrameLayout.LayoutParams(dp(188), dp(188), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(8) })
                controlParent.addView(centre, LinearLayout.LayoutParams(-1, dp(206)))
                }
                }
                if(MobileStyle.new) {
                    sheetDown=text("↓ "+rateText(lastDown),12f,muted).apply { setPadding(dp(6),dp(2),dp(6),dp(2));maxLines=1 }
                    sheetUp=text("↑ "+rateText(lastUp),12f,muted).apply { setPadding(dp(6),dp(2),dp(6),dp(2));maxLines=1 }
                    // A fixed height: one line or two beside the speed, the list under it stays put.
                    if(dockBottom && !homeSwitchesOn()) {
                        // Either side of the caption in the panel, the same distance from its edges.
                        dockLeft=FrameLayout(requireContext())
                        centre.addView(dockLeft,FrameLayout.LayoutParams(dp(92),-2,Gravity.START or Gravity.BOTTOM).apply { marginStart=dp(14);bottomMargin=dp(14) })
                        val speed=LinearLayout(requireContext()).apply {
                            orientation=LinearLayout.VERTICAL
                            for(label in listOf(sheetDown,sheetUp)) { label?.gravity=Gravity.END or Gravity.CENTER_VERTICAL;addView(label,LinearLayout.LayoutParams(-1,dp(22))) }
                        }
                        sheetSpeed=speed
                        centre.addView(speed,FrameLayout.LayoutParams(dp(92),-2,Gravity.END or Gravity.BOTTOM).apply { marginEnd=dp(14);bottomMargin=dp(14) })
                    } else {
                    // Speed changes every second: in fixed boxes that repaints two labels, not the layout of the whole screen.
                    statusRow=LinearLayout(requireContext()).apply {
                        gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(6),0)
                        for(label in listOf(sheetDown,sheetUp)) { label?.gravity=Gravity.END or Gravity.CENTER_VERTICAL;addView(label,LinearLayout.LayoutParams(dp(84),dp(24))) }
                    }
                    sheetSpeed=statusRow
                    // At the bottom the check and speed stay above the switches, next to the list.
                    if(dockBottom) controlParent.addView(statusRow,0,LinearLayout.LayoutParams(-1,dp(40)))
                    else side.addView(statusRow,LinearLayout.LayoutParams(-1,dp(40)))
                    }
                }
                updateState()
                val imports = LinearLayout(requireContext())
                fun importButton(label: String, icon: Int, callback: () -> Unit) = LinearLayout(requireContext()).apply {
                    gravity=Gravity.CENTER; isFocusable=true; contentDescription=label
                    setOnClickListener { callback() }
                    background=tile()
                    addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(foregroundColor); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
                    addView(text(label,14f).apply { setPadding(0,0,0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
                imports.addView(importButton(getString(R.string.qmobile_clipboard), R.drawable.ic_mobile_clipboard) { importClipboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                imports.addView(importButton(getString(R.string.qmobile_qr), R.drawable.ic_mobile_qr) { startActivity(Intent(requireContext(), ScannerActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
                if (hasServers) {
                    val above=FrameLayout(requireContext())
                    if(!MobileStyle.new) above.addView(MobileSeasonalView(requireContext()),FrameLayout.LayoutParams(-1,dp(64)))
                    currentCheck=text(currentPing ?: getString(R.string.qmobile_check_connection),12f,muted).apply {
                        // Text taller than its box gets a scroll bar on some phones, so it always fits.
                        maxLines=2;includeFontPadding=false;setPadding(dp(2),0,dp(2),0);gravity=Gravity.BOTTOM
                        isFocusable=true
                        tag=MobileFeedback.NONE
                        setOnClickListener { checkCurrentConnection() }
                    }
                    above.addView(currentCheck,FrameLayout.LayoutParams(-2,-2,if(MobileStyle.new) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.BOTTOM).apply { if(!MobileStyle.new) bottomMargin=dp(6) })
                    subscriptionTop=above
                    above.layoutParams=LinearLayout.LayoutParams(-1,dp(64))
                    updateState()
                } else { currentCheck=null;subscriptionTop=null }
                geoBanner = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); minimumHeight = dp(48)
                    background = tile()
                    visibility = View.GONE
                    // A tap hides the card; a new download shows it again.
                    contentDescription = getString(R.string.update_action_hide)
                    setOnClickListener { app.rawline.mobile.route.ProviderGeoAssets.dismiss() }
                }
                geoStatus = text("",14f).apply { setPadding(0,0,dp(12),0) }
                geoProgress = ProgressBar(requireContext()).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(blue); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoCheck = ImageView(requireContext()).apply { setImageResource(R.drawable.ic_baseline_check_circle_24); imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76,175,80)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoBanner!!.addView(geoStatus,LinearLayout.LayoutParams(0,-2,1f))
                geoBanner!!.addView(geoProgress,LinearLayout.LayoutParams(dp(24),dp(24)))
                geoBanner!!.addView(geoCheck,LinearLayout.LayoutParams(dp(24),dp(24)))
                // Under the decoration, right above the subscriptions it is about.
                // In the new style the check sits beside the speed, under the button.
                subscriptionTop?.let { if(dockLeft!=null) { currentCheck?.maxLines=3; it.layoutParams=FrameLayout.LayoutParams(-1,-2); dockLeft?.addView(it) } else if(statusRow!=null) { it.layoutParams=LinearLayout.LayoutParams(0,-2,1f); statusRow?.addView(it,0) } else body.addView(it) }
                if(!chipBottom && chipNear) chipRow?.let { side.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6);bottomMargin=dp(6) }) }
                if(!byGroups) body.addView(geoBanner,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                updateGeoStatus()
                val serverPanel=if(byGroups) LinearLayout(requireContext()).apply {
                    orientation=LinearLayout.VERTICAL;if(!MobileStyle.new) background=tile();clipToOutline=true
                } else body
                if(byGroups) {
                    val area=FrameLayout(requireContext()).also { serverDropArea=it }
                    area.addView(serverPanel,FrameLayout.LayoutParams(-1,-2))
                    body.addView(area,LinearLayout.LayoutParams(-1,0,1f))
                    val tabs=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL;if(MobileStyle.new) setPadding(0,dp(6),0,0) }
                    fun indicator(left: Boolean)=ImageView(requireContext()).apply {
                        setImageResource(R.drawable.ic_mobile_chevron)
                        imageTintList=android.content.res.ColorStateList.valueOf(blue)
                        rotation=if(left) 180f else 0f
                        scaleType=ImageView.ScaleType.CENTER_INSIDE
                        alpha=.35f
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    groupLeftIndicator=indicator(true)
                    groupRightIndicator=indicator(false)
                    val strip=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL }
                    val stripScroll=HorizontalScrollView(requireContext()).apply {
                        isHorizontalScrollBarEnabled=false;isFillViewport=true
                        addView(strip,FrameLayout.LayoutParams(-2,-2))
                        val viewport=this
                        val taps=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapUp(event:MotionEvent):Boolean {
                                if(reordering) return false
                                val x=viewport.scrollX+event.x
                                return strip.childrenList().firstOrNull { x>=it.left && x<it.right }?.performClick() ?: false
                            }
                        })
                        setOnTouchListener { _,event -> taps.onTouchEvent(event);false }
                        setOnScrollChangeListener { _,_,_,_,_ -> updateGroupIndicators() }
                        addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_ ->
                            if(right-left!=oldRight-oldLeft) sizeGroupTabs()
                            updateGroupIndicators()
                        }
                    }
                    groupStrip=strip;groupStripScroll=stripScroll
                    tabs.addView(groupLeftIndicator,LinearLayout.LayoutParams(dp(14),dp(20)))
                    tabs.addView(stripScroll,LinearLayout.LayoutParams(0,dp(54),1f))
                    tabs.addView(groupRightIndicator,LinearLayout.LayoutParams(dp(14),dp(20)))
                    // The new style opens these only while a server is being dragged to another subscription.
                    groupTabs=if(MobileStyle.new) tabs else null
                    serverPanel.addView(tabs,LinearLayout.LayoutParams(-1,if(MobileStyle.new) 0 else dp(54)))
                    serverPanel.addView(geoBanner,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(5),dp(4),dp(5),0) })
                }
                val serverColumn=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val serverScroll=ScrollView(requireContext()).apply {
                    isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
                    clipToPadding=byGroups
                    // Rows melt away at the edges of the list instead of being cut off.
                    if(MobileStyle.new) { isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(dp(20)) }
                    if(byGroups && !MobileStyle.new) setPadding(0,dp(8),0,0)
                    addView(serverColumn,FrameLayout.LayoutParams(-1,-2))
                    setOnScrollChangeListener { _, _, y, _, _ ->
                        MobileOrbArtwork.pauseWhileScrolling()
                        if(byGroups) groupScrollPositions[renderedGroupId]=y else serverScrollPosition=y
                    }
                }
                serverHeader=if(byGroups) FrameLayout(requireContext()).also { serverPanel.addView(it,LinearLayout.LayoutParams(-1,-2)) } else null
                serverPanel.addView(serverScroll,LinearLayout.LayoutParams(-1,if(byGroups) -2 else 0,if(byGroups) 0f else 1f))
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
                if(chipBottom && chipNear) chipRow?.let { body.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }) }
                if(dockBottom) body.addView(controlParent,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
                if(chipBottom && !chipNear) chipRow?.let { body.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }) }
                // Appearance: the tile bar at the very bottom, under the server list.
                if(tilesBottom) {
                    if(landscape) side.addView(View(requireContext()),LinearLayout.LayoutParams(-1,0,1f))
                    side.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin=dp(10) })
                }
            }
            pickerGroups=groups.map { it.first }
            groupChip?.text=pickerGroups.firstOrNull { it.id==focusedGroup }?.displayName().orEmpty()
            val serverColumn=serverRows ?: return@launch
            if(MobileStyle.new && byGroups && risenGroup!=focusedGroup) {
                // Only a change of subscription rises; the first render is simply there.
                if(risenGroup!=Long.MIN_VALUE) {
                    serverColumn.alpha=0f; serverColumn.translationY=dp(14).toFloat()
                    serverColumn.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).withLayer().start()
                }
                risenGroup=focusedGroup
            }
            val serverScroll=serverScrollView ?: return@launch
            val anchor=serverColumn.childrenList().firstOrNull { it.bottom > serverScroll.scrollY }
            val anchorId=nextGroupViews.entries.firstOrNull { it.value.second === anchor }?.key
            val anchorOffset=(anchor?.top ?: 0)-serverScroll.scrollY
            val groupDrag=groupReorder ?: MobileReorder(if(byGroups) groupStrip!! else serverColumn,if(byGroups) groupStripScroll!! else serverScroll,
                { reorderActive(it) },{ saveReorder(null,it) },
                { first,second -> (first in pinnedGroups())==(second in pinnedGroups()) },
                { item -> if(!byGroups) setGroupRowsCompact(serverColumn,serverScroll,item,true) },
                { item -> if(!byGroups) setGroupRowsCompact(serverColumn,serverScroll,item,false) }).also { groupReorder=it }
            val desired=mutableListOf<View>()
            val liveIds=groups.filter { !byGroups || it.first.id==focusedGroup }.flatMap { it.second }.map { it.id }.toSet()
            nextLatencyViews.keys.retainAll(liveIds); nextProfileRows.keys.retainAll(liveIds)
            val liveGroups=groups.map { it.first.id }.toSet()
            nextGroupViews.keys.retainAll(liveGroups); nextRefreshButtons.keys.retainAll(liveGroups)
            nextProfileReorders.keys.retainAll(liveGroups)
            profilePages.keys.retainAll(liveGroups)
            groupScrollPositions.keys.retainAll(liveGroups)
            if(byGroups) {
                nextGroupViews.keys.retainAll(setOf(focusedGroup))
                nextProfileReorders.keys.retainAll(setOf(focusedGroup))
            }

            var firstGroup = true
            for ((groupIndex, pair) in groups.withIndex()) {
                val (group,profiles)=pair
                if(byGroups && group.id!=focusedGroup) continue
                // An emptied group stays listed so it can still be renamed, filled or deleted; only a fresh install's
                // lone empty list is hidden.
                if (!byGroups && profiles.isEmpty() && !group.isSubscription && groups.all { it.second.isEmpty() }) continue
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
                val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; if(!byGroups) background = tile() }
                // The full list keeps each group's header with its rows; one subscription at a time pins it.
                val head = if(byGroups) LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL } else box
                val rows =LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val profileDrag=MobileReorder(rows,serverScroll,{ reorderActive(it) },{
                    saveReorder(group.id,MobileProfilePaging.reorder(profiles.map { p -> p.id },visibleProfiles.map { p -> p.id },it))
                })
                profileDrag.movable=true
                val receiveProfile: (Long,Long?) -> Unit = { id,before ->
                    moveProfile(id,group.id,before ?: profiles.getOrNull(window.end)?.id)
                }
                profileDrag.acceptFrom(box,positioned=true,receive=receiveProfile)
                if(byGroups) serverDropArea?.let {
                    profileDrag.acceptFrom(it,allowSameList=true,positioned=true,receive=receiveProfile)
                }
                nextProfileReorders[group.id]=profileDrag
                val title = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron); imageTintList=android.content.res.ColorStateList.valueOf(muted); rotation=if(signature.collapsed) 0f else 90f
                    visibility=if(byGroups) View.GONE else if(group.subOptions.allowCollapse) View.VISIBLE else View.INVISIBLE
                }
                val heading = LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,0,0); isFocusable=true }
                heading.addView(arrow,LinearLayout.LayoutParams(dp(20),dp(20)))
                val names=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(4),dp(8)) }
                names.addView(text(group.displayName(),17f).apply { setPadding(0,0,0,0); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                if(group.isSubscription && group.subLastUpdate>0) names.addView(text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(group.subLastUpdate*1000)),10f,muted).apply { setPadding(0,dp(3),0,0) })
                heading.addView(names,LinearLayout.LayoutParams(0,-2,1f))
                heading.setOnClickListener {
                    if(byGroups) return@setOnClickListener
                    if(!group.subOptions.allowCollapse) return@setOnClickListener
                    if (!collapsed.add(group.id)) collapsed.remove(group.id)
                    requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).edit().putStringSet("collapsedGroups",collapsed.map { it.toString() }.toSet()).apply()
                    arrow.animate().rotation(if(group.id in collapsed) 0f else 90f).setDuration(if(MobileStyle.new) 240 else 120).start()
                    refresh()
                }
                if(byGroups) {
                    val gestures=GestureDetector(requireContext(),object:GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(event:MotionEvent)=true
                        override fun onSingleTapUp(event:MotionEvent):Boolean { heading.performClick();return true }
                        override fun onLongPress(event:MotionEvent) { editGroup(group,true) }
                        override fun onDoubleTap(event:MotionEvent):Boolean { host().displayFragmentWithId(R.id.nav_group);return true }
                        override fun onFling(start:MotionEvent?,end:MotionEvent,velocityX:Float,velocityY:Float):Boolean {
                            val first=start ?: return false
                            val dx=end.x-first.x
                            if(kotlin.math.abs(dx)<dp(40) || kotlin.math.abs(dx)<kotlin.math.abs(end.y-first.y)) return false
                            groups.getOrNull(groupIndex+if(dx<0) 1 else -1)?.first?.let { selectGroup(it.id) }
                            return true
                        }
                    })
                    heading.setOnTouchListener { _,event -> gestures.onTouchEvent(event) }
                }
                if(!byGroups) groupDrag.bind(heading,box,group.id,heading)
                title.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
                if (group.isSubscription) title.addView(iconAction(R.drawable.ic_mobile_refresh,getString(R.string.qmobile_refresh)) { SubscriptionClient.refreshGroup(group.id,false) }.apply { isEnabled=!SubscriptionClient.states.value.containsKey(group.id); alpha=1f;nextRefreshButtons[group.id]=this },LinearLayout.LayoutParams(dp(40),dp(48)))
                title.addView(iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests)) { testMenu(profiles.map { it.id }.toLongArray(),group.displayName(),group.id,true) },LinearLayout.LayoutParams(dp(40),dp(48)))
                lateinit var more: ImageButton
                more=iconAction(R.drawable.ic_mobile_more,getString(R.string.qmobile_more)) { groupMenu(group,more) }
                title.addView(more,LinearLayout.LayoutParams(dp(44),dp(48)))
                head.addView(title)
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
                    val bar=FrameLayout(requireContext()).apply { background=GradientDrawable().apply { setColor(toolbarColor); cornerRadius=dp(MobilePalette.rowDp).toFloat(); setStroke(1,MobilePalette.outline) } }
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
                    head.addView(traffic)
                    if (quota.expire > 0) head.addView(text(getString(R.string.qmobile_expiry,java.text.DateFormat.getDateInstance().format(java.util.Date(quota.expire*1000))),11f,muted).apply { setPadding(dp(14),0,dp(14),dp(8)) })
                    if (quota.announce.isNotBlank()) head.addView(text(quota.announce,12f,muted).apply {
                        setPadding(dp(14),0,dp(14),dp(10))
                        ellipsize=android.text.TextUtils.TruncateAt.END
                        maxLines=if(group.id in openNotes) Int.MAX_VALUE else 2
                        setOnClickListener {
                            if(!openNotes.add(group.id)) openNotes.remove(group.id)
                            maxLines=if(group.id in openNotes) Int.MAX_VALUE else 2
                        }
                    })
                }
                for ((profileIndex,profile) in visibleProfiles.withIndex()) {
                    // Give input and animation callbacks a turn even when a page contains complex rows.
                    if(previousStructure!=null && profileIndex>0 && profileIndex%12==0) kotlinx.coroutines.delay(1)
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
                    val leadingFlag=MobileCountryFlags.leading(displayName)
                    val flagLength=leadingFlag.length
                    val badge=flagBadge(displayName)
                    row.addView(badge,LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginStart=dp(10) })
                    val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(6), dp(6)) }
                    labels.addView(text(if(flagLength>0) displayName.substring(flagLength).trimStart() else displayName, 15f).apply { setTypeface(typeface,Typeface.BOLD); includeFontPadding=!MobileStyle.new; setPadding(0,0,0,dp(if(MobileStyle.new) 3 else 2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    labels.addView(text(profile.displayProtocol(), 12f, muted).apply { includeFontPadding=!MobileStyle.new; setPadding(0,0,0,0); maxLines=1 })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    val latency=FrameLayout(requireContext()).apply { tag=profile.latency }
                    nextLatencyViews[profile.id]=latency
                    row.addView(latency,LinearLayout.LayoutParams(dp(62),dp(32)))
                    if(!group.isSubscription || !group.subOptions.hideServerSettings) row.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_chevron); setPadding(dp(6),dp(6),dp(6),dp(6)); contentDescription=getString(R.string.qmobile_edit); setOnClickListener { startActivity(profile.profileSettingsIntent(requireContext(),group.isSubscription)) } },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val swipe = MobileSwipeTile(requireContext(), row, { listOf(
                        iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests),Color.rgb(36,171,85)) { openSwipe?.closeActions(); testMenu(longArrayOf(profile.id),profile.displayName(),group.id) },
                        iconAction(R.drawable.ic_mobile_share,getString(R.string.qmobile_share),Color.rgb(60,132,190)) { shareProfile(profile) },
                        iconAction(R.drawable.ic_mobile_delete,getString(R.string.qmobile_delete),Color.rgb(220,66,61)) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result=withContext(Dispatchers.IO) { ProfileManager.batchDeleteProfiles(listOf(profile.id),stopRunning=true) }
                                if(!result.ok) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
                                else if(profile.type=="qwdtt" && profile.id !in result.kept) app.rawline.mobile.ktx.runOnDefaultDispatcher {
                                    if(app.rawline.mobile.deploy.QwdttProfileApi.releaseDeleted(listOf(profile))>0) withContext(Dispatchers.Main) {
                                        Toast.makeText(app.rawline.mobile.ktx.app,"Устройство отвязано от сервера",Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) }).apply { onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions(); openSwipe=opened } }
                    profileDrag.bind(row,swipe,profile.id)
                    if(activeProfileDrag?.draggedId==profile.id) swipe.visibility=View.INVISIBLE
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
                if(byGroups && hasServers && profiles.isEmpty()) rows.addView(text(getString(if(groups.any { it.first.isSubscription }) R.string.qmobile_group_empty else R.string.qmobile_empty),14f,muted).apply { gravity=Gravity.CENTER })
                box.addView(rows)

                box.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=if(firstGroup) 0 else dp(16) }
                nextGroupViews[group.id]=signature to box
                if(byGroups) groupHeads[group.id]=head
                desired.add(box)
                firstGroup = false
            }
            // A retained box keeps its header, which has to follow it into a rebuilt screen.
            serverHeader?.let { pinned ->
                groupHeads.keys.retainAll(setOf(focusedGroup))
                val head=groupHeads[focusedGroup]
                if(head==null) pinned.removeAllViews()
                else if(head.parent!==pinned) {
                    (head.parent as? ViewGroup)?.removeView(head)
                    pinned.removeAllViews()
                    pinned.addView(head,FrameLayout.LayoutParams(-1,-2))
                }
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
            if(byGroups) nextProfileReorders[focusedGroup]?.let { renderGroupStrip(groups.map { it.first },focusedGroup,it,groupDrag) }
            val restorePosition=groupScrollPositions[focusedGroup] ?: 0
            renderedGroupId=focusedGroup
            // Restore before drawing, after layout knows the new row heights.
            serverScroll.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    serverScroll.viewTreeObserver.removeOnPreDrawListener(this)
                    val retained=anchorId?.let { nextGroupViews[it]?.second }
                    if(byGroups) serverScroll.scrollTo(0,restorePosition)
                    else if(retained != null) serverScroll.scrollTo(0,(retained.top-anchorOffset).coerceAtLeast(0))
                    pendingMovedProfile?.let { nextProfileRows[it] }?.let { row ->
                        pendingMovedProfile=null
                        row.post { row.requestRectangleOnScreen(android.graphics.Rect(0,0,row.width,row.height),false) }
                    }
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
                if(!completed) { renderedStructure=null;if(!reordering) chromeKey=null }
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
        power?.isEnabled = state != app.rawline.mobile.bg.BaseService.State.Stopping
        if(restartAfterStop && !state.canStop && state!=app.rawline.mobile.bg.BaseService.State.Stopping) {
            restartAfterStop=false
            host().toggleService()
        }
        // The row keeps its height, so the list under it does not jump on connecting.
        sheetSpeed?.visibility=if(state.connected) View.VISIBLE else View.INVISIBLE
        if(!state.connected) { lastDown=0L;lastUp=0L }
        modeSwitches.forEach { (mode,switch) -> switch.isChecked=(state.canStop || restartAfterStop) && DataStore.serviceMode==mode }
        val test=TestSessionClient.state.value
        val testing=test.running && test.kind==TestSpec.KIND_URL
        val snapshot=if(testing && !test.testCurrent) nativeTest?.takeIf {
            test.rows.containsKey(it.profileId) && test.rows[it.profileId]?.phase!=app.rawline.mobile.ui.test.RowPhase.DONE
        } else nativeConnection?.takeIf {
            it.profileId==(if(state.connected) DataStore.currentProfile else DataStore.selectedProxy)
        }
        val stage=if(testing && test.testCurrent) null else snapshot?.stage
        val caption=if(state == app.rawline.mobile.bg.BaseService.State.Stopping) getString(R.string.stopping)
        else if(testing || state.canStop || state.connected) getString(when(stage) {
            app.rawline.mobile.bg.carrier.NativeConnectionStage.AUTHORIZING -> R.string.qmobile_vk_authorizing
            app.rawline.mobile.bg.carrier.NativeConnectionStage.CAPTCHA -> R.string.qmobile_vk_captcha
            app.rawline.mobile.bg.carrier.NativeConnectionStage.WAITING_VK -> R.string.qmobile_vk_waiting
            app.rawline.mobile.bg.carrier.NativeConnectionStage.RECONNECTING -> R.string.qmobile_reconnecting
            app.rawline.mobile.bg.carrier.NativeConnectionStage.PREPARING, app.rawline.mobile.bg.carrier.NativeConnectionStage.CONNECTING -> R.string.qmobile_connecting
            else -> if(testing || currentTestArmed) R.string.qmobile_ping_checking else if(state.connected) R.string.qmobile_connected else R.string.qmobile_connecting
        }) else ""
        val description=getString(R.string.qmobile_connect) + ", " + caption.ifEmpty { getString(R.string.qmobile_disconnected) }
        if(power?.contentDescription != description) power?.contentDescription=description
        power?.setTextColor(muted)
        (power?.background as? MobilePowerArtwork)?.connected=state.connected
        (power?.background as? MobilePowerArtwork)?.connecting=state==app.rawline.mobile.bg.BaseService.State.Connecting
        currentCheck?.visibility=if(state.connected) View.VISIBLE else if(MobileStyle.new) View.INVISIBLE else View.GONE
        val button=power
        if(!state.connected || pulseTarget!==button) {
            powerPulse?.cancel();powerPulse=null;pulseTarget=null
            button?.scaleX=1f;button?.scaleY=1f
        }
        if(state.connected && button!=null && powerPulse==null && !MobileStyle.new && modeSwitches.isEmpty()) {
            pulseTarget=button
            powerPulse=android.animation.ObjectAnimator.ofPropertyValuesHolder(button,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X,.995f,1.015f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y,.995f,1.015f)).apply {
                duration=1800;repeatCount=android.animation.ValueAnimator.INFINITE;repeatMode=android.animation.ValueAnimator.REVERSE
                interpolator=android.view.animation.AccelerateDecelerateInterpolator();start()
            }
        }
        power?.apply {
            textSize=13f;gravity=Gravity.CENTER;setPadding(0,if(background is MobilePowerArtwork) dp(75) else dp(8),0,0)
            val seconds=((android.os.SystemClock.elapsedRealtime()-DataStore.mobileConnectedAt)/1000).coerceAtLeast(0)
            if(background !is MobilePowerArtwork) {
                val elapsed=String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
                val workers=snapshot?.takeIf { it.total>0 && !testing }?.let { "  ${it.active}/${it.total}" }.orEmpty()
                val label=caption.ifEmpty { getString(R.string.qmobile_orb_idle) }+(if(state.connected) "  $elapsed" else "")+workers
                if(text.toString()!=label) text=label
            }
            val fontSize=textSize
            (background as? MobilePowerArtwork)?.apply {
                captionSize=fontSize
                statusText=caption
                elapsedText=String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
                // Running workers out of those started, as the original WDTT/qWDTT/CSQTT apps show them.
                workersText=snapshot?.takeIf { it.total>0 && !testing }?.let { "${it.active}/${it.total}" }.orEmpty()
            }
        }
    }
    private fun updateLatencies() {
        val state=TestSessionClient.state.value
        latencyViews.forEach { (id, holder) ->
            val result=state.rows[id].takeUnless { state.testCurrent }
            val busy=id in TestSessionClient.queuedProfiles.value || (state.running && result != null && result.phase != app.rawline.mobile.ui.test.RowPhase.DONE)
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
            else holder.addView(text(label,12f,if(unavailable) muted else ProxyEntity.latencyColor(latency)).apply {
                gravity=Gravity.CENTER;setPadding(0,0,0,0);includeFontPadding=!MobileStyle.new
                if(previous=="busy") { alpha=0f;animate().alpha(1f).setDuration(120).start() }
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
    private fun shareChoice(swipe:MobileSwipeTile,label:String,icon:Int,callback:()->Unit):View {
        val ink=if(MobileStyle.new) foregroundColor else Color.WHITE
        return LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;contentDescription=label;if(MobileStyle.new) background=MobilePalette.actionShape(context,Color.rgb(105,93,176)) else setBackgroundColor(Color.rgb(105,93,176))
            setOnClickListener { swipe.closeActions();callback() }
            addView(ImageView(requireContext()).apply { setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(ink);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(23),dp(23)))
            addView(text(label,10f,ink).apply { setPadding(0,dp(4),0,0);maxLines=1;includeFontPadding=false;gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
    }
    /** The ways to pass a subscription on: its address, a QR code, or the profiles it holds. */
    private fun shareGroup(group:ProxyGroup,swipe:MobileSwipeTile) {
        fun copy(value:String) { Toast.makeText(requireContext(),if(SagerNet.trySetPrimaryClip(value)) R.string.action_export_msg else R.string.action_export_err,Toast.LENGTH_SHORT).show() }
        fun copyLinks(deep:Boolean) {
            viewLifecycleOwner.lifecycleScope.launch {
                copy(withContext(Dispatchers.IO) {
                    ProfileManager.members(group.id).map { if(deep) it.exportJsonLink() else it.exportLink() }.filter { it.isNotEmpty() }.joinToString("\n")
                })
            }
        }
        val choices=mutableListOf<View>()
        if(group.isSubscription && group.url.isNotBlank()) {
            choices+=shareChoice(swipe,"URL",R.drawable.ic_mobile_link) { copy(group.url) }
            choices+=shareChoice(swipe,getString(R.string.qmobile_qr),R.drawable.ic_mobile_qr) { app.rawline.mobile.widget.QRCodeDialog(group.url,group.displayName()).show(parentFragmentManager,"group-qr") }
        }
        choices+=shareChoice(swipe,getString(R.string.qmobile_group_profiles),R.drawable.ic_mobile_copy_outline) { copyLinks(false) }
        choices+=shareChoice(swipe,"JSON",R.drawable.ic_mobile_json) { copyLinks(true) }
        swipe.showShareChoices(*choices.toTypedArray())
    }
    private fun shareProfile(profile:ProxyEntity) {
        val swipe=openSwipe ?: return
        fun option(label:String,icon:Int,callback:()->Unit)=shareChoice(swipe,label,icon,callback)
        fun copy(value:String) { Toast.makeText(requireContext(),if(SagerNet.trySetPrimaryClip(value)) R.string.action_export_msg else R.string.action_export_err,Toast.LENGTH_SHORT).show() }
        val link=profile.exportLink().ifBlank { profile.exportJsonLink() }
        swipe.showShareChoices(
            option("URL",R.drawable.ic_mobile_link) { copy(link) },
            option(getString(R.string.qmobile_qr),R.drawable.ic_mobile_qr) { app.rawline.mobile.widget.QRCodeDialog(link,profile.displayName()).show(parentFragmentManager,"profile-qr") },
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
        body.addView(text(group.displayName(),18f).apply { setTypeface(typeface,Typeface.BOLD) })
        fun entry(label:String,icon:Int,action:()->Unit) {
            val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56);background=tile(); isFocusable=true; contentDescription=label; setOnClickListener { dialog.dismiss();action() }; MobileFeedback.apply(this) }
            row.addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(blue) },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginStart=dp(14) })
            row.addView(text(label,17f).apply { gravity=Gravity.CENTER_VERTICAL; setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(-1,-2))
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        if(group.isSubscription) entry(getString(R.string.qmobile_refresh),R.drawable.ic_mobile_refresh) { SubscriptionClient.refreshGroup(group.id,false) }
        if(group.subOptions.routingProvider.isNotBlank()) entry(getString(R.string.provider_routing_title),R.drawable.ic_mobile_route) {
            startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group.id))
        }
        entry(getString(R.string.qmobile_ping),R.drawable.ic_mobile_test) { viewLifecycleOwner.lifecycleScope.launch { val ids=withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(group.id).map { it.id }.toLongArray() };testMenu(ids,group.displayName(),group.id,true) } }
        entry(getString(R.string.settings),R.drawable.ic_mobile_settings) { editGroup(group,true) }
        entry(getString(R.string.connection_test_clear_results),R.drawable.ic_mobile_clear_test) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { ProfileManager.clearGroupTestResults(group.id) }
                refresh()
            }
        }
        entry(getString(R.string.clear_traffic_statistics),R.drawable.ic_mobile_clear_stats) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val ids=ProfileManager.memberIds(group.id).toLongArray()
                    runCatching { host().connection.service?.resetTraffic(ids) }.onFailure { app.rawline.mobile.ktx.Logs.w(it) }
                    ProfileManager.resetTraffic(ids)
                }
                refresh()
            }
        }
        val pinned=group.id in pinnedGroups()
        entry(getString(if(pinned) R.string.qmobile_unpin else R.string.qmobile_pin),R.drawable.ic_mobile_pin) { val ids=pinnedGroups().toMutableSet();if(pinned) ids.remove(group.id) else ids.add(group.id);DataStore.mobilePinnedGroups=ids.joinToString(",");refresh() }
        entry(getString(R.string.qmobile_delete),R.drawable.ic_mobile_delete) {
            viewLifecycleOwner.lifecycleScope.launch {
                val previous=if(DataStore.mobileListMode=="groups" || MobileStyle.new) groupAfterRemoval(renderedGroupOrder,group.id) else 0L
                val deleted=withContext(Dispatchers.IO) { GroupRepo.delete(group.id,previous) }
                if(!deleted) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
            }
        }
        dialog.setContentView(androidx.core.widget.NestedScrollView(requireContext()).apply {
            clipToPadding=false
            addView(body,FrameLayout.LayoutParams(-1,-2))
        })
        val bars=view?.let { ViewCompat.getRootWindowInsets(it)?.getInsets(WindowInsetsCompat.Type.systemBars()) }
        val height=view?.height?.takeIf { it>0 } ?: resources.displayMetrics.heightPixels
        dialog.behavior.maxHeight=(height-(bars?.top ?: 0)-(bars?.bottom ?: 0)-dp(16)).coerceAtLeast(1)
        dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
    private fun editGroup(group: ProxyGroup, settings: Boolean) {
        startActivity(Intent(requireContext(), if(settings) GroupSettingsActivity::class.java else SubscriptionEditorActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            .putExtra(GroupSettingsActivity.EXTRA_SETTINGS, settings))
    }
    private fun addMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
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
    private fun routingMenu(anchor: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val routes=withContext(Dispatchers.IO) { RouteManager.usable() }
            if(!anchor.isAttachedToWindow) return@launch
            val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
            routes.forEach { route ->
                popup.menu.add(1, android.view.Menu.NONE, android.view.Menu.NONE, route.name).apply {
                    isCheckable=true;isChecked=route.id==DataStore.currentRouteId
                    setOnMenuItemClickListener {
                        app.rawline.mobile.ui.route.RouteQuickSwitch.switchTo(route.id)
                        app.rawline.mobile.ui.route.RouteQuickSwitch.switched(this@MobileHomeFragment,route.name);true
                    }
                }
            }
            androidx.core.view.MenuCompat.setGroupDividerEnabled(popup.menu,true)
            popup.menu.add(2, android.view.Menu.NONE, android.view.Menu.NONE, R.string.warp_enable).apply {
                isCheckable=true;isChecked=DataStore.enableWarp
                setOnMenuItemClickListener { app.rawline.mobile.ui.route.RouteQuickSwitch.setWarpEnabled(this@MobileHomeFragment,!isChecked);true }
            }
            popup.menu.add(2, android.view.Menu.NONE, android.view.Menu.NONE, R.string.qmobile_enable_adblock).apply {
                isCheckable=true;isChecked=DataStore.adblockEnable
                setOnMenuItemClickListener {
                    DataStore.adblockEnable = !isChecked
                    if (DataStore.serviceState.started) SagerNet.reloadService()
                    true
                }
            }
            popup.menu.add(3, android.view.Menu.NONE, android.view.Menu.NONE, R.string.settings).setOnMenuItemClickListener {
                host().openSettingsScreen(app.rawline.mobile.ui.settings.RoutingSettingsFragment::class.java.name,getString(R.string.settings_routing));true
            }
            popup.menu.add(3, android.view.Menu.NONE, android.view.Menu.NONE, R.string.xray_geo_download_title).setOnMenuItemClickListener {
                host().openSettingsScreen(app.rawline.mobile.ui.settings.XrayGeoSettingsFragment::class.java.name, getString(R.string.xray_geo_download_title));true
            }
            popup.show()
        }
    }
    private fun modeMenu(anchor: View) {
        val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
        listOf(Key.MODE_VPN to R.string.service_mode_vpn, Key.MODE_PROXY to R.string.service_mode_proxy).forEach { (mode,label) ->
            popup.menu.add(label).apply {
                isCheckable=true;isChecked=DataStore.serviceMode==mode
                setOnMenuItemClickListener {
                    if (DataStore.serviceMode != mode) {
                        if (DataStore.serviceState.started) SagerNet.stopService()
                        DataStore.serviceMode=mode
                    }
                    true
                }
            }
        }
        popup.show()
    }
    /** Its own popup: PopupMenu cuts long rows at half the screen, these wrap instead. */
    private fun updatesMenu(anchor: View) {
        val context=requireContext()
        val rows=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(0,dp(6),0,dp(6)) }
        val window=android.widget.PopupWindow(rows,minOf(resources.displayMetrics.widthPixels-dp(32),dp(340)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(context,R.drawable.bg_popup_menu))
            animationStyle=R.style.PopupMenuAnimation
            elevation=dp(8).toFloat()
        }
        fun row(title: String, checked: Boolean? = null, enabled: Boolean = true, click: () -> Unit = {}) {
            rows.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(48);setPadding(dp(16),dp(8),dp(12),dp(8))
                addView(text(title,15f,if(enabled) MobilePalette.text else MobilePalette.muted).apply { setPadding(0,0,dp(8),0) },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                if(checked!=null) addView(android.widget.CheckBox(context).apply {
                    isChecked=checked;isClickable=false;isFocusable=false
                })
                if(enabled) { isClickable=true;MobileFeedback.apply(this);setOnClickListener { window.dismiss();click() } }
            })
        }
        row(getString(R.string.allow_beta_update),DataStore.allowBetaUpdate) { DataStore.allowBetaUpdate=!DataStore.allowBetaUpdate;UpdateDiscovery.refresh() }
        row(getString(R.string.update_home_auto),DataStore.updateCheckAuto) { DataStore.updateCheckAuto=!DataStore.updateCheckAuto;UpdateDiscovery.refresh() }
        row(getString(R.string.update_check)) { UpdateActivity.start(context) }
        // A found version sits right under the check, the installed one below a line.
        UpdateDiscovery.available.value?.takeIf { DataStore.updateCheckAuto }?.let { offer ->
            row(getString(R.string.update_home_banner,offer.versionName)) { UpdateActivity.start(context,offer) }
        }
        rows.addView(View(context).apply { setBackgroundColor(MobilePalette.outline) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,maxOf(1,dp(1)/2)).apply { setMargins(dp(16),dp(4),dp(16),dp(4)) })
        row(getString(R.string.update_current_version,BuildConfig.VERSION_NAME),enabled=false)
        // With the tiles at the bottom there is no room below: open upwards, as the PopupMenus there do.
        if(tilesAtBottom()) {
            rows.measure(View.MeasureSpec.makeMeasureSpec(window.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.UNSPECIFIED)
            window.showAsDropDown(anchor,0,-anchor.height-rows.measuredHeight)
        } else window.showAsDropDown(anchor)
    }
    private fun subscriptionsMenu(anchor: View) {
        val popup=androidx.appcompat.widget.PopupMenu(requireContext(),anchor)
        fun collapse(all: Boolean) {
            if(all) groupViews.keys.forEach { collapsed.add(it) } else collapsed.clear()
            requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).edit()
                .putStringSet("collapsedGroups",collapsed.map { it.toString() }.toSet()).apply()
            refresh()
        }
        if(DataStore.mobileListMode!="groups") {
            popup.menu.add(R.string.qmobile_fold_all).setOnMenuItemClickListener { collapse(true);true }
            popup.menu.add(R.string.qmobile_expand_all).setOnMenuItemClickListener { collapse(false);true }
        }
        popup.menu.add(R.string.update_all_subscription).setOnMenuItemClickListener {
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
            setTypeface(typeface,Typeface.BOLD)
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
        val nativeTypes = listOf("qwdtt" to "qWDTT", "csqtt" to "CSQTT", "mrtt" to "MRTT", "wdttplus" to "WDTT Plus", "freeturn" to "FreeTurn", "openflux" to "OpenFlux", "openfluxkey" to "OpenFlux Key", "olcrtc" to "olcRTC",
            "masterdns" to "MasterDNS", "cottendns" to "CottenDNS", "soar" to "Soar", "vaydns" to "VayDNS", "dnstt" to "DNSTT", "slipstream" to "SlipStream", "s3bypass" to "S3 Bypass", "sstp" to "SSTP")
        // Keep the same editors and localized labels as the original manual-entry menu.
        val originalTypes = listOf(
            "autoselector" to R.string.action_auto_selector,
            "socks" to R.string.action_socks,
            "http" to R.string.action_http,
            "shadowsocks" to R.string.action_shadowsocks,
            "shadowsocksr" to R.string.action_shadowsocksr,
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
                text=title;isAllCaps=false;textSize=16f;setTypeface(typeface,Typeface.BOLD)
                setTextColor(foregroundColor);background=tile();stateListAnimator=null
                supportBackgroundTintList=null
                gravity=Gravity.CENTER_VERTICAL or Gravity.START;includeFontPadding=false
                minimumHeight=dp(48);setPadding(dp(12),dp(8),dp(12),dp(8))
                MobileFeedback.apply(this)
                setOnClickListener {
                    isEnabled=false
                    sheet.dismiss()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val target=withContext(Dispatchers.IO) { SubscribeFlows.targetGroupId() }
                        startActivity(Intent(requireContext(),app.rawline.mobile.ui.profile.settingsActivityFor(type))
                            .putExtra("qmobileProfileType",type).putExtra("qmobileGroupId",target))
                    }
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
