package app.rawline.mobile.ui

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
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** The building blocks of the server screens, in the look of the deploy list and editor. */
internal class ServerUi(private val context: Context) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    val accent get() = MobilePalette.accent(context)

    fun column(padding: Int = 0) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }

    fun card(radius: Int = MobilePalette.cardDp) = column(12).apply {
        if (MobileStyle.new) { background = MobileGlass(dp(radius).toFloat()); return@apply }
        background = GradientDrawable().apply { setColor(MobilePalette.surface); cornerRadius = dp(radius).toFloat(); setStroke(1,MobilePalette.outline) }
    }

    fun well() = GradientDrawable().apply {
        setColor(MobilePalette.well); cornerRadius = dp(MobilePalette.wellDp).toFloat()
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
        background = GradientDrawable().apply { setColor(MobilePalette.surface); cornerRadius=dp(MobilePalette.rowDp).toFloat(); setStroke(1,MobilePalette.outline) }
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
        setTypeface(typeface,Typeface.BOLD)
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

    /** A field whose label floats above the value, like the deploy editor's: a filled value never hides what it means. */
    fun field(parent: LinearLayout, label: String, value: String = "", number: Boolean = false, secret: Boolean = false, multiline: Boolean = false): EditText {
        val accentColor = ColorStateList.valueOf(accent)
        val box = TextInputLayout(ContextThemeWrapper(context, R.style.MobileDeployInputOverlay)).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor = MobilePalette.well; boxStrokeWidth = 0; boxStrokeWidthFocused = 0
            val radius = dp(MobilePalette.wellDp).toFloat()
            setBoxCornerRadii(radius, radius, radius, radius)
            defaultHintTextColor = ColorStateList.valueOf(MobilePalette.muted); hintTextColor = accentColor
            setEndIconTintList(accentColor)
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val editor = TextInputEditText(box.context).apply {
            textSize = 16f; setTextColor(MobilePalette.text); minimumHeight = dp(48); setPadding(dp(12), dp(24), dp(12), dp(8))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) minLines = 2 else isSingleLine = true
            setText(value); MobilePalette.tintCursor(this)
            setOnFocusChangeListener { view, focused -> if (focused) view.post { MobilePalette.tintCursor(this) } }
        }
        box.addView(editor, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return editor
    }

    /** A setting with its name on the left and the choice on the right, as the original apps draw it. */
    fun dropdown(parent: LinearLayout, title: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, picked: (String) -> Unit = {}) {
        var current = selected
        fun caption() = options.firstOrNull { it.first == current }?.second ?: options.firstOrNull()?.second.orEmpty()
        val choice = text(caption(), 14f, MobilePalette.text).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            minimumHeight = dp(44); setPadding(dp(14), dp(6), dp(10), dp(6)); background = MobilePalette.rowShape(context); compoundDrawablePadding = dp(6)
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_baseline_expand_more_24)?.mutate()?.let {
                it.setTint(MobilePalette.muted); it.setBounds(0, 0, dp(20), dp(20)); setCompoundDrawablesRelative(null, null, it, null)
            }
            isFocusable = enabled
            if (enabled) MobileFeedback.apply(this)
            setOnClickListener { anchor ->
                if (!enabled) return@setOnClickListener
                val popup = androidx.appcompat.widget.PopupMenu(context, anchor, Gravity.END)
                options.forEach { (key, label) ->
                    popup.menu.add(label).apply { isCheckable = true; isChecked = key == current }.setOnMenuItemClickListener {
                        current = key; this@apply.text = caption(); picked(key); true
                    }
                }
                popup.show()
            }
        }
        parent.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; alpha = if (enabled) 1f else .5f; setPadding(0, dp(4), 0, dp(4))
            addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(choice, LinearLayout.LayoutParams(-2, -2).apply { width = -2 })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    fun switchRow(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit = {}): SwitchCompat {
        val switch = SwitchCompat(context).apply { isChecked = checked; setOnClickListener { changed(isChecked) } }
        parent.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(switch)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return switch
    }

    /** Two or more choices side by side, the deploy editor's «Пароль / SSH-ключ». */
    fun segmented(parent: LinearLayout, options: List<Pair<String, String>>, selected: String, picked: (String) -> Unit = {}) {
        val row = LinearLayout(context)
        fun draw(current: String) {
            row.removeAllViews()
            options.forEachIndexed { index, (key, label) ->
                row.addView(button(label, primary = key == current) { draw(key); picked(key) }.apply { textSize = 14f },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
        }
        draw(selected)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** A small action in the text colour of the links, without a surface. */
    fun textButton(label: String, danger: Boolean = false, click: () -> Unit) = text(label, 14f, if (danger) android.graphics.Color.rgb(232, 120, 112) else accent, bold = true).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40); setPadding(dp(8), dp(6), dp(8), dp(6))
        isFocusable = true; setOnClickListener { click() }; MobileFeedback.apply(this)
    }

    /** Small actions that wrap to the next line; [chosen] marks the one in force. */
    fun chips(items: List<Pair<String, () -> Unit>>, chosen: Int = -1) = com.google.android.material.chip.ChipGroup(context).apply {
        chipSpacingHorizontal = dp(6); chipSpacingVertical = dp(6)
        items.forEachIndexed { index, (label, click) ->
            addView(button(label, primary = index == chosen) { click() }.apply { textSize = 13f; minimumHeight = dp(38); setPadding(dp(12), dp(4), dp(12), dp(4)) })
        }
    }

    /** A row of a list: title, line under it, no icon. */
    fun item(title: String, subtitle: String?, click: (() -> Unit)? = null) = column(12).apply {
        background = MobilePalette.rowShape(context)
        addView(text(title, 15f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (subtitle != null) addView(text(subtitle, 13f, MobilePalette.muted).apply { setPadding(0, dp(3), 0, 0) })
        if (click != null) { isFocusable = true; setOnClickListener { click() }; MobileFeedback.apply(this) }
    }

    /** Every server form: a bottom sheet with a title, its fields and, when given, the one button that applies them. */
    fun sheet(title: String, action: String? = null, apply: () -> Boolean = { true }, fill: (LinearLayout, MobileSheetDialog) -> Unit): MobileSheetDialog {
        val sheet = MobileSheetDialog(context)
        val body = column(20).apply { setBackgroundColor(MobilePalette.surface) }
        body.addView(text(title, 22f, bold = true).apply { setPadding(0, 0, 0, dp(4)) })
        fill(body, sheet)
        if (action != null) body.addView(button(action, primary = true) { if (apply()) sheet.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        sheet.setContentView(android.widget.ScrollView(context).apply { addView(body) }); sheet.show()
        return sheet
    }

    fun hint(parent: LinearLayout, value: String, color: Int = MobilePalette.muted) = parent.addView(text(value, 13f, color).apply { setPadding(0, dp(6), 0, 0) })

    fun heading(parent: LinearLayout, value: String) = parent.addView(text(value, 15f, bold = true).apply { setPadding(0, dp(14), 0, dp(2)) })

    /** The calls made in the client from the linked VK account, to pick instead of copying them over by hand. */
    fun pickCreatedCalls(limit: Int, chosen: (List<String>) -> Unit) {
        val created = runCatching { app.rawline.mobile.bg.carrier.VkRelayAccount.cachedHashes() }.getOrDefault(emptyList())
        if (created.isEmpty()) {
            android.widget.Toast.makeText(context, "Созданных звонков нет: создайте их в «Хешах» настроек VK-релея", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val checks = BooleanArray(created.size)
        MobileAlertDialogBuilder(context).setTitle("Созданные звонки").setMultiChoiceItems(created.toTypedArray(), checks) { _, index, on -> checks[index] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Выбрать") { _, _ -> chosen(created.filterIndexed { index, _ -> checks[index] }.take(limit)) }.show()
    }

    /** Shown only with a linked VK account. */
    fun createdCallsButton(parent: LinearLayout, limit: Int, chosen: (List<String>) -> Unit) {
        if (app.rawline.mobile.bg.carrier.VkRelayAccount.linked()) parent.addView(textButton("Выбрать из созданных в клиенте") { pickCreatedCalls(limit, chosen) })
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
            addView(copyButton("Скопировать: ${label ?: "ссылку"}") { app.rawline.mobile.SagerNet.trySetPrimaryClip(value) },
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
        setImageBitmap(app.rawline.mobile.widget.QrBitmap.encode(link, dp(size))); contentDescription = "QR-код подключения"
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = dp(12).toFloat() }
    }

    /** Puts the QR picture on the clipboard through the app's cache FileProvider (cache/qr only). */
    fun copyQr(link: String): Boolean = runCatching {
        val dir = java.io.File(context.cacheDir, "qr").apply { mkdirs() }
        val file = java.io.File(dir, "qr.png")
        val bitmap = app.rawline.mobile.widget.QrBitmap.encode(link, 720)
        val framed = android.graphics.Bitmap.createBitmap(bitmap.width + 80, bitmap.height + 80, android.graphics.Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 40f, 40f, null) }
        }
        file.outputStream().use { framed.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, app.rawline.mobile.BuildConfig.APPLICATION_ID + ".cache", file)
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

/** The server screens: every themed screen now shares the same transition. */
abstract class ServerScreenActivity(contentLayoutId: Int = 0) : ThemedActivity(contentLayoutId)
