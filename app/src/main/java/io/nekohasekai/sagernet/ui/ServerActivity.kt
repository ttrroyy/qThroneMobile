package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.QwdttAdmin
import app.rawline.mobile.deploy.WdttPlusAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A deployed server, as the original apps open it: the address, then its actions. qWDTT manages
 * accesses through its admin API, CSQTT through its web panel; "Управление сервером" is the deploy editor.
 */
class ServerActivity : ServerScreenActivity() {
    private lateinit var body: LinearLayout
    private lateinit var toolbar: Toolbar
    private var server: DeployServer? = null
    private val ui by lazy { ServerUi(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setSubtitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Управление сервером"
        }
        body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
            if (loaded == null) { Toast.makeText(this@ServerActivity, "Не удалось открыть VPS", Toast.LENGTH_LONG).show(); finish(); return@launch }
            server = loaded; render(loaded)
        }
    }

    private fun render(server: DeployServer) {
        toolbar.subtitle = server["host"]
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.card().background
            addView(ui.badge(R.drawable.ic_action_dns), LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { marginEnd = ui.dp(12) })
            addView(ui.column().apply {
                addView(ui.text(server["name"].ifBlank { server["host"] }, 16f, bold = true))
                addView(ui.text(buildString {
                    if (server["name"].isNotBlank()) append(server["host"]).append(" · ")
                    append("SSH ").append(server["sshPort"])
                    append(if (server["installed"] == "true") " · установлен" else " · не установлен")
                }, 14f, MobilePalette.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        body.addView(ui.section("Действия"))
        when (server.kind) {
            "qwdtt" -> {
                val admin = QwdttAdmin(server)
                body.addView(ui.tile("Пользователи и подписки", "Доступы, лимиты устройств и сроки действия", R.drawable.ic_mobile_people) {
                    if (admin.available) startActivity(Intent(this, QwdttAccessActivity::class.java).putExtra("server", server.id))
                    else Toast.makeText(this, "Доступно после установки сервера из приложения", Toast.LENGTH_LONG).show()
                })
            }
            "csqtt" -> body.addView(ui.tile("Панель сервера", "Пароли, устройства и звонки в веб-панели CSQTT", R.drawable.baseline_public_24) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(server.panelUrl())))
            })
            "wdttplus" -> listOf(
                Triple("Клиенты и сервер", "Пароли, хеши, устройства, сроки и настройки сервера", R.drawable.ic_mobile_people) to WdttPlusClientsActivity::class.java,
                Triple("Выходной IP и прокси", "Прокси, WARP, WireGuard или второй сервер для выхода", R.drawable.ic_hardware_router) to WdttPlusExitActivity::class.java,
                Triple("Резервные копии и перенос", "Расписание, проверка и выгрузка копий", R.drawable.ic_mobile_backup) to WdttPlusBackupsActivity::class.java,
            ).forEach { (tile, screen) ->
                body.addView(ui.tile(tile.first, tile.second, tile.third) {
                    if (WdttPlusAdmin(server).available) startActivity(Intent(this, screen).putExtra("server", server.id))
                    else Toast.makeText(this, "Доступно после установки сервера из приложения", Toast.LENGTH_LONG).show()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            }
        }
        body.addView(ui.gap(10))
        body.addView(ui.tile("Управление сервером", "SSH, порты, обновление и переустановка", R.drawable.ic_mobile_settings) {
            startActivity(Intent(this, DeployActivity::class.java).putExtra("kind", server.kind).putExtra("editServer", server.id)
                .putExtra("directEditor", true))
        })
    }
}
