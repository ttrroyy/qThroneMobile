package app.qthrone.mobile.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import androidx.preference.ListPreference

object MobileSelector {
    private val anchors = java.util.WeakHashMap<androidx.preference.Preference,java.lang.ref.WeakReference<android.view.View>>()
    fun bind(preference: androidx.preference.Preference, view: android.view.View) { anchors[preference] = java.lang.ref.WeakReference(view) }
    fun show(preference: androidx.preference.Preference, labels: List<CharSequence>, selected: Int, onSelect: (Int) -> Unit) {
        val anchor = anchors[preference]?.get()?.takeIf { it.isAttachedToWindow }
        if(anchor != null) showAt(anchor,labels,selected,onSelect=onSelect)
        else show(preference.context,preference.title ?: "",labels,selected,onSelect)
    }
    fun showAt(anchor: android.view.View, labels: List<CharSequence>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
        val context = anchor.context
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val list = ListView(context).apply {
            divider = null
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            setPadding(0,dp(10),dp(8),dp(10))
            clipToPadding = true
            adapter = object : ArrayAdapter<CharSequence>(context, android.R.layout.simple_list_item_1, labels) {
                override fun isEnabled(position: Int) = enabled(position)
                override fun areAllItemsEnabled() = false
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        text = if(position == selected) "✓  ${labels[position]}" else labels[position]
                        setTextColor(if(!enabled(position)) MobilePalette.muted else if(position == selected) MobilePalette.accent(context) else MobilePalette.text)
                        isEnabled = enabled(position)
                        alpha = if(enabled(position)) 1f else .5f
                        minimumHeight = dp(48)
                        maxLines = 3
                        setPadding(dp(16),dp(8),dp(12),dp(8))
                    }
            }
        }
        // Keep long DNS lists attached to their row instead of filling the middle of the screen.
        val background = android.graphics.drawable.GradientDrawable().apply {
                setColor(MobilePalette.solid); cornerRadius = dp(MobilePalette.cardDp).toFloat()
                setStroke(1, MobilePalette.outline)
        }
        list.background = background
        list.clipToOutline = true
        val popup = PopupWindow(list, minOf((anchor.width - dp(24)).coerceAtLeast(dp(120)), dp(300)), minOf(labels.size,4) * dp(48) + dp(20), true).apply {
            setBackgroundDrawable(background)
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        list.setOnItemClickListener { _, _, index, _ -> if(enabled(index)) { popup.dismiss(); onSelect(index) } }
        popup.showAsDropDown(anchor, dp(12), 0)
    }
    fun show(context: Context, preference: ListPreference): Boolean {
        val labels=preference.entries ?: return false
        val values=preference.entryValues ?: return false
        val select: (Int) -> Unit = { index ->
            val value=values[index].toString()
            if(preference.callChangeListener(value)) preference.value=value
        }
        val anchor = anchors[preference]?.get()?.takeIf { it.isAttachedToWindow }
        if(anchor != null) showAt(anchor,labels.toList(),preference.findIndexOfValue(preference.value),onSelect=select)
        else show(context,preference.title ?: "",labels.toList(),preference.findIndexOfValue(preference.value),select)
        return true
    }
    fun show(context: Context,title: CharSequence,labels: List<CharSequence>,selected: Int,onSelect:(Int)->Unit) {
        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val dialog=MobileSheetDialog(context)
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(24)) }
        body.addView(TextView(context).apply { text=title;textSize=19f;setTextColor(MobilePalette.text);setPadding(dp(8),dp(8),dp(8),dp(12)) })
        val choices=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        labels.forEachIndexed { index,label ->
            choices.addView(TextView(context).apply {
                text=if(index==selected) "✓  $label" else label;textSize=16f;gravity=Gravity.CENTER_VERTICAL
                setTextColor(if(index==selected) MobilePalette.accent(context) else MobilePalette.text)
                setPadding(dp(16),0,dp(16),0);minimumHeight=dp(48)
                background=android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.solid);cornerRadius=dp(MobilePalette.rowDp).toFloat();setStroke(1,MobilePalette.outline) }
                MobileFeedback.apply(this);setOnClickListener { dialog.dismiss();onSelect(index) }
            },LinearLayout.LayoutParams(-1,dp(48)))
        }
        body.addView(ScrollView(context).apply { isVerticalScrollBarEnabled=false;addView(choices) },LinearLayout.LayoutParams(-1, minOf(labels.size*dp(48),(context.resources.displayMetrics.heightPixels*.55).toInt())))
        dialog.setContentView(body);dialog.show()
    }
}
