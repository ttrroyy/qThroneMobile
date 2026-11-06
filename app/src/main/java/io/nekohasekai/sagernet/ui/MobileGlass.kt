package app.qthrone.mobile.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * The new style's surface: a translucent body that is lighter at the top, a highlight along the upper edge
 * and a rim of light, as on a drop of water. Android cannot refract what lies behind, so these carry the look.
 */
class MobileGlass(private val radius: Float, private val tint: Int? = null) : Drawable() {
    init { MobileGlassLight.track(this) }
    private val spin = Matrix()
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
        // Two glints travel round the rim as the phone tilts: on black this edge is what reads as liquid glass.
        val rim = if (light) Color.BLACK else Color.WHITE
        fun a(alpha: Int) = ColorUtils.setAlphaComponent(rim, alpha)
        val dim = if (light) 12 else 26
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        paint.shader = SweepGradient(cx, cy,
            intArrayOf(a(dim), a(if (light) 34 else 130), a(dim), a(dim), a(if (light) 44 else 235), a(dim), a(dim)),
            floatArrayOf(0f, .16f, .32f, .5f, .68f, .86f, 1f)).apply {
            spin.setRotate(MobileGlassLight.angle, cx, cy); setLocalMatrix(spin)
        }
        canvas.drawPath(path, paint)
        // A second, faint line inside the first gives the edge thickness.
        if (!light && h > density * 16) {
            box.inset(density * 2f, density * 2f)
            val inner = (r - density * 2f).coerceAtLeast(0f)
            paint.strokeWidth = density * .7f
            paint.shader = LinearGradient(0f, top, 0f, top + h * .6f, Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(box, inner, inner, paint)
        }
        paint.shader = null
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f)) }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Where the light falls on the glass: follows the phone's tilt while one of the new style's screens is open. */
object MobileGlassLight : android.hardware.SensorEventListener {
    var angle = 0f
        private set
    private val glass = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Drawable, Boolean>())
    private var manager: android.hardware.SensorManager? = null
    fun track(drawable: Drawable) { glass.add(drawable) }
    fun start(context: android.content.Context) {
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        val gravity = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: return
        manager = sensors
        sensors.registerListener(this, gravity, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }
    fun stop() { manager?.unregisterListener(this); manager = null }
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val next = event.values[0] * 7f + (event.values[1] - 7f) * 3f
        if (kotlin.math.abs(next - angle) < 1.5f) return
        angle += (next - angle) * .35f
        glass.toList().forEach { it.invalidateSelf() }
    }
    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) { }
}
