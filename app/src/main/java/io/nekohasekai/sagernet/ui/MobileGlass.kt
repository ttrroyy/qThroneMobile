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
