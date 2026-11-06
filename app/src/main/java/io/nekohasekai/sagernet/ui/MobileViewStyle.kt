package app.rawline.mobile.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/** Palette and touch feedback for screens built with views rather than preferences. */
object MobileViewStyle {
    fun apply(root: View) {
        if(root is MaterialCardView) {
            root.setCardBackgroundColor(MobilePalette.surface)
            root.strokeColor=MobilePalette.outline
            root.strokeWidth=1
        }
        if(root is TextView) root.setTextColor(MobilePalette.text)
        if(root is ImageView) root.imageTintList=ColorStateList.valueOf(MobilePalette.accent(root.context))
        if(root.isClickable && root !is MaterialCardView && root !is android.widget.EditText) MobileFeedback.apply(root)
        if(root is RecyclerView) {
            root.isVerticalScrollBarEnabled=false
            root.setBackgroundColor(MobilePalette.screen)
            if(root.getTag(app.rawline.mobile.R.id.route_list) != true) {
                root.setTag(app.rawline.mobile.R.id.route_list,true)
                root.addOnChildAttachStateChangeListener(object: RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = apply(view)
                    override fun onChildViewDetachedFromWindow(view: View) {}
                })
            }
        }
        if(root is ViewGroup) for(i in 0 until root.childCount) apply(root.getChildAt(i))
    }
}
