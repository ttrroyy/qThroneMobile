package app.rawline.mobile.ui

import android.content.Context
import android.graphics.Color
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

object MobileStyle {
    val new get() = DataStore.uiStyle == "new"
}

object MobilePalette {
    val oled get() = (DataStore.amoledTheme || MobileStyle.new) && Theme.usingNightMode()
    val light get() = !oled && !Theme.usingNightMode()
    val background get() = if (oled) Color.BLACK else if (light) (if (MobileStyle.new) Color.WHITE else Color.rgb(236,237,239)) else Color.rgb(30,30,30)
    /** Translucent in the new style: anything that has to hide what is under it uses [solid] or [frosted]. */
    val surface get() = if (MobileStyle.new) (if (light) Color.argb(190,255,255,255) else Color.argb(18,255,255,255)) else solid
    val solid get() = if (MobileStyle.new) (if (light) Color.rgb(243,243,246) else Color.rgb(21,21,24))
        else if (oled) Color.rgb(15,15,15) else if (light) Color.rgb(248,249,250) else Color.rgb(49,51,54)
    val toolbar get() = if (MobileStyle.new) Color.TRANSPARENT else if (oled) Color.BLACK else if (light) (if (MobileStyle.new) Color.WHITE else Color.rgb(231,233,235)) else Color.rgb(34,35,37)
    val text get() = if (MobileStyle.new) (if (light) Color.rgb(10,10,11) else Color.rgb(245,245,247))
        else if (light) Color.rgb(39,44,48) else Color.rgb(231,234,237)
    val muted get() = if (MobileStyle.new) (if (light) Color.rgb(105,105,112) else Color.rgb(142,142,150))
        else if (light) Color.rgb(100,110,119) else Color.rgb(173,182,191)
    /** The dark field inside a card; on true black a lighter one, or it would vanish. */
    val well get() = if (MobileStyle.new) (if (light) Color.argb(16,0,0,0) else Color.argb(20,255,255,255)) else androidx.core.graphics.ColorUtils.blendARGB(surface, Color.BLACK, .14f)
    /** Corner radii in dp: the new style is rounder throughout. */
    val rowDp get() = if (MobileStyle.new) 20 else 5
    val cardDp get() = if (MobileStyle.new) 28 else 12
    val wellDp get() = if (MobileStyle.new) 18 else 12
    /** A screen's own colour: the new style's screens let the backdrop through. */
    val screen get() = if (MobileStyle.new) Color.TRANSPARENT else background
    /** Over content that must stay readable: notices, dialogs, sheets. */
    /** The blur behind sheets and dialogs: enough to lift them off the screen, while what is behind stays recognisable. */
    const val BLUR_DP = 20
    val windowDim get() = if (light) .35f else .6f
    val frosted get() = if (light) Color.argb(248,250,250,252) else Color.argb(248,10,10,11)
    val outline get() = androidx.core.graphics.ColorUtils.setAlphaComponent(muted,55)
    fun accent(context: Context) = if (MobileStyle.new) text else if (!DataStore.useSystemTheme && DataStore.appTheme == Theme.CYAN) Color.rgb(87,190,229)
        else MaterialColors.getColor(context, androidx.appcompat.R.attr.colorAccent, Color.rgb(87,190,229))

    /** A row or button: every list row and action in the app. */
    fun rowShape(context: Context): android.graphics.drawable.Drawable = shape(context, rowDp)
    /** A card that holds rows, fields or text: also the dialogs, notices and sheets. */
    fun cardShape(context: Context): android.graphics.drawable.Drawable = shape(context, cardDp)
    /** The chosen row or the main action: a tint and an outline of [color], never a solid fill, the same on every theme. */
    fun selectedShape(context: Context, color: Int = accent(context)): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        if (MobileStyle.new) return MobileGlass(rowDp * density, color)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.graphics.ColorUtils.blendARGB(surface, color, .16f))
            cornerRadius = rowDp * density; setStroke(density.toInt().coerceAtLeast(1), color)
        }
    }
    /** A button revealed behind a swiped row: a drop of glass in [color], clear of its neighbours. */
    fun actionShape(context: Context, color: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        return android.graphics.drawable.InsetDrawable(MobileGlass(rowDp * density, color), (4 * density).toInt())
    }
    /** A notice over the content behind it. */
    fun frostedShape(context: Context): android.graphics.drawable.Drawable = if (!MobileStyle.new) shape(context, cardDp) else android.graphics.drawable.GradientDrawable().apply {
        setColor(frosted); cornerRadius = cardDp * context.resources.displayMetrics.density; setStroke(1, outline)
    }
    private fun shape(context: Context, radius: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        if (MobileStyle.new) return MobileGlass(radius * density)
        return android.graphics.drawable.GradientDrawable().apply { setColor(surface); cornerRadius = radius * density; setStroke(1, outline) }
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
