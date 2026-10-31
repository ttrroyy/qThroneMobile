package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.Color
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

object MobilePalette {
    val oled get() = DataStore.amoledTheme && Theme.usingNightMode()
    val light get() = !oled && !Theme.usingNightMode()
    val background get() = if (oled) Color.BLACK else if (light) Color.rgb(236,237,239) else Color.rgb(30,30,30)
    val surface get() = if (oled) Color.rgb(15,15,15) else if (light) Color.rgb(248,249,250) else Color.rgb(49,51,54)
    val toolbar get() = if (oled) Color.BLACK else if (light) Color.rgb(231,233,235) else Color.rgb(34,35,37)
    val text get() = if (light) Color.rgb(39,44,48) else Color.rgb(231,234,237)
    val muted get() = if (light) Color.rgb(100,110,119) else Color.rgb(173,182,191)
    val outline get() = androidx.core.graphics.ColorUtils.setAlphaComponent(muted,55)
    fun accent(context: Context) = if (!DataStore.useSystemTheme && DataStore.appTheme == Theme.CYAN) Color.rgb(87,190,229)
        else MaterialColors.getColor(context, androidx.appcompat.R.attr.colorAccent, Color.rgb(87,190,229))

    /** The caret and selection handles in the accent: the theme draws them black, which vanishes on the dark themes. */
    fun tintCursor(edit: android.widget.EditText) {
        val accent = accent(edit.context)
        edit.highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 80)
        if (android.os.Build.VERSION.SDK_INT < 29) return
        // The theme has no caret drawable to recolour, so the caret is drawn here: 2dp wide, the line's height.
        edit.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent); setSize((2 * edit.resources.displayMetrics.density).toInt(), 0)
        }
        // A handle the view has not loaded yet comes from the platform theme.
        fun handle(current: android.graphics.drawable.Drawable?, attr: Int) = (current ?: run {
            val styled = edit.context.obtainStyledAttributes(intArrayOf(attr))
            val res = styled.getResourceId(0, 0)
            styled.recycle()
            if (res == 0) null else androidx.core.content.ContextCompat.getDrawable(edit.context, res)
        })?.mutate()?.apply { setTint(accent) }
        handle(edit.textSelectHandle, android.R.attr.textSelectHandle)?.let { edit.setTextSelectHandle(it) }
        handle(edit.textSelectHandleLeft, android.R.attr.textSelectHandleLeft)?.let { edit.setTextSelectHandleLeft(it) }
        handle(edit.textSelectHandleRight, android.R.attr.textSelectHandleRight)?.let { edit.setTextSelectHandleRight(it) }
    }
}
