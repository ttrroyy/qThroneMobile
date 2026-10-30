package app.qthrone.mobile.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** Soft neutral layers drawn at the current viewport size, without bitmap scaling. */
class MobileHomeBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        if(MobilePalette.oled) { canvas.drawColor(Color.BLACK);return }
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(MobilePalette.background, MobilePalette.toolbar, MobilePalette.background),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = LinearGradient(0f, 0f, w, h, Color.argb(9,180,186,192), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val sweep = Path().apply {
            moveTo(-w*.3f, h*.08f); cubicTo(w*.72f,h*.22f,w*.08f,h*.68f,w*1.2f,h*.8f)
            lineTo(w*1.2f,h); cubicTo(w*.03f,h*.8f,w*.65f,h*.38f,-w*.3f,h*.25f); close()
        }
        canvas.drawPath(sweep, paint)
        paint.shader = RadialGradient(w*.15f,h*.16f,w*.95f,Color.argb(8,175,183,192),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        canvas.drawRect(bounds,paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Rings and a crisp power glyph; the surrounding view remains transparent. */
class MobilePowerArtwork(private val accent: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var connected = false
        set(value) { field=value; invalidateSelf() }
    var statusText = ""
        set(value) { if(field!=value) { field=value;invalidateSelf() } }
    var elapsedText = ""
        set(value) { if(field!=value) { field=value;invalidateSelf() } }
    var workersText = ""
        set(value) { if(field!=value) { field=value;invalidateSelf() } }
    var pressScale = 1f
        set(value) { field=value; invalidateSelf() }
    var captionSize = 0f
    override fun draw(canvas: Canvas) {
        val cx=bounds.exactCenterX(); val cy=bounds.exactCenterY()
        val r=minOf(bounds.width(),bounds.height())*.46f*pressScale
        paint.style=Paint.Style.STROKE; paint.strokeWidth=r*.005f
        paint.shader=LinearGradient(cx-r,cy-r,cx+r,cy+r,Color.argb(100,74,135,159),Color.argb(60,161,144,155),Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,r,paint)
        paint.style=Paint.Style.FILL
        paint.shader=LinearGradient(cx,cy-r,cx,cy+r,MobilePalette.surface,MobilePalette.toolbar,Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,r*.83f,paint)
        paint.shader=RadialGradient(cx-r*.2f,cy-r*.3f,r*1.1f,
            intArrayOf(MobilePalette.surface,MobilePalette.surface,MobilePalette.toolbar),
            floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy-r*.025f,r*.66f,paint)
        paint.shader=null
        paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
        paint.strokeWidth=r*.04f
        paint.color=if(connected) accent else MobilePalette.muted
        val glyph=r*.17f
        val glyphY=if(statusText.isNotEmpty()) cy-r*.2f else cy
        canvas.drawArc(RectF(cx-glyph,glyphY-glyph,cx+glyph,glyphY+glyph),-45f,270f,false,paint)
        canvas.drawLine(cx,glyphY-glyph*1.25f,cx,glyphY-glyph*.1f,paint)
        if(statusText.isNotEmpty()) {
            paint.style=Paint.Style.FILL
            paint.textAlign=Paint.Align.CENTER
            paint.textSize=if(captionSize>0f) captionSize else r*.112f
            val width=paint.measureText(statusText)
            if(width>r*1.55f) paint.textSize*=r*1.55f/width
            paint.typeface=Typeface.DEFAULT
            paint.color=MobilePalette.muted
            canvas.drawText(statusText,cx,cy+r*.17f,paint)
            paint.typeface=Typeface.DEFAULT_BOLD
            paint.color=MobilePalette.text
            if(connected) canvas.drawText(elapsedText,cx,cy+r*.39f,paint)
            if(workersText.isNotEmpty()) {
                paint.typeface=Typeface.DEFAULT
                paint.color=MobilePalette.muted
                canvas.drawText(workersText,cx,cy+(if(connected) r*.58f else r*.39f),paint)
            }
        }
    }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
