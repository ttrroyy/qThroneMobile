package app.rawline.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.graphics.Rect
import com.google.android.material.bottomsheet.BottomSheetDialog

class MobileSheetDialog(context: Context) : BottomSheetDialog(context) {
    private var dragHandle: View? = null

    fun dragOnlyFrom(handle: View) {
        if (MobileStyle.new) return // the new style's sheets drag from anywhere; their lists scroll through nested scrolling
        dragHandle = handle
        behavior.isDraggable = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        window?.decorView?.let { MobileFeedback.onTouchDown(it, event) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragHandle?.let { handle ->
                val bounds = Rect()
                behavior.isDraggable = handle.getGlobalVisibleRect(bounds) &&
                    bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dismiss() {
        closedAt = android.os.SystemClock.uptimeMillis()
        super.dismiss()
    }

    private fun scrollWithSheet(view: View) {
        if (view is android.widget.ScrollView) view.isNestedScrollingEnabled = true
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) scrollWithSheet(view.getChildAt(index))
    }

    override fun onStart() {
        super.onStart()
        window?.let { MobileScreenMotion.fullFrameRate(it) }
        if (MobileStyle.new) {
            // Open or closed, nothing in between: a half-open sheet is what felt like it stopped short.
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { scrollWithSheet(it) }
        }
        findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            background=GradientDrawable().apply {
                setColor(if (MobileStyle.new) MobilePalette.frosted else MobilePalette.surface);cornerRadius=MobilePalette.cardDp*resources.displayMetrics.density
            }
            clipToOutline=true
        }
        if (MobileStyle.new) window?.setDimAmount(MobilePalette.windowDim)
        if (MobileStyle.new && android.os.Build.VERSION.SDK_INT >= 31) window?.let { window ->
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (MobilePalette.BLUR_DP * context.resources.displayMetrics.density).toInt() }
        }
        window?.setWindowAnimations(if (MobileStyle.new) app.rawline.mobile.R.style.MobileSheetSoft else app.rawline.mobile.R.style.MobileSheetFast)
    }

    companion object {
        /** When a sheet last closed: the tap that closes one must not also reach what lies under it. */
        var closedAt = 0L
            private set
    }
}
