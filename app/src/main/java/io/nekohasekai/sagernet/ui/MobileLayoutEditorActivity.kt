package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import java.util.Locale

/** Where the home screen's parts stand: the same answers for the home screen and its editor. */
object MobileHomeLayout {
    private val store get() = DataStore.configurationStore
    private fun position(bottom: Boolean) = if (bottom) "bottom" else "top"

    var tilesBottom: Boolean
        get() = if (MobileStyle.new) store.getString("qmobileNewTilesPosition") == "bottom"
            else (store.getString("qmobileTilesPosition") ?: position(store.getBoolean("qmobileTilesBottom", false))) == "bottom"
        set(value) = store.putString(if (MobileStyle.new) "qmobileNewTilesPosition" else "qmobileTilesPosition", position(value))
    var extendedTiles: Boolean
        get() = if (MobileStyle.new) store.getString("qmobileNewTiles") == "on" else store.getString("qmobileHomeTilesMode") != "standard"
        set(value) = if (MobileStyle.new) store.putString("qmobileNewTiles", if (value) "on" else "off")
            else store.putString("qmobileHomeTilesMode", if (value) "extended" else "standard")
    var controlBottom: Boolean
        get() = store.getString("qmobileControlPosition") == "bottom"
        set(value) = store.putString("qmobileControlPosition", position(value))
    var controlSwitches: Boolean
        get() = store.getString("qmobileHomeControl") == "switches"
        set(value) = store.putString("qmobileHomeControl", if (value) "switches" else "button")
    /** The classic style has no subscription row. */
    var chipBottom: Boolean
        get() = MobileStyle.new && store.getString("qmobileChipPosition") == "bottom"
        set(value) = store.putString("qmobileChipPosition", position(value))
    /** Between the connection control and the servers, rather than on the control's outer side. */
    var chipNearList: Boolean
        get() = MobileStyle.new && store.getBoolean("qmobileChipNearList", false)
        set(value) = store.putBoolean("qmobileChipNearList", value)
}

/** A miniature of the home screen in the current style: hold a part to move it above or below the server list. */
class MobileLayoutEditorActivity : ServerScreenActivity() {
    private enum class Part(val title: Int) {
        TILES(R.string.qmobile_layout_tiles), CHIP(R.string.qmobile_layout_chip), CONTROL(R.string.qmobile_layout_control)
    }

