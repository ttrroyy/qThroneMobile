package app.rawline.mobile.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.FreeturnAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The clients of a deployed FreeTurn server, as its app lists them: each has its own link and WireGuard peer. */
class FreeturnClientsActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var list: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var progress: ProgressBar
    private lateinit var admin: FreeturnAdmin
    private lateinit var server: DeployServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Клиенты FreeTurn"
            setTitleTextColor(MobilePalette.text); setSubtitleTextColor(MobilePalette.muted)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val content = ui.column(16)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = ui.text("", 14f, MobilePalette.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        list = ui.column()
        content.addView(ui.button("Добавить клиента", primary = true) { add() })
        content.addView(progress); content.addView(status); content.addView(list)
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
            if (loaded == null) { finish(); return@launch }
            server = loaded; admin = FreeturnAdmin(loaded); toolbar.subtitle = loaded["host"]; refresh()
        }
    }

    private fun <T> request(call: () -> T, done: (T) -> Unit) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call) }
            progress.visibility = View.GONE
            result.onSuccess(done).onFailure { status.text = it.message ?: it.javaClass.simpleName }
        }
    }

    private fun refresh(): Unit = request({ admin.clients() }) { clients ->
        status.text = ""
        list.removeAllViews()
        clients.forEach { client ->
            val subtitle = listOfNotNull(if (client.owner) "Этот сервер" else null, client.address.ifBlank { null }).joinToString(" · ").ifBlank { null }
            list.addView(ui.tile(client.name, subtitle, R.drawable.ic_baseline_vpn_key_24) { open(client) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
    }

    private fun open(client: FreeturnAdmin.Client): Unit = request({ admin.link(client.name) }) { link ->
        val dialog = MobileAlertDialogBuilder(this).setTitle(client.name)
            .setView(ui.column(16).apply { addView(ui.linkRow("Ссылка freeturn://", link, lines = 4)) })
            .setPositiveButton(android.R.string.ok, null)
        if (!client.owner) dialog.setNegativeButton("Удалить") { _, _ -> request({ admin.remove(client.name) }) { refresh() } }
        dialog.show()
    }

    private fun add() {
        val input = EditText(this).apply { hint = "Имя клиента"; setTextColor(MobilePalette.text); setHintTextColor(MobilePalette.muted); MobilePalette.tintCursor(this) }
        MobileAlertDialogBuilder(this).setTitle("Новый клиент").setView(ui.column(16).apply { addView(input) })
            .setPositiveButton("Добавить") { _, _ ->
                val name = input.text.toString().trim()
                // The installer's own rule for client names.
                if (name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) request({ admin.add(name) }) { refresh() }
                else status.text = "Имя: латинские буквы, цифры, точка, дефис, до 32 символов"
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
