package app.rawline.mobile.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

object MobileFeedback {
    /** The new style, on the first touch of a window: whatever control lies under the finger presses like glass, set up here once. */
    fun onTouchDown(root: View, event: android.view.MotionEvent) {
        if(!MobileStyle.new || event.actionMasked!=android.view.MotionEvent.ACTION_DOWN) return
        val target=controlAt(root,event.x,event.y) ?: return
        // The connect button presses and animates by itself; a ripple around it would hide it from the home screen.
        if(target.background is MobilePowerArtwork) return
        val density=target.resources.displayMetrics.density
        // Sheets and whole panels are clickable only to stop touches; the switch, inputs and lists have their own response.
        if(target.height>220*density || target is android.widget.EditText || target is android.widget.CompoundButton || target is android.widget.AbsListView) return
        if(target !is com.google.android.material.button.MaterialButton) apply(target)
    }
    private fun controlAt(view: View, x: Float, y: Float): View? {
        if(view.visibility!=View.VISIBLE || x<0 || y<0 || x>=view.width || y>=view.height) return null
        if(view is android.view.ViewGroup) for(index in view.childCount-1 downTo 0) {
            val child=view.getChildAt(index)
            controlAt(child,x+view.scrollX-child.left-child.translationX,y+view.scrollY-child.top-child.translationY)?.let { return it }
        }
        return view.takeIf { (it.isClickable || it.isLongClickable) && it.isEnabled }
    }
    fun apply(view: View, radius: Float = MobilePalette.rowDp.toFloat(), circle: Boolean = false, white: Boolean = false) {
        if(view.background is RippleDrawable) return
        val mask=GradientDrawable().apply {
            setColor(Color.WHITE)
            if(circle) shape=GradientDrawable.OVAL
            else cornerRadius=radius*view.resources.displayMetrics.density
        }
        val colour=if(MobilePalette.light && !white) Color.argb(28,0,0,0) else Color.argb(if(MobileStyle.new) 56 else 35,255,255,255)
        view.background=RippleDrawable(ColorStateList.valueOf(colour),view.background,mask)
    }
}
