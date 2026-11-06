package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.Color
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

object MobileStyle {
    val new get() = DataStore.uiStyle == "new"
}

object MobilePalette {
    val oled get() = (DataStore.amoledTheme || MobileStyle.new) && Theme.usingNightMode()
    val light get() = !oled && !Theme.usingNightMode()
    val background get() = if (oled) Color.BLACK else if (light) (if (MobileStyle.new) Color.WHITE else Color.rgb(236,237,239)) else Color.rgb(30,30,30)
    val surface get() = if (MobileStyle.new) (if (light) Color.rgb(243,243,246) else Color.rgb(21,21,24))
        else if (oled) Color.rgb(15,15,15) else if (light) Color.rgb(248,249,250) else Color.rgb(49,51,54)
    val toolbar get() = if (oled) Color.BLACK else if (light) (if (MobileStyle.new) Color.WHITE else Color.rgb(231,233,235)) else Color.rgb(34,35,37)
    val text get() = if (MobileStyle.new) (if (light) Color.rgb(10,10,11) else Color.rgb(245,245,247))
        else if (light) Color.rgb(39,44,48) else Color.rgb(231,234,237)
    val muted get() = if (MobileStyle.new) (if (light) Color.rgb(105,105,112) else Color.rgb(142,142,150))
        else if (light) Color.rgb(100,110,119) else Color.rgb(173,182,191)
    /** The dark field inside a card; on true black a lighter one, or it would vanish. */
    val well get() = if (MobileStyle.new) (if (light) Color.rgb(231,231,236) else Color.rgb(36,36,40)) else androidx.core.graphics.ColorUtils.blendARGB(surface, Color.BLACK, .14f)
    /** Corner radii in dp: the new style is rounder throughout. */
    val rowDp get() = if (MobileStyle.new) 16 else 5
    val cardDp get() = if (MobileStyle.new) 24 else 12
    val wellDp get() = if (MobileStyle.new) 16 else 12
    val outline get() = androidx.core.graphics.ColorUtils.setAlphaComponent(muted,55)
    fun accent(context: Context) = if (MobileStyle.new) text else if (!DataStore.useSystemTheme && DataStore.appTheme == Theme.CYAN) Color.rgb(87,190,229)
        else MaterialColors.getColor(context, androidx.appcompat.R.attr.colorAccent, Color.rgb(87,190,229))

    /** A row or button: every list row and action in the app. */
    fun rowShape(context: Context) = shape(context, rowDp)
    /** A card that holds rows, fields or text: also the dialogs, notices and sheets. */
    fun cardShape(context: Context) = shape(context, cardDp)
    /** The chosen row or the main action: a tint and an outline of [color], never a solid fill, the same on every theme. */
    fun selectedShape(context: Context, color: Int = accent(context)) = shape(context, rowDp).apply {
        setColor(androidx.core.graphics.ColorUtils.blendARGB(surface, color, .16f))
        setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), color)
    }
    private fun shape(context: Context, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(surface); cornerRadius = radius * context.resources.displayMetrics.density; setStroke(1, outline)
    }

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
