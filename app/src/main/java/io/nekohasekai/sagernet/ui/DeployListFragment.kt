package app.qthrone.mobile.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.DeployEngine
import app.qthrone.mobile.deploy.DeployServer
import app.qthrone.mobile.deploy.DeployStore
import app.qthrone.mobile.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lightweight settings list; encrypted VPS credentials are read off the UI thread. */
class DeployListFragment : Fragment() {
    private lateinit var body: LinearLayout
    private var load: Job? = null
    private var cachedItems: List<DeployServer> = emptyList()
    private var openSwipe: MobileSwipeTile? = null
    private val kind get() = arguments?.getString("kind").takeIf { it in listOf("qwdtt", "csqtt", "wdttplus") } ?: "qwdtt"
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? =
        MobileScreenMotion.create(this, nextAnim)

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        render(cachedItems)
        return ScrollView(requireContext()).apply {
            setBackgroundColor(MobilePalette.screen); isVerticalScrollBarEnabled = false
            addView(body); applyListInsets()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        load?.cancel()
        load = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                DeployStore.savedServers().filter { it.kind == kind }
            } }
            result.onSuccess { items -> cachedItems = items; render(items) }
                .onFailure { Toast.makeText(requireContext(), "Не удалось загрузить список VPS", Toast.LENGTH_LONG).show() }
        }
    }

    private fun render(items: List<DeployServer>) {
        body.removeAllViews(); openSwipe = null
        items.forEach { server ->
            tile(server["name"], server["host"], R.drawable.ic_mobile_core, server) { open(server.id) }
        }
        tile("Добавить VPS для деплоя", null, R.drawable.ic_mobile_add) { open(null) }
    }

    private fun open(id: String?) {
        // A saved server opens on its actions, as in the original apps; a new one goes to the editor.
        if (id != null) { startActivity(Intent(requireContext(), ServerActivity::class.java).putExtra("server", id)); return }
        startActivity(Intent(requireContext(), DeployActivity::class.java).putExtra("kind", kind)
            .putExtra("directEditor", true))
    }

    private fun tile(title: String, subtitle: String?, icon: Int, server: DeployServer? = null, click: () -> Unit) {
        fun background() = GradientDrawable().apply { setColor(MobilePalette.surface); cornerRadius=dp(MobilePalette.rowDp).toFloat(); setStroke(1,MobilePalette.outline) }
        val tile = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumHeight = dp(48); this.background = background()
            isFocusable = true; setOnClickListener { click() }; MobileFeedback.apply(this)
        }
        tile.addView(ImageView(requireContext()).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(MobilePalette.accent(context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        tile.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 16f; setTypeface(typeface,android.graphics.Typeface.BOLD); setTextColor(MobilePalette.text) })
            if (subtitle != null) addView(TextView(context).apply {
                text = subtitle; textSize = 14f; setTextColor(MobilePalette.muted); setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val entry: View = if (server == null) tile else {
            val delete = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_mobile_delete); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setBackgroundColor(Color.rgb(220, 66, 61)); setPadding(dp(18), dp(18), dp(18), dp(18))
                contentDescription = "Удалить VPS из списка"
                setOnClickListener {
                    if (DeployEngine.state.value.running && DeployEngine.state.value.serverId == server.id)
                        Toast.makeText(context, "Дождитесь завершения операции", Toast.LENGTH_SHORT).show()
                    else MobileAlertDialogBuilder(context).setMessage("Удалить VPS из списка? Сервис на сервере останется.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Удалить") { _, _ -> viewLifecycleOwner.lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { DeployStore.remove(server.id) } }
                            if (result.isSuccess) refresh()
                            else Toast.makeText(context, "Не удалось удалить VPS", Toast.LENGTH_LONG).show()
                        } }.show()
                }
            }
            MobileSwipeTile(requireContext(), tile, View(requireContext()), View(requireContext()), delete, deleteOnly = true).apply {
                this.background = background(); clipToOutline = true
                onOpened = { opened -> openSwipe?.takeIf { it !== opened }?.closeActions(); openSwipe = opened }
            }
        }
        body.addView(entry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}
