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
                setColor(MobilePalette.surface);cornerRadius=12*resources.displayMetrics.density
            }
            clipToOutline=true
        }
    }
}
