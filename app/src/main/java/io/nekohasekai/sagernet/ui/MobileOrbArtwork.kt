package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import kotlin.math.*

/**
 * The new style's connect button: a disc whose edge is a slow wave, stronger once connected, with rings travelling out of it.
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
    private val waitForFocus = Runnable { invalidateSelf() }
    override var connected = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var focused = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    override var connecting = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    // Rebuilt only when the button moves or is pressed, never for a plain animation frame.
    private var shaderKey = Float.NaN
    private var glow: Shader? = null
    private var dropBody: Shader? = null
    private var caustic: Shader? = null
    private var glint: Shader? = null
    private var rimShader: Shader? = null
    private var runner: Shader? = null

    private fun moving() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun ease(value: Float, target: Float, dt: Float, ms: Float): Float {
        val next = value + (target - value) * (1f - exp(-dt / ms))
        return if (abs(next - target) < .004f) target else next
    }

    private fun shaders(cx: Float, cy: Float, base: Float, day: Boolean, ink: Int) {
        val key = cx * 31f + cy * 7f + base
        if (key == shaderKey) return
        shaderKey = key
        glow = RadialGradient(cx, cy, base * 1.55f, ColorUtils.setAlphaComponent(ink, 34), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        // A drop: thin at the top, gathering light low in its body.
        dropBody = RadialGradient(cx, cy + base * .62f, base * 1.5f,
            if (day) Color.argb(235, 255, 255, 255) else Color.argb(60, 255, 255, 255),
            if (day) Color.argb(120, 232, 232, 240) else Color.argb(16, 255, 255, 255), Shader.TileMode.CLAMP)
        // The bright patch high on the left, and the caustic glow under it.
        val light = Math.toRadians(241.0).toFloat()
        glint = RadialGradient(cx + base * .7f * cos(light), cy + base * .7f * sin(light), base * .8f, Color.argb(if (day) 200 else 60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        caustic = RadialGradient(cx, cy + base * 1.05f, base * .85f, ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 22 else 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rimShader = LinearGradient(0f, cy - base, 0f, cy + base,
            ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 34 else 190),
            ColorUtils.setAlphaComponent(if (day) Color.BLACK else Color.WHITE, if (day) 16 else 46), Shader.TileMode.CLAMP)
        runner = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, ink, Color.TRANSPARENT), floatArrayOf(0f, .62f, .63f))
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 8f else (now - lastFrame).coerceIn(1, 100).toFloat()
        lastFrame = now
        val waveTo = if (connected) 1f else if (connecting) .6f else .3f
        val spinTo = if (connecting) 1f else 0f
        val litTo = if (connected || connecting) 1f else 0f
        val animated = moving()
        if (animated) {
            wave = ease(wave, waveTo, dt, 320f); spin = ease(spin, spinTo, dt, 240f); lit = ease(lit, litTo, dt, 260f)
            angle = (angle + dt * (.14f + .24f * spin)) % 360f
            phase = (phase + dt * (.00035f + .001f * spin)) % (2f * PI.toFloat())
        } else { wave = waveTo; spin = spinTo; lit = litTo }

        val cx = bounds.exactCenterX()
        val rest = radius(minOf(bounds.width(), bounds.height()))
        val base = rest * pressScale
        // The rings fade out inside the view: it clips whatever leaves it.
        val cy = bounds.top + centerY(minOf(bounds.width(), bounds.height()))
        val ink = MobilePalette.text
        val day = MobilePalette.light
        shaders(cx, cy, base, day, ink)

        // The rings leave the edge and fade before they reach the caption.
        if (lit > .01f && animated) {
            paint.style = Paint.Style.STROKE; paint.shader = null; paint.strokeWidth = density
            val cycle = 3400f
            for (index in 0 until 3) {
                val travel = ((now % cycle.toLong()) / cycle + index / 3f) % 1f
                paint.color = ColorUtils.setAlphaComponent(ink, ((1f - travel) * (1f - travel) * 70f * wave).toInt())
                canvas.drawCircle(cx, cy, base * (1.08f + .5f * travel), paint)
            }
        }
        paint.color = Color.BLACK
        // A soft light behind the disc grows with the connection.
        if (lit > .01f) {
            paint.style = Paint.Style.FILL
            paint.shader = glow; paint.alpha = (255f * lit).toInt()
            canvas.drawCircle(cx, cy, base * 1.55f, paint)
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
        paint.style = Paint.Style.FILL
        paint.shader = dropBody; paint.alpha = if (day) 255 else (127f + 128f * lit).toInt()
        canvas.drawPath(edge, paint)
        paint.shader = glint; paint.alpha = 255
        canvas.drawPath(edge, paint)
        paint.shader = caustic; paint.alpha = if (day) 255 else (108f + 147f * lit).toInt()
        canvas.drawPath(edge, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 1.2f
        paint.shader = rimShader; paint.alpha = 255
        canvas.drawPath(edge, paint)

        if (focused) {
            paint.shader = null; paint.color = ink; paint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, base * 1.2f, paint)
        }
        // The stroke that runs round while connecting.
        if (spin > .01f) {
            canvas.save(); canvas.rotate(angle * 2f, cx, cy)
            paint.strokeWidth = 3f * density; paint.strokeCap = Paint.Cap.ROUND
            paint.shader = runner; paint.alpha = (255f * spin).toInt()
            val ring = base * 1.14f
            box.set(cx - ring, cy - ring, cx + ring, cy + ring)
            canvas.drawArc(box, 0f, 225f, false, paint)
            canvas.restore()
        }
        paint.shader = null

        // Power symbol.
        val glyph = base * .34f
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = base * .055f
        paint.color = ColorUtils.blendARGB(MobilePalette.muted, ink, lit)
        box.set(cx - glyph, cy - glyph, cx + glyph, cy + glyph)
        canvas.drawArc(box, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - glyph * 1.3f, cx, cy - glyph * .15f, paint)

        // Caption under the waves, with the workers of WDTT, qWDTT and CSQTT beside the time.
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.typeface = font
        val top = cy + rest + 22f * density
        paint.textSize = 13f * density
        paint.color = MobilePalette.muted
        canvas.drawText(statusText.ifEmpty { idleText }, cx, top, paint)
        if (connected) {
            paint.textSize = 16f * density; paint.color = ink; paint.isFakeBoldText = true
            canvas.drawText(if (workersText.isEmpty()) elapsedText else "$elapsedText   $workersText", cx, top + 25f * density, paint)
            paint.isFakeBoldText = false
        } else if (workersText.isNotEmpty()) {
            paint.textSize = 13f * density; paint.color = MobilePalette.muted
            canvas.drawText(workersText, cx, top + 22f * density, paint)
        }
        // The next frame follows the screen's own rate; a window over it or a screen change pauses it.
        if (animated) {
            unscheduleSelf(waitForFocus)
            if (!frozen && (callback as? android.view.View)?.hasWindowFocus() != false) invalidateSelf()
            else scheduleSelf(waitForFocus, now + 150L)
        }
    }

    companion object {
        /** A screen change draws through a layer; repainting that layer every frame is what makes it stutter. */
        @JvmStatic var frozen = false
        /** Where the disc sits in a button of [size]: the panel at the bottom curves around it. */
        fun radius(size: Int) = size * .27f
        fun centerY(size: Int) = radius(size) * 1.6f
    }
}