    private val ui by lazy { ServerUi(this) }
    private fun dp(n: Int) = ui.dp(n)
    private val accent get() = MobilePalette.accent(this)
    private val parts = mutableMapOf<Part, FrameLayout>()
    private val dashes = mutableMapOf<Part, GradientDrawable>()
    private lateinit var column: LinearLayout
    private lateinit var list: View
    private var dragged: Part? = null
    private var grabY = 0f
    private var fingerY = 0f
    private var blink: ValueAnimator? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Toolbar(this).apply {
            title = getString(R.string.qmobile_edit_layout)
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)); clipChildren = false
            background = MobileHomeBackdrop()
        }
        list = serverList()
        val available = if (MobileStyle.new) Part.values().toList() else listOf(Part.TILES, Part.CONTROL)
        available.forEach { parts[it] = frameFor(it); fill(it) }
        arrange()

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(12)) }
        body.addView(FrameLayout(this).apply { addView(Miniature(this@MobileLayoutEditorActivity), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
            if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.card().apply {
            for (hint in listOf(R.string.qmobile_layout_hint_move, R.string.qmobile_layout_hint_tiles, R.string.qmobile_layout_hint_control)) addView(ui.text(getString(hint), 14f, MobilePalette.muted).apply { setPadding(dp(4), dp(3), dp(4), dp(3)) })
        }, if (landscape) LinearLayout.LayoutParams(dp(260), -2).apply { marginStart = dp(12); gravity = Gravity.CENTER_VERTICAL } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        blink = ValueAnimator.ofInt(255, 60).apply {
            duration = 650; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator -> dashes.forEach { (part, dash) -> dash.alpha = if (part == dragged) 255 else animator.animatedValue as Int } }
            start()
        }
    }

    override fun onStop() {
        blink?.cancel(); blink = null
        super.onStop()
    }

    private fun isBottom(part: Part) = when (part) {
        Part.TILES -> MobileHomeLayout.tilesBottom
        Part.CHIP -> MobileHomeLayout.chipBottom
        Part.CONTROL -> MobileHomeLayout.controlBottom
    }

    private fun place(part: Part, bottom: Boolean) {
        when (part) {
            Part.TILES -> MobileHomeLayout.tilesBottom = bottom
            Part.CHIP -> MobileHomeLayout.chipBottom = bottom
            Part.CONTROL -> MobileHomeLayout.controlBottom = bottom
        }
        // The control looks different at the bottom, in a panel of its own.
        if (part == Part.CONTROL) fill(part)
        rearrange()
    }

    private fun rearrange() {
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180).apply { dragged?.let { excludeTarget(parts.getValue(it), true) } })
        arrange()
    }

    /** The home screen's order: from the top edge tiles, then subscription and control in either order; the same ones mirrored under the list. */
    private fun arrange() {
        column.removeAllViews()
        val order = (if (MobileHomeLayout.chipNearList) listOf(Part.TILES, Part.CONTROL, Part.CHIP) else listOf(Part.TILES, Part.CHIP, Part.CONTROL)).filter { it in parts }
        fun add(part: Part) = column.addView(parts.getValue(part), LinearLayout.LayoutParams(-1, -2))
        order.filterNot(::isBottom).forEach(::add)
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        order.reversed().filter(::isBottom).forEach(::add)
    }

    private fun frameFor(part: Part) = FrameLayout(this).apply {
        setPadding(dp(4), dp(5), dp(4), dp(5))
        val dash = GradientDrawable().apply {
            cornerRadius = dp(MobilePalette.rowDp).toFloat()
            setStroke(dp(2), accent, dp(7).toFloat(), dp(5).toFloat())
        }
        dashes[part] = dash
        foreground = dash
        contentDescription = getString(part.title)
        // Touches go through the miniature; a remote, keyboard or screen reader gets the same choices as a menu.
        tag = MobileFeedback.NONE
        isFocusable = true
        setOnClickListener { menu(part, it) }
        setOnFocusChangeListener { view, focused -> view.animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f).setDuration(120).start() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (dragged == part) follow() }
    }

    private fun menu(part: Part, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val positions = resources.getStringArray(R.array.qmobile_tiles_positions)
        positions.forEachIndexed { index, label ->
            popup.menu.add(label).setOnMenuItemClickListener {
                if (part == Part.CHIP) MobileHomeLayout.chipNearList = false
                place(part, index == 1); anchor.requestFocus(); true
            }
        }
        if (part == Part.CHIP) popup.menu.add(R.string.qmobile_layout_chip_near).setOnMenuItemClickListener {
            MobileHomeLayout.chipNearList = true
            place(part, MobileHomeLayout.controlBottom); anchor.requestFocus(); true
        }
        if (part == Part.CONTROL) resources.getStringArray(R.array.qmobile_home_control_entries).forEachIndexed { index, label ->
            popup.menu.add(label).setOnMenuItemClickListener { setSwitches(index == 1); true }
        }
        if (part == Part.TILES) resources.getStringArray(R.array.qmobile_home_tiles_entries).forEachIndexed { index, label ->
            popup.menu.add(label).setOnMenuItemClickListener { setExtended(index == 0); true }
        }
        popup.show()
    }

    private fun setSwitches(switches: Boolean) {
        MobileHomeLayout.controlSwitches = switches
        fill(Part.CONTROL)
        val modes = resources.getStringArray(R.array.qmobile_home_control_entries)
        MobileTopNotice.show(this, getString(R.string.qmobile_home_control) + ": " + modes[if (switches) 1 else 0])
    }

    private fun setExtended(extended: Boolean) {
        MobileHomeLayout.extendedTiles = extended
        fill(Part.TILES)
        val modes = resources.getStringArray(R.array.qmobile_home_tiles_entries)
        MobileTopNotice.show(this, getString(R.string.qmobile_home_tiles) + ": " + modes[if (extended) 0 else 1])
    }

    private fun fill(part: Part) {
        val box = parts.getValue(part)
        box.removeAllViews()
        box.addView(when (part) {
            Part.TILES -> tiles()
            Part.CHIP -> chip()
            Part.CONTROL -> control()
        }, FrameLayout.LayoutParams(-1, -2))
    }

    private fun icon(res: Int, tint: Int = MobilePalette.text) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); background = MobilePalette.rowShape(context); setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun tiles() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        val extended = MobileHomeLayout.extendedTiles
        val new = MobileStyle.new
        if (extended) {
            val icons = listOf(R.drawable.ic_mobile_settings, R.drawable.ic_mobile_tun, R.drawable.ic_mobile_route, R.drawable.ic_mobile_subscriptions, R.drawable.ic_mobile_update) + if (new) emptyList() else listOf(R.drawable.ic_mobile_add)
            icons.forEachIndexed { index, res -> addView(icon(res), LinearLayout.LayoutParams(dp(48), dp(48)).apply { if (index < icons.lastIndex) marginEnd = dp(8) }) }
        } else {
            addView(icon(if (new) R.drawable.ic_mobile_more else R.drawable.ic_mobile_settings), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(ui.text(getString(R.string.app_name), 18f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(if (new) R.drawable.ic_mobile_settings else R.drawable.ic_mobile_add), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun chip() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(getString(R.string.subscription), 16f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setPadding(dp(16), 0, dp(12), 0); background = MobilePalette.rowShape(context)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        addView(icon(R.drawable.ic_mobile_add), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun check() = ui.text(getString(R.string.qmobile_check_connection), 12f, MobilePalette.muted).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun control(): View {
        val new = MobileStyle.new
        val bottom = MobileHomeLayout.controlBottom
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun status() = box.addView(check().apply { setPadding(dp(10), 0, dp(6), 0) }, LinearLayout.LayoutParams(-1, dp(40)))
        if (MobileHomeLayout.controlSwitches) {
            if (new && bottom) status()
            box.addView(LinearLayout(this).apply {
                isBaselineAligned = false
                listOf(R.string.service_mode_vpn, R.string.service_mode_proxy).forEachIndexed { index, label ->
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(14), dp(2), dp(8), dp(2)); background = MobilePalette.rowShape(context)
                        addView(ui.text(getString(label), 14f), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(SwitchCompat(context).apply { isClickable = false; isFocusable = false })
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2))
            if (new && !bottom) status()
            return box
        }
        val size = dp(if (new) 190 else 188)
        val orb = View(this).apply {
            background = if (new) MobileOrbArtwork(context, getString(R.string.qmobile_orb_idle))
                else MobilePowerArtwork(accent).apply { statusText = getString(R.string.qmobile_connect) }
        }
        val centre = FrameLayout(this)
        centre.addView(orb, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (new && bottom) {
            centre.background = MobileDockGlass(MobilePalette.cardDp * resources.displayMetrics.density, MobileOrbArtwork.centerY(size), MobileOrbArtwork.radius(size) * 1.26f, dp(104).toFloat())
            centre.addView(check().apply { maxLines = 3 }, FrameLayout.LayoutParams(dp(92), -2, Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(14) })
        }
        box.addView(centre, LinearLayout.LayoutParams(-1, dp(if (new) 194 else 206)))
        if (new && !bottom) status()
        return box
    }

    /** Stays put and only frames the rest; it takes whatever height the movable parts leave. */
    private fun serverList() = FrameLayout(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        alpha = .55f
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (!MobileStyle.new) addView(check(), LinearLayout.LayoutParams(-1, dp(32)))
            for (code in listOf("de", "fi", "nl", "se", "us", "jp", "gb", "ca", "fr", "pl")) addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0); background = MobilePalette.rowShape(context)
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(7).toFloat() }
                    MobileCountryFlags.bitmap(context, code)?.let(::setImageBitmap)
                }, LinearLayout.LayoutParams(dp(30), dp(22)).apply { marginEnd = dp(12) })
                addView(ui.text(Locale("", code).displayCountry, 15f).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        }, FrameLayout.LayoutParams(-1, -2))
    }

    private fun partAt(y: Float) = parts.entries.firstOrNull { y >= it.value.top && y < it.value.bottom }?.key

    private fun startDrag(part: Part, y: Float) {
        val frame = parts.getValue(part)
        dragged = part
        fingerY = y
        grabY = y - frame.top
        frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        frame.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(8).toFloat()).setDuration(120).start()
    }

    private fun drag(y: Float) {
        val part = dragged ?: return
        fingerY = y
        val bottom = y > list.top + list.height / 2f
        if (bottom != isBottom(part)) place(part, bottom)
        // On the control's side the finger also chooses which side of the control the subscription takes.
        if (part == Part.CHIP && bottom == MobileHomeLayout.controlBottom) {
            val control = parts.getValue(Part.CONTROL)
            val middle = control.top + control.height / 2f
            val near = if (bottom) y < middle else y > middle
            if (near != MobileHomeLayout.chipNearList) { MobileHomeLayout.chipNearList = near; rearrange() }
        }
        follow()
    }

    private fun follow() {
        val frame = dragged?.let(parts::getValue) ?: return
        frame.translationY = (fingerY - grabY).coerceIn(0f, (column.height - frame.height).coerceAtLeast(0).toFloat()) - frame.top
    }

    private fun drop() {
        val frame = dragged?.let(parts::getValue) ?: return
        dragged = null
        frame.animate().translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160).start()
    }

    /** The whole screen at the phone's own proportions, scaled to the room there is; every touch lands here. */
    private inner class Miniature(context: Context) : ViewGroup(context) {
        private var scale = 1f
        private val virtualWidth = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        private val virtualHeight = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onDoubleTap(event: MotionEvent): Boolean {
                when (partAt(event.y / scale)) {
                    Part.TILES -> setExtended(!MobileHomeLayout.extendedTiles)
                    Part.CONTROL -> setSwitches(!MobileHomeLayout.controlSwitches)
                    else -> {}
                }
                return true
            }
            override fun onLongPress(event: MotionEvent) { partAt(event.y / scale)?.let { startDrag(it, event.y / scale) } }
        })

        init {
            addView(column)
            background = GradientDrawable().apply { setColor(MobilePalette.background); cornerRadius = dp(MobilePalette.cardDp).toFloat() }
            foreground = GradientDrawable().apply { cornerRadius = dp(MobilePalette.cardDp).toFloat(); setStroke(dp(1).coerceAtLeast(1), MobilePalette.outline) }
            clipToOutline = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            scale = minOf(1f, MeasureSpec.getSize(widthSpec).toFloat() / virtualWidth, MeasureSpec.getSize(heightSpec).toFloat() / virtualHeight)
            column.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension((virtualWidth * scale).toInt(), (virtualHeight * scale).toInt())
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            column.layout(0, 0, virtualWidth, virtualHeight)
            column.pivotX = 0f; column.pivotY = 0f
            column.scaleX = scale; column.scaleY = scale
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> drag(event.y / scale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
            }
            return true
        }
    }
}
