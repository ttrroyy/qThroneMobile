package app.qthrone.mobile.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/** Shared palette for the settings root and every existing preference screen. */
object MobileSettingsStyle {
    private val styledRoots=java.util.WeakHashMap<View,Boolean>()
    /** A fixed colour would hide that a row is disabled (a dependency that is off); the framework sets the views' enabled state. */
    private fun dimmedWhenDisabled(color: Int) = android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x61), color))
    fun apply(fragment: PreferenceFragmentCompat, root: View) {
        if(styledRoots.put(root,true)==true) return
        // Keep the old screen consistent until theme recreation replaces every row.
        val background=MobilePalette.background
        val surface=MobilePalette.surface
        val textColor=MobilePalette.text
        val muted=MobilePalette.muted
        val accent=MobilePalette.accent(root.context)
        fun compact(group: androidx.preference.PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val preference = group.getPreference(i)
                if (preference is androidx.preference.DialogPreference && preference.dialogTitle.isNullOrBlank()) {
                    preference.dialogTitle = preference.title
                }
                if (preference is androidx.preference.EditTextPreference) {
                    preference.positiveButtonText = fragment.getString(android.R.string.ok)
                    preference.negativeButtonText = fragment.getString(android.R.string.cancel)
                }
                if(fragment is SettingsPreferenceFragment && preference !is PreferenceCategory) {
                    val icon=when {
                        preference.key=="qmobileLogs" -> app.qthrone.mobile.R.drawable.ic_mobile_logs
                        preference.key=="qmobileVkRelay" -> app.qthrone.mobile.R.drawable.ic_mobile_vk
                        preference.key=="qmobileTun" -> app.qthrone.mobile.R.drawable.ic_mobile_tun
                        preference.key=="qmobileCore" -> app.qthrone.mobile.R.drawable.ic_mobile_core
                        preference.key=="settingsBackup" -> app.qthrone.mobile.R.drawable.ic_mobile_backup
                        preference.fragment?.contains("UpdateSettings")==true -> app.qthrone.mobile.R.drawable.ic_mobile_update
                        preference.fragment?.contains("Appearance")==true -> app.qthrone.mobile.R.drawable.ic_mobile_pumpkin
                        preference.fragment?.contains("Testing")==true -> app.qthrone.mobile.R.drawable.ic_mobile_test
                        preference.key=="qmobileRouting" -> app.qthrone.mobile.R.drawable.ic_mobile_route
                        preference.fragment?.contains("Dns")==true -> app.qthrone.mobile.R.drawable.ic_mobile_globe
                        preference.fragment?.contains("Subscription")==true -> app.qthrone.mobile.R.drawable.ic_mobile_subscriptions
                        preference.key=="qmobileUpdate" -> app.qthrone.mobile.R.drawable.ic_mobile_refresh
                        else -> app.qthrone.mobile.R.drawable.ic_mobile_settings
                    }
                    preference.setIcon(icon)
                }
                preference.isIconSpaceReserved = preference !is PreferenceCategory && preference.icon != null
                // Preference rebinds a long title to one line; every settings row wraps instead.
                preference.isSingleLineTitle = false
                if (preference is androidx.preference.PreferenceGroup) compact(preference)
            }
        }
        compact(fragment.preferenceScreen)
        root.setBackgroundColor(background)
        val list=fragment.listView
        fragment.setDivider(null)
        fragment.setDividerHeight(0)
        list.isVerticalScrollBarEnabled=false
        list.itemAnimator=null
        list.setBackgroundColor(background)
        val density=list.resources.displayMetrics.density
        list.setPadding((16*density).toInt(), (4*density).toInt(), (16*density).toInt(), (16*density).toInt())
        list.clipToPadding=false
        list.addItemDecoration(object: RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position=parent.getChildAdapterPosition(view)
                val category=position>=0 && (parent.adapter as? PreferenceGroupAdapter)?.getItem(position) is PreferenceCategory
                outRect.top=if(category && position>0) (2*density).toInt() else 0
                outRect.bottom=((if(category) 2 else 6)*density).toInt()
            }
        })
        val styledBackgrounds = java.util.WeakHashMap<View, android.graphics.drawable.Drawable>()
        val styledKeys = java.util.WeakHashMap<View, String?>()
        fun style(view: View) {
            val position=list.getChildAdapterPosition(view)
            if(position==RecyclerView.NO_POSITION) return
            val preference=(list.adapter as? PreferenceGroupAdapter)?.getItem(position)
            val oldKey=styledKeys[view]
            if (view.background != null && styledBackgrounds[view] === view.background && oldKey == preference?.key) return
            if(preference != null) MobileSelector.bind(preference,view)
            val category=preference is PreferenceCategory
            val iconFrame=view.findViewById<View>(androidx.preference.R.id.icon_frame)
            iconFrame?.visibility=if(!category && preference?.icon!=null) View.VISIBLE else View.GONE
            // Only change the view here: preference setters notify the adapter while
            // onChildViewAttachedToWindow can still be inside RecyclerView layout.
            val warning=preference?.key == "updateCurrentVersion" || preference?.key == "sharingInstructions" || preference?.key == "vkRelayAutoWarning" || preference?.key == "providerJsonWarning" || preference?.key?.startsWith("providerWarning") == true
            if(category) {
                view.minimumHeight=0
                view.setPadding(view.paddingLeft,(4*density).toInt(),view.paddingRight,(2*density).toInt())
            } else {
                view.minimumHeight=(48*density).toInt()
                view.setPaddingRelative((12*density).toInt(),(8*density).toInt(),(12*density).toInt(),(8*density).toInt())
            }
            val oldCopy=view.findViewWithTag<View>("socks-copy")
            (oldCopy?.parent as? android.view.ViewGroup)?.removeView(oldCopy)
            if(preference?.key in listOf("inbound_user","inbound_pass")) {
                val container=view as? android.view.ViewGroup
                if(container!=null && container.findViewWithTag<View>("socks-copy")==null) {
                    val widget=container.findViewById<android.view.ViewGroup>(android.R.id.widget_frame)
                    widget?.visibility=View.VISIBLE
                    widget?.addView(android.widget.ImageButton(list.context).apply {
                        tag="socks-copy";contentDescription=list.context.getString(app.qthrone.mobile.R.string.action_copy)
                        setImageResource(app.qthrone.mobile.R.drawable.ic_mobile_clipboard)
                        imageTintList=if(preference?.key == "vkRelayAccount" && preference.icon is androidx.core.graphics.drawable.RoundedBitmapDrawable) null else dimmedWhenDisabled(accent)
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener {
                            val value=if(preference?.key=="inbound_user") app.qthrone.mobile.database.DataStore.inboundUser else app.qthrone.mobile.database.DataStore.inboundPass
                            app.qthrone.mobile.SagerNet.trySetPrimaryClip(value)
                        }
                    },android.view.ViewGroup.LayoutParams((40*density).toInt(),(40*density).toInt()))
                }
            }
            view.background=android.graphics.drawable.GradientDrawable().apply {
                setColor(if(category || warning) background else surface)
                cornerRadius=5*density
                if(!category && !warning) setStroke(1,MobilePalette.outline)
            }
            // The installed version is set apart from the update actions by a line above it.
            if(preference?.key == "updateCurrentVersion") view.background=android.graphics.drawable.LayerDrawable(arrayOf(view.background,
                android.graphics.drawable.ColorDrawable(MobilePalette.outline))).apply {
                setLayerHeight(1,maxOf(1,(density/2).toInt()));setLayerGravity(1,android.view.Gravity.TOP)
            }
            if(!category && !warning) MobileFeedback.apply(view)
            styledBackgrounds[view] = view.background
            view.findViewById<android.widget.ImageView>(android.R.id.icon)?.apply {
                visibility=if(preference?.icon!=null) View.VISIBLE else View.GONE
                imageTintList=if(preference?.key == "vkRelayAccount" && preference.icon is androidx.core.graphics.drawable.RoundedBitmapDrawable) null else android.content.res.ColorStateList.valueOf(accent)
            }
            view.findViewById<TextView>(android.R.id.title)?.apply {
                setTextColor(dimmedWhenDisabled(if(category) accent else textColor))
                textSize=16f
                setTypeface(null,if(category) Typeface.BOLD else Typeface.NORMAL)
                isSingleLine=false;maxLines=Int.MAX_VALUE;ellipsize=null
            }
            view.findViewById<TextView>(android.R.id.summary)?.apply { setTextColor(dimmedWhenDisabled(muted));textSize=13f }
            if(preference?.key == "sharingAddresses") view.findViewById<TextView>(android.R.id.summary)?.apply {
                minLines=2; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
            } else if(oldKey == "sharingAddresses") view.findViewById<TextView>(android.R.id.summary)?.apply {
                minLines=0; maxLines=Int.MAX_VALUE; ellipsize=null
            }
            styledKeys[view]=preference?.key
        }
        list.addOnChildAttachStateChangeListener(object: RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view:View) { style(view) }
            override fun onChildViewDetachedFromWindow(view:View) {}
        })
        var restylePending = true
        fun restyle() { restylePending = true; list.invalidate() }
        list.adapter?.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = restyle()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = restyle()
        })
        // A Preference rebind can restore its stock background without reattaching the row.
        // Restore the surface before drawing, including rows whose summaries arrive later.
        list.viewTreeObserver.addOnPreDrawListener {
            // Adapter updates can arrive in batches. Style once after rebinding,
            // instead of scanning every visible row on every scrolling frame.
            if (restylePending) {
                restylePending = false
                for (i in 0 until list.childCount) style(list.getChildAt(i))
            }
            true
        }
    }
}
