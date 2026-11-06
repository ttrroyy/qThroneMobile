package app.qthrone.mobile.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.outbound.json.JsonArray
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.route.ProviderRouting
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.ui.route.RouteProfileActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Happ / Incy routing profile of one's own, no subscription needed: the same document those apps share. */
class ProviderRouteActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var name: EditText
    private var document = JsonObject()
    private var provider = "Happ"
    private var globalProxy = true
    private var existing: RouteProfile? = null
    private val lists = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        val toolbar = Toolbar(this).apply {
            title = getString(R.string.provider_route_new); setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.ic_navigation_close); setNavigationOnClickListener { finish() }
            menu.add(android.R.string.ok).setIcon(R.drawable.ic_baseline_check_circle_24).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { save(); true }
        }
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        lifecycleScope.launch {
            val id = intent.getLongExtra(EXTRA_ID, 0L)
            existing = if (id > 0) withContext(Dispatchers.IO) { RouteManager.get(id) } else null
            existing?.let { ProviderRouting.stored(it) }?.let { (source, stored) -> provider = source; document = stored.copy() }
            globalProxy = ProviderRouting.truth(document, "GlobalProxy", true)
            build(body)
        }
    }

    private fun build(body: LinearLayout) {
        fun card(vararg rows: android.view.View) = ui.card(12).apply {
            rows.forEachIndexed { index, row ->
                addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = ui.dp(10) })
            }
        }
        fun field(hint: String, value: String, lines: Boolean = false) = ui.input(hint, value).apply {
            background = ui.well(); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            MobilePalette.tintCursor(this)
            if (lines) {
                isSingleLine = false; minLines = 2; gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
        name = field(getString(R.string.provider_route_name), document.string("Name"))
        body.addView(card(
            name,
            ui.text(getString(R.string.provider_route_format), 14f, MobilePalette.muted),
            segments(listOf("Happ", "Incy"), listOf("Happ", "Incy").indexOf(provider).coerceAtLeast(0)) { provider = listOf("Happ", "Incy")[it] },
            ui.text(getString(R.string.provider_route_default), 14f, MobilePalette.muted),
            segments(listOf(getString(R.string.provider_route_proxy), getString(R.string.provider_route_direct)), if (globalProxy) 0 else 1) { globalProxy = it == 0 },
        ), LinearLayout.LayoutParams(-1, -2))
        for ((title, prefix) in listOf(
            R.string.provider_proxy to "Proxy", R.string.provider_direct to "Direct", R.string.provider_block to "Block",
        )) {
            body.addView(ui.section(getString(title)))
            val sites = field(getString(R.string.provider_route_sites), document.array(prefix + "Sites").strings().joinToString("\n"), true)
            val ips = field(getString(R.string.provider_route_ips), document.array(prefix + "Ip").strings().joinToString("\n"), true)
            lists[prefix + "Sites"] = sites; lists[prefix + "Ip"] = ips
            body.addView(card(sites, ips), LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(ui.text(getString(R.string.provider_route_new_note), 13f, MobilePalette.muted).apply { setPadding(ui.dp(6), ui.dp(14), ui.dp(6), 0) })
    }

    /** One choice out of equal parts, like the outbound choice of a routing profile. */
    private fun segments(labels: List<String>, selected: Int, choose: (Int) -> Unit) = LinearLayout(this).apply {
        val parts = ArrayList<TextView>()
        fun paint(active: Int) = parts.forEachIndexed { index, part ->
            val on = index == active
            part.setTextColor(if (on) MobilePalette.background else MobilePalette.text)
            part.background = GradientDrawable().apply {
                cornerRadius = ui.dp(5).toFloat(); setColor(if (on) ui.accent else MobilePalette.surface); setStroke(1, MobilePalette.outline)
            }
        }
        labels.forEachIndexed { index, label ->
            val part = ui.text(label, 15f, bold = true).apply {
                gravity = Gravity.CENTER; minimumHeight = ui.dp(42); isFocusable = true
                setOnClickListener { paint(index); choose(index) }; MobileFeedback.apply(this)
            }
            parts.add(part)
            addView(part, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = ui.dp(8) })
        }
        paint(selected)
    }

    private fun save() {
        document["Name"] = name.text.toString().trim().ifEmpty { "$provider Default" }
        document["GlobalProxy"] = globalProxy.toString()
        for ((key, input) in lists) document[key] = JsonArray().apply {
            input.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        }
        val profile = runCatching { ProviderRouting.profile(document, provider) }.getOrElse {
            Toast.makeText(this, it.message ?: getString(R.string.route_import_invalid), Toast.LENGTH_LONG).show()
            return
        }
        existing?.let { profile.id = it.id }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { RouteManager.save(profile) }
            setResult(Activity.RESULT_OK, Intent().putExtra(RouteProfileActivity.EXTRA_SAVED_ID, id))
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "routeId"
    }
}
