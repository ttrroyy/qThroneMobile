package app.qthrone.mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.qthrone.mobile.R

/** The building blocks of the server screens, in the look of the deploy list and editor. */
internal class ServerUi(private val context: Context) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    val accent get() = MobilePalette.accent(context)

    fun column(padding: Int = 0) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }

    fun card(radius: Int = 12) = column(12).apply {
        background = GradientDrawable().apply { setColor(MobilePalette.surface); cornerRadius = dp(radius).toFloat(); setStroke(1,MobilePalette.outline) }
    }

    fun well() = GradientDrawable().apply {
        setColor(ColorUtils.blendARGB(MobilePalette.surface, android.graphics.Color.BLACK, .14f)); cornerRadius = dp(12).toFloat()
    }

    fun text(value: CharSequence, size: Float = 16f, color: Int = MobilePalette.text, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
    }

    fun section(title: String) = text(title, 15f, MobilePalette.muted).apply { setPadding(dp(6), dp(12), 0, dp(6)) }

    fun gap(height: Int = 8) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    /** An action tile: icon, title, subtitle and an arrow, like the original server screen. */
    fun tile(title: String, subtitle: String?, icon: Int, click: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(48)
        background = GradientDrawable().apply { setColor(MobilePalette.surface); cornerRadius = dp(5).toFloat(); setStroke(1,MobilePalette.outline) }
        isFocusable = true; setOnClickListener { click() }; MobileFeedback.apply(this)
        addView(badge(icon), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(text(title, 16f, bold = true))
            if (subtitle != null) addView(text(subtitle, 13f, MobilePalette.muted).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_mobile_arrow_forward); imageTintList = ColorStateList.valueOf(MobilePalette.text)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
    }

    fun badge(icon: Int) = ImageView(context).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(accent); scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    fun button(label: String, primary: Boolean = false, danger: Boolean = false, click: () -> Unit) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = 16f; minimumHeight = dp(48)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        val color = if (danger) android.graphics.Color.rgb(232, 120, 112) else accent
        setTextColor(if (primary) MobilePalette.text else color)
        background = if (primary) MobilePalette.selectedShape(context, color) else MobilePalette.rowShape(context)
        isFocusable = true; setOnClickListener { click() }; MobileFeedback.apply(this)
    }

    fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(MobilePalette.text); background = null
        contentDescription = description; setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { click() }
        MobileFeedback.apply(this, circle = true)
    }

    fun input(hint: String, value: String = "", number: Boolean = false) = EditText(context).apply {
        this.hint = hint; setText(value); setTextColor(MobilePalette.text); setHintTextColor(MobilePalette.muted); isSingleLine = true
        background = well(); setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(48); MobilePalette.tintCursor(this)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    /** Label and value, the statistics row of an access card. */
    fun stat(label: String, value: String) = column().apply {
        addView(text(label, 12f, MobilePalette.muted)); addView(text(value, 15f, bold = true).apply { setPadding(0, dp(2), 0, 0) })
    }

    /** The copy button every share block ends with. */
    fun copyButton(description: String, copy: () -> Boolean) = iconButton(R.drawable.ic_mobile_copy_outline, description) {
        if (copy()) android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
    }.apply { imageTintList = ColorStateList.valueOf(accent) }

    /** A share link in the dark well with a copy button, the same block for every server kind. */
    fun linkRow(label: String?, value: String, lines: Int = 2) = column().apply {
        setPadding(0, dp(10), 0, 0)
        if (label != null) addView(text(label, 12f, MobilePalette.muted).apply { setPadding(0, 0, 0, dp(6)) })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(value, 12f, mono = true).apply {
                maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END; setTextIsSelectable(true)
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = well()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(copyButton("Скопировать: ${label ?: "ссылку"}") { app.qthrone.mobile.SagerNet.trySetPrimaryClip(value) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        })
    }

    /** The QR code in the same well, on the left, with a button that copies it as a picture. */
    fun qrRow(link: String, size: Int = 168) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0)
        addView(LinearLayout(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = well(); gravity = Gravity.CENTER_HORIZONTAL
            addView(qr(link, size), LinearLayout.LayoutParams(dp(size), dp(size)))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(copyButton("Скопировать QR-код") { copyQr(link) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
    }

    fun qr(link: String, size: Int = 220) = ImageView(context).apply {
        setImageBitmap(app.qthrone.mobile.widget.QrBitmap.encode(link, dp(size))); contentDescription = "QR-код подключения"
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = dp(12).toFloat() }
    }

    /** Puts the QR picture on the clipboard through the app's cache FileProvider (cache/qr only). */
    fun copyQr(link: String): Boolean = runCatching {
        val dir = java.io.File(context.cacheDir, "qr").apply { mkdirs() }
        val file = java.io.File(dir, "qr.png")
        val bitmap = app.qthrone.mobile.widget.QrBitmap.encode(link, 720)
        val framed = android.graphics.Bitmap.createBitmap(bitmap.width + 80, bitmap.height + 80, android.graphics.Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 40f, 40f, null) }
        }
        file.outputStream().use { framed.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, app.qthrone.mobile.BuildConfig.APPLICATION_ID + ".cache", file)
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        // The picture for apps that paste images, the link itself for text paste and the home screen's clipboard import.
        clipboard.setPrimaryClip(android.content.ClipData(android.content.ClipDescription("QR-код", arrayOf("image/png", "text/plain")),
            android.content.ClipData.Item(link, null, uri)))
        true
    }.getOrDefault(false)

    fun bytes(value: Long): String = when {
        value >= 1L shl 30 -> String.format("%.2f ГБ", value / (1L shl 30).toDouble())
        value >= 1L shl 20 -> String.format("%.0f МБ", value / (1L shl 20).toDouble())
        value >= 1024 -> String.format("%.0f КБ", value / 1024.0)
        else -> "$value Б"
    }
}

/** Settings and server screens share a quick fade; sheets keep their own bottom transition. */
abstract class ServerScreenActivity(contentLayoutId: Int = 0) : ThemedActivity(contentLayoutId) {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (isDialog) return
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.settings_enter, R.anim.settings_exit)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.settings_pop_enter, R.anim.settings_pop_exit)
        } else overridePendingTransition(R.anim.settings_enter, R.anim.settings_exit)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (!isDialog && android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.settings_pop_enter, R.anim.settings_pop_exit)
    }
}
