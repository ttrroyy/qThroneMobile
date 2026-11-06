package app.rawline.mobile.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** The new style's surface: a translucent body, a little lighter at the top, and one thin even rim. */
class MobileGlass(private val radius: Float, private val tint: Int? = null) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var corner = 0f
    private var body: Shader? = null
    private var rim: Shader? = null
    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density

    override fun onBoundsChange(bounds: Rect) {
        val light = MobilePalette.light
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val top = bounds.top.toFloat()
        corner = minOf(radius, h / 2, bounds.width() / 2f)
        val inset = density * .5f
        box.set(bounds); box.inset(inset, inset)
        val high = tint?.let { ColorUtils.setAlphaComponent(it, if (light) 36 else 44) } ?: if (light) Color.argb(205, 255, 255, 255) else Color.argb(15, 255, 255, 255)
        val low = tint?.let { ColorUtils.setAlphaComponent(it, if (light) 20 else 26) } ?: if (light) Color.argb(160, 248, 248, 250) else Color.argb(9, 255, 255, 255)
        body = LinearGradient(0f, top, 0f, top + h, high, low, Shader.TileMode.CLAMP)
        val line = tint ?: if (light) Color.BLACK else Color.WHITE
        rim = LinearGradient(0f, top, 0f, top + h,
            ColorUtils.setAlphaComponent(line, if (tint != null) 90 else if (light) 20 else 30),
            ColorUtils.setAlphaComponent(line, if (tint != null) 50 else if (light) 12 else 14), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.style = Paint.Style.FILL
        paint.shader = body
        canvas.drawRoundRect(box, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * .75f
        paint.shader = rim
        canvas.drawRoundRect(box, corner, corner, paint)
        paint.shader = null
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f)) }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The panel at the bottom of the new style's home screen: the same glass, its top edge rising round the connect button. */
class MobileDockGlass(private val corner: Float, private val discY: Float, private val radius: Float, private val edge: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private var body: Shader? = null
    private var rim: Shader? = null
    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density

    override fun onBoundsChange(bounds: Rect) {
        val inset = density * .5f
        val left = bounds.left + inset; val right = bounds.right - inset; val bottom = bounds.bottom - inset
        val top = bounds.top + edge; val centreY = bounds.top + discY; val cx = bounds.exactCenterX()
        val span = kotlin.math.sqrt(radius * radius - (top - centreY) * (top - centreY))
        val fillet = 16 * density
        // The arc starts a little above where the circle meets the edge, so a soft curve can join them.
        val start = Math.toDegrees(kotlin.math.atan2((top - centreY).toDouble(), -span.toDouble())).toFloat() + 14f
        val rad = Math.toRadians(start.toDouble())
        val joinX = (radius * kotlin.math.cos(rad)).toFloat(); val joinY = centreY + (radius * kotlin.math.sin(rad)).toFloat()
        shape.rewind()
        shape.moveTo(left, top + corner); shape.quadTo(left, top, left + corner, top)
        shape.lineTo(cx - span - fillet, top); shape.quadTo(cx - span, top, cx + joinX, joinY)
        shape.arcTo(RectF(cx - radius, centreY - radius, cx + radius, centreY + radius), start, 540f - 2f * start, false)
        shape.quadTo(cx + span, top, cx + span + fillet, top)
        shape.lineTo(right - corner, top); shape.quadTo(right, top, right, top + corner)
        shape.lineTo(right, bottom - corner); shape.quadTo(right, bottom, right - corner, bottom)
        shape.lineTo(left + corner, bottom); shape.quadTo(left, bottom, left, bottom - corner)
        shape.close()
        val light = MobilePalette.light
        val high = centreY - radius
        body = LinearGradient(0f, high, 0f, bottom, if (light) Color.argb(205, 255, 255, 255) else Color.argb(22, 255, 255, 255), if (light) Color.argb(160, 248, 248, 250) else Color.argb(9, 255, 255, 255), Shader.TileMode.CLAMP)
        val line = if (light) Color.BLACK else Color.WHITE
        rim = LinearGradient(0f, high, 0f, bottom, ColorUtils.setAlphaComponent(line, if (light) 24 else 44), ColorUtils.setAlphaComponent(line, if (light) 12 else 14), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.style = Paint.Style.FILL; paint.shader = body
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density * .75f; paint.shader = rim
        canvas.drawPath(shape, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Where a remote, keyboard or car controller stands: a ring around the control in focus. Touch never shows it. */
object MobileFocusRing {
    fun attach(window: android.view.Window) {
        if (!MobileStyle.new) return
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            old?.foreground = null
            if (now == null || now.isInTouchMode || now.background is MobilePowerArtwork || now is android.widget.EditText) return@addOnGlobalFocusChangeListener
            val density = now.resources.displayMetrics.density
            now.foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = MobilePalette.rowDp * density
                setStroke((2 * density).toInt(), MobilePalette.text)
            }
        }
    }
}
