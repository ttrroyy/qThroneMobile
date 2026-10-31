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

    /** The caret and selection handles in the accent: the defaults vanish on the OLED theme. Re-run on focus. */
    fun tintCursor(edit: android.widget.EditText) {
        val accent = accent(edit.context)
        edit.highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 80)
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val density = edit.resources.displayMetrics.density
        edit.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(accent); setSize((2 * density).toInt(), (22 * density).toInt()) }
        edit.textSelectHandle?.mutate()?.apply { setTint(accent) }?.let { edit.setTextSelectHandle(it) }
        edit.textSelectHandleLeft?.mutate()?.apply { setTint(accent) }?.let { edit.setTextSelectHandleLeft(it) }
        edit.textSelectHandleRight?.mutate()?.apply { setTint(accent) }?.let { edit.setTextSelectHandleRight(it) }
    }

    /** An on switch in the accent, an off one muted, as in the settings screens. */
    fun tintSwitch(switch: androidx.appcompat.widget.SwitchCompat) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val accent = accent(switch.context)
        switch.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(accent, muted))
        switch.trackTintList = android.content.res.ColorStateList(states,
            intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 110), outline))
        switch.setTextColor(text)
    }
}
