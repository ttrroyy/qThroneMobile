package app.rawline.mobile.widget

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.nio.charset.StandardCharsets

/** A square QR code of [text], black on white with the standard quiet zone. */
object QrBitmap {
    fun encode(text: String, size: Int): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 2)
        if (!StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        val bits = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { if (bits.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
