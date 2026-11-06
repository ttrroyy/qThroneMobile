package app.rawline.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Flat country artwork independent of the device's emoji font. */
object MobileCountryFlags {
    private val images = mutableMapOf<String, Bitmap?>()

    fun bitmap(context: Context, code: String): Bitmap? = images.getOrPut(code) {
        runCatching {
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private val leadingFlag = Regex("^(?:[\\x{1F1E6}-\\x{1F1FF}]{2}|[\\x{1F3F3}\\x{1F3F4}\\x{1F6A9}\\x{1F38C}\\x{2690}\\x{2691}]\\x{FE0F}?(?:\\x{200D}[^\\s]+|[\\x{E0061}-\\x{E007F}]+)?)")

    /** The flag a profile name starts with, country or not (white, pirate, rainbow), "" without one. */
    fun leading(name: String): String = leadingFlag.find(name)?.value.orEmpty()

    /** A flag with no picture of its own, drawn by the system's emoji font. */
    fun emoji(context: Context, flag: String): Bitmap = images.getOrPut("emoji:$flag") {
        val size = (28 * context.resources.displayMetrics.density).toInt()
        if (flag.removeSuffix("\uFE0F") == "\uD83C\uDFF4") return@getOrPut Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.BLACK) }
        // Without the variation selector some fonts draw the white flag as a plain outline.
        val glyph = if (flag == "\uD83C\uDFF3") "\uD83C\uDFF3\uFE0F" else flag
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size * .72f; textAlign = android.graphics.Paint.Align.CENTER }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawText(glyph, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }!!
}
