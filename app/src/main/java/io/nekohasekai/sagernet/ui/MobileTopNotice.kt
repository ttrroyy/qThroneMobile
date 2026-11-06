package app.rawline.mobile.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object MobileTopNotice {
    fun show(activity: Activity, message: String, error: Boolean = false) {
        val parent = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        parent.findViewWithTag<View>("mobileNotice")?.let { parent.removeView(it) }
        val notice = LinearLayout(activity).apply {
            tag = "mobileNotice"; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(48)
            elevation = dp(8).toFloat()
            background = MobilePalette.frostedShape(activity)
            addView(TextView(activity).apply {
                text = if(error) "!" else "✓"; textSize = 20f
                setTextColor(if(error) android.graphics.Color.rgb(230,100,100) else android.graphics.Color.rgb(67,185,105))
                gravity = Gravity.CENTER; includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(24), -2))
            addView(TextView(activity).apply {
                text = message; textSize = 14f; setTextColor(MobilePalette.text)
                setPadding(dp(10), 0, 0, 0); maxLines = 5
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val top = ViewCompat.getRootWindowInsets(parent)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        parent.addView(notice, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), top + dp(12), dp(16), 0)
        })
        notice.alpha = 0f; notice.translationY = -dp(60).toFloat()
        notice.animate().alpha(1f).translationY(0f).setDuration(if (MobileStyle.new) 280 else 120).start()
        notice.postDelayed({ notice.animate().alpha(0f).translationY(-dp(60).toFloat()).setDuration(if (MobileStyle.new) 200 else 120)
            .withEndAction { parent.removeView(notice) }.start() }, if(error) 6500L else 4000L)
    }
}
