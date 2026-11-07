package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.FreeturnAdmin
import app.rawline.mobile.deploy.FreeturnRpc
import app.rawline.mobile.deploy.SshSessions
import app.rawline.mobile.outbound.types.Freeturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The clients of a deployed FreeTurn server, as its app lists them: each has its own link and WireGuard peer.
 * The server keeps no calls; the ones given here are kept with the server in the app and go into the client's link.
 */
class FreeturnClientsActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var list: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var spinner: ProgressBar
    private lateinit var admin: FreeturnAdmin
    private lateinit var server: DeployServer
    private val links = HashMap<String, String>()
    private var busy = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Клиенты FreeTurn"
            setTitleTextColor(MobilePalette.text); setSubtitleTextColor(MobilePalette.muted)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        spinner = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(MobilePalette.text) }
        toolbar.addView(spinner, Toolbar.LayoutParams(ui.dp(24), ui.dp(24), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(16) })
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        status = ui.text("", 14f, MobilePalette.muted).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8)); visibility = View.GONE }
        list = ui.column()
        content.addView(ui.button("Добавить клиента", primary = true) { add() })
        content.addView(status); content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
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
            server = loaded; admin = FreeturnAdmin(loaded); toolbar.subtitle = loaded["name"].ifBlank { loaded["host"] }; refresh()
        }
    }

    override fun onDestroy() {
        if (isFinishing && this::server.isInitialized) SshSessions.close(server)
        super.onDestroy()
    }

    private fun <T> request(call: () -> T, failed: (String) -> Unit = { status.text = it; status.visibility = View.VISIBLE }, done: (T) -> Unit) {
        busy++; spinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call) }
            if (--busy == 0) spinner.visibility = View.GONE
            result.onSuccess(done).onFailure { failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun calls(): JSONObject = runCatching { JSONObject(server["ftCalls"]) }.getOrDefault(JSONObject())

    private fun refresh(): Unit = request({ admin.clients() }) { clients ->
        status.visibility = View.GONE
        list.removeAllViews()
        if (clients.isEmpty()) list.addView(ui.text("Клиентов пока нет", 14f, MobilePalette.muted))
        clients.forEach { client ->
            val saved = calls().optString(client.name)
            val subtitle = listOfNotNull(if (client.owner) "Этот сервер" else null, client.address.ifBlank { null },
                if (saved.isBlank()) null else "звонков: ${Freeturn.callLinks(saved).size}").joinToString(" · ").ifBlank { null }
            list.addView(ui.item(client.name, subtitle) { open(client) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
    }

    /** Opens at once; the link comes into it when the server answers. */
    private fun open(client: FreeturnAdmin.Client) {
        lateinit var share: LinearLayout
        lateinit var callsField: EditText
        fun show() {
            share.removeAllViews()
            val link = links[client.name] ?: return share.addView(ui.text("Загрузка ссылки...", 14f, MobilePalette.muted))
            val full = runCatching { FreeturnRpc.withCalls(link, Freeturn.callLinks(callsField.text.toString()).joinToString("\n")) }.getOrDefault(link)
            share.addView(ui.qrRow(full)); share.addView(ui.linkRow("Ссылка freeturn://", full, lines = 4))
            if (Freeturn.callLinks(callsField.text.toString()).isEmpty()) ui.hint(share, "В ссылке нет звонка: клиент вставит свою ссылку на звонок VK или возьмёт звонок со своего аккаунта.")
            share.addView(ui.button("Поделиться") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, full), client.name))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
        lateinit var sheet: MobileSheetDialog
        sheet = ui.sheet(client.name) { body, _ ->
            share = ui.column().also { body.addView(it) }
            ui.heading(body, "Звонки VK в ссылке")
            callsField = ui.field(body, "Ссылки на звонки или хеши, по одной в строке", calls().optString(client.name), multiline = true)
            ui.createdCallsButton(body, 16) { picked ->
                val current = Freeturn.callLinks(callsField.text.toString())
                callsField.setText((current + picked.map { Freeturn.CALL_LINK + it }).distinct().joinToString("\n"))
            }
            body.addView(ui.button("Сохранить звонки") {
                val text = Freeturn.callLinks(callsField.text.toString()).joinToString("\n")
                callsField.setText(text)
                server["ftCalls"] = calls().put(client.name, text).toString()
                val snapshot = DeployServer(JSONObject(server.json.toString()))
                request({ DeployStore.save(snapshot) }, { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }) { show(); refresh(); Toast.makeText(this, "Звонки сохранены", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            if (!client.owner) body.addView(ui.textButton("Удалить клиента", danger = true) {
                MobileAlertDialogBuilder(this).setTitle("Удалить клиента?").setMessage("Ссылка «${client.name}» перестанет работать.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Удалить") { _, _ -> sheet.dismiss(); request({ admin.remove(client.name) }) { refresh() } }.show()
            })
        }
        show()
        if (links[client.name] == null) request({ admin.link(client.name) }, { share.removeAllViews(); ui.hint(share, it, android.graphics.Color.rgb(232, 120, 112)) }) {
            links[client.name] = it; if (sheet.isShowing) show()
        }
    }

    private fun add() {
        lateinit var input: EditText
        ui.sheet("Новый клиент", "Добавить", {
            val name = input.text.toString().trim()
            // The installer's own rule for client names.
            if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
                Toast.makeText(this, "Имя: латинские буквы, цифры, точка, дефис, до 32 символов", Toast.LENGTH_LONG).show(); return@sheet false
            }
            request({ admin.add(name) }) { links[name] = it; refresh() }; true
        }) { body, _ -> input = ui.field(body, "Имя клиента (латиница)") }
    }
}
