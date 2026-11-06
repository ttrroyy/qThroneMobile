package app.qthrone.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.qthrone.mobile.R
import kotlin.math.*

/**
 * The new style's connect button: a disc whose edge turns into a slow wave once connected, with rings travelling out of it.
 * While connecting a bright stroke runs round the edge. Everything eases between the states; nothing jumps.
 */
class MobileOrbArtwork(context: Context, private val idleText: String) : MobilePowerArtwork(0) {
    private val density = context.resources.displayMetrics.density
    private val font = runCatching { ResourcesCompat.getFont(context, R.font.geologica) }.getOrNull() ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Path()
    private val box = RectF()
    private var wave = 0f
    private var spin = 0f
    private var lit = 0f
    private var lastFrame = 0L
    private var angle = 0f
    private var phase = 0f
    private val tick = Runnable { invalidateSelf() }
    override var connected = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    override var connecting = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private fun moving() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun ease(value: Float, target: Float, dt: Float, ms: Float): Float {
        val next = value + (target - value) * (1f - exp(-dt / ms))
        return if (abs(next - target) < .004f) target else next
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 100).toFloat()
        lastFrame = now
        val waveTo = if (connected) 1f else if (connecting) .45f else 0f
        val spinTo = if (connecting) 1f else 0f
        val litTo = if (connected || connecting) 1f else 0f
        if (moving()) {
            wave = ease(wave, waveTo, dt, 320f); spin = ease(spin, spinTo, dt, 240f); lit = ease(lit, litTo, dt, 260f)
            angle = (angle + dt * (.14f + .24f * spin)) % 360f
            phase = (phase + dt * (.00035f + .001f * spin)) % (2f * PI.toFloat())
        } else { wave = waveTo; spin = spinTo; lit = litTo }

        val cx = bounds.exactCenterX()
        val base = minOf(bounds.width(), bounds.height()) * .25f * pressScale
        val cy = bounds.exactCenterY() - base * .34f
        val ink = MobilePalette.text

        // The rings leave the edge and fade before they reach the caption.
        if (wave > .01f && moving()) {
            paint.style = Paint.Style.STROKE; paint.shader = null; paint.strokeWidth = density
            val cycle = 3400f
            for (index in 0 until 3) {
                val travel = ((now % cycle.toLong()) / cycle + index / 3f) % 1f
                paint.color = ColorUtils.setAlphaComponent(ink, ((1f - travel) * (1f - travel) * 70f * wave).toInt())
                canvas.drawCircle(cx, cy, base * (1.08f + .62f * travel), paint)
            }
        }
        // A soft light behind the disc grows with the connection.
        if (lit > .01f) {
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(cx, cy, base * 1.7f, ColorUtils.setAlphaComponent(ink, (34f * lit).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, base * 1.7f, paint)
            paint.shader = null
        }
        edge.rewind()
        val steps = 144
        for (step in 0..steps) {
            val theta = step * 2.0 * PI / steps
            val swell = 1f + wave * .06f * sin(8.0 * theta + phase * 8).toFloat() + wave * .02f * sin(3.0 * theta - phase * 3).toFloat()
            val x = cx + base * swell * cos(theta).toFloat(); val y = cy + base * swell * sin(theta).toFloat()
            if (step == 0) edge.moveTo(x, y) else edge.lineTo(x, y)
        }
        edge.close()
        // A drop: thin at the top, gathering light low in its body.
        val day = MobilePalette.light
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy + base * .62f, base * 1.5f,
            if (day) Color.argb(235, 255, 255, 255) else Color.argb(30 + (30 * lit).toInt(), 255, 255, 255),
            if (day) Color.argb(120, 232, 232, 240) else Color.argb(8, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawPath(edge, paint)
        canvas.save(); canvas.clipPath(edge)
        // The bright patch high on the left, and the caustic glow under it.
        paint.shader = RadialGradient(cx - base * .32f, cy - base * .62f, base * .8f, Color.argb(if (day) 200 else 60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, base * 1.2f, paint)
        paint.shader = RadialGradient(cx, cy + base * 1.05f, base * .85f, ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 22 else 34 + (46 * lit).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, base * 1.2f, paint)
        paint.shader = null
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 1.2f
        paint.shader = LinearGradient(0f, cy - base, 0f, cy + base,
            ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 34 else 190),
            ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 16 else 46), Shader.TileMode.CLAMP)
        canvas.drawPath(edge, paint)
        paint.shader = null

        // The stroke that runs round while connecting.
        if (spin > .01f) {
            canvas.save(); canvas.rotate(angle * 2f, cx, cy)
            paint.strokeWidth = 3f * density; paint.strokeCap = Paint.Cap.ROUND
            paint.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(ink, (255 * spin).toInt()), Color.TRANSPARENT), floatArrayOf(0f, .62f, .63f))
            val ring = base * 1.14f
            box.set(cx - ring, cy - ring, cx + ring, cy + ring)
            canvas.drawArc(box, 0f, 225f, false, paint)
            paint.shader = null
            canvas.restore()
        }

        // Power symbol.
        val glyph = base * .34f
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = base * .055f
        paint.color = ColorUtils.blendARGB(MobilePalette.muted, ink, lit)
        box.set(cx - glyph, cy - glyph, cx + glyph, cy + glyph)
        canvas.drawArc(box, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - glyph * 1.3f, cx, cy - glyph * .15f, paint)

        // Caption under the waves.
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.typeface = font
        val top = cy + base * 1.86f
        paint.textSize = 15f * density
        paint.color = MobilePalette.muted
        canvas.drawText(statusText.ifEmpty { idleText }, cx, top, paint)
        if (connected) {
            paint.textSize = 24f * density; paint.color = ink; paint.isFakeBoldText = true
            canvas.drawText(elapsedText, cx, top + 30f * density, paint)
            paint.isFakeBoldText = false
        }
        if (workersText.isNotEmpty()) {
            paint.textSize = 13f * density; paint.color = MobilePalette.muted
            canvas.drawText(workersText, cx, top + (if (connected) 52f else 22f) * density, paint)
        }
        if (moving() && (wave != waveTo || spin != spinTo || lit != litTo || connected || connecting)) {
            unscheduleSelf(tick)
            scheduleSelf(tick, now + if (connecting || wave != waveTo) 16L else 32L)
        } else lastFrame = 0L
    }
}
