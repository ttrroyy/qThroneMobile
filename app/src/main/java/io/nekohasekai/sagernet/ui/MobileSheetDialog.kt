package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.graphics.Rect
import com.google.android.material.bottomsheet.BottomSheetDialog

class MobileSheetDialog(context: Context) : BottomSheetDialog(context) {
    private var dragHandle: View? = null

    fun dragOnlyFrom(handle: View) {
        dragHandle = handle
        behavior.isDraggable = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragHandle?.let { handle ->
                val bounds = Rect()
                behavior.isDraggable = handle.getGlobalVisibleRect(bounds) &&
                    bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            background=GradientDrawable().apply {
                setColor(if (MobileStyle.new) MobilePalette.frosted else MobilePalette.surface);cornerRadius=MobilePalette.cardDp*resources.displayMetrics.density
            }
            clipToOutline=true
        }
        if (MobileStyle.new && android.os.Build.VERSION.SDK_INT >= 31) window?.let { window ->
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (22 * context.resources.displayMetrics.density).toInt() }
        }
        window?.setWindowAnimations(if (MobileStyle.new) app.qthrone.mobile.R.style.MobileSheetSoft else app.qthrone.mobile.R.style.MobileSheetFast)
    }
}
