package app.qthrone.mobile.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * The new style's surface: a translucent body that is lighter at the top, a highlight along the upper edge
 * and a rim of light, as on a drop of water. Android cannot refract what lies behind, so these carry the look.
 */
class MobileGlass(private val radius: Float, private val tint: Int? = null) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = Path()
    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density

    override fun draw(canvas: Canvas) {
        val light = MobilePalette.light
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val top = bounds.top.toFloat()
        val r = minOf(radius, h / 2, bounds.width() / 2f)
        val inset = density * .6f
        box.set(bounds); box.inset(inset, inset)
        path.rewind(); path.addRoundRect(box, r, r, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        val high = tint?.let { ColorUtils.setAlphaComponent(it, if (light) 40 else 50) } ?: if (light) Color.argb(235, 255, 255, 255) else Color.argb(24, 255, 255, 255)
        val low = tint?.let { ColorUtils.setAlphaComponent(it, if (light) 16 else 20) } ?: if (light) Color.argb(150, 246, 246, 250) else Color.argb(7, 255, 255, 255)
        paint.shader = LinearGradient(0f, top, 0f, top + h, high, low, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        // The highlight along the top, fading before the middle.
        paint.shader = LinearGradient(0f, top, 0f, top + h * .46f, Color.argb(if (light) 120 else 18, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        // The rim catches light above and below, and fades along the sides.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 1.1f
        val rim = if (light) Color.argb(40, 0, 0, 0) else Color.WHITE
        paint.shader = LinearGradient(0f, top, 0f, top + h,
            intArrayOf(ColorUtils.setAlphaComponent(rim, if (light) 26 else 150), ColorUtils.setAlphaComponent(rim, if (light) 14 else 22), ColorUtils.setAlphaComponent(rim, if (light) 34 else 70)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f)) }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
