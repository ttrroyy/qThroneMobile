package app.rawline.mobile.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.WdttPlusAdmin
import app.rawline.mobile.outbound.types.VkCallHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Клиенты и сервер» of WDTT Plus: its app's server clients section over the SSH admin interface. */
class WdttPlusClientsActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var body: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var search: EditText
    private lateinit var admin: WdttPlusAdmin
    private var state: WdttPlusAdmin.State? = null
    private var busy: String? = null
    private val dates = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Клиенты и сервер"
        }
        toolbar.menu.add("Обновить").setIcon(R.drawable.ic_mobile_refresh).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { refresh(); true }
        toolbar.menu.add("Новый клиент").setIcon(R.drawable.ic_mobile_add).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { createForm(); true }
        body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = ui.text("", 14f, MobilePalette.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) }
        search = ui.input("Поиск клиента").apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); background = ui.card().background
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { renderClients() }
            })
        }
        list = ui.column()
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
            if (loaded == null) { finish(); return@launch }
            admin = WdttPlusAdmin(loaded); toolbar.subtitle = loaded["host"]; refresh()
        }
    }

    private fun <T> request(key: String? = null, call: () -> T, done: (T) -> Unit) {
        busy = key; progress.visibility = View.VISIBLE; render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call) }
            busy = null; progress.visibility = View.GONE
            result.onSuccess(done).onFailure { status.text = it.message ?: it.javaClass.simpleName; render() }
        }
    }

    private fun refresh() {
        if (!this::admin.isInitialized) return
        request(null, { admin.list() }) { state = it; status.text = ""; render() }
    }

    private fun render() {
        if (!this::body.isInitialized) return
        body.removeAllViews()
        state?.let { body.addView(serverCard(it.server)) }
        body.addView(progress); body.addView(status)
        body.addView(ui.section("Клиенты"))
        body.addView(search); body.addView(list)
        renderClients()
    }

    private fun serverCard(server: org.json.JSONObject) = ui.card().apply {
        addView(ui.text("WDTT Plus " + server.optString("wdtt_plus_version").ifBlank { "—" }, 18f, bold = true))
        val address = server.optString("effective_public_ip").ifBlank { server.optString("public_ip") }
        if (address.isNotBlank()) addView(ui.text(address, 14f, MobilePalette.muted, mono = true))
        addView(LinearLayout(context).apply {
            setPadding(0, ui.dp(12), 0, 0)
            addView(ui.stat("Клиенты", "${server.optInt("active_password_count")}/${server.optInt("max_passwords")}"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Устройства", server.optInt("device_count").toString()), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Подключения", server.optInt("active_connections").toString()), LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(LinearLayout(context).apply {
            setPadding(0, ui.dp(10), 0, ui.dp(12))
            addView(ui.stat("CPU", String.format("%.0f%%", server.optDouble("cpu_percent", 0.0))), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Память", String.format("%.0f%%", server.optDouble("memory_percent", 0.0))), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Сеть", String.format("↓%.1f ↑%.1f Мбит/с", server.optDouble("network_rx_mbps", 0.0), server.optDouble("network_tx_mbps", 0.0))), LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(LinearLayout(context).apply {
            addView(ui.button("Настройки") { settingsForm(server) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            addView(ui.button("Обслуживание") { maintenance() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun renderClients() {
        if (!this::list.isInitialized) return
        list.removeAllViews()
        val query = search.text.toString().trim()
        state?.accesses.orEmpty().filter { query.isEmpty() || it.label.contains(query, true) || it.password.contains(query, true) || it.vkHash.contains(query, true) }
            .forEach { list.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) }) }
        if (state != null && state!!.accesses.isEmpty()) list.addView(ui.text("Клиентов пока нет. Создайте первого кнопкой «+».", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
    }

    private fun expires(access: WdttPlusAdmin.Access) = if (access.expiresAt > 0) dates.format(Date(access.expiresAt * 1000)) else "бессрочно"

    private fun card(access: WdttPlusAdmin.Access) = ui.card().apply {
        isFocusable = true; setOnClickListener { details(access) }; MobileFeedback.apply(this)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.column().apply {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.text(access.title, 18f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    val chip = when { access.deactivated -> "выключен"; access.expired -> "истёк"; access.deviceId.isNotBlank() -> "привязан"; else -> null }
                    if (chip != null) addView(ui.text(chip, 12f, if (access.deactivated || access.expired) android.graphics.Color.rgb(232, 170, 80) else android.graphics.Color.rgb(110, 200, 120))
                        .apply { setPadding(ui.dp(10), 0, 0, 0) })
                })
                if (access.label.isNotBlank()) addView(ui.text(access.password, 13f, MobilePalette.muted, mono = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ic_mobile_copy_outline, "Скопировать пароль") { app.rawline.mobile.SagerNet.trySetPrimaryClip(access.password) },
                LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        })
        addView(LinearLayout(context).apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            addView(ui.stat("Истекает", expires(access)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Устройство", access.deviceName.ifBlank { if (access.deviceId.isBlank()) "нет" else "привязано" }), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Трафик", ui.bytes(access.traffic("all"))), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (busy == access.password) addView(ProgressBar(context).apply { isIndeterminate = true })
        else addView(LinearLayout(context).apply {
            addView(ui.button(if (access.deactivated) "Включить" else "Выключить") {
                request(access.password, { admin.action(if (access.deactivated) "activate" else "deactivate", access.password) }) { refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            addView(ui.button("Удалить", danger = true) {
                MobileAlertDialogBuilder(context).setTitle("Удалить клиента?").setMessage("Доступ «${access.title}» и привязка устройства будут удалены.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Удалить") { _, _ -> request(access.password, { admin.action("delete", access.password) }) { refresh() } }.show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun sheet(title: String, fill: (LinearLayout, MobileSheetDialog) -> Unit) {
        val sheet = MobileSheetDialog(this)
        val body = ui.column(20).apply { setBackgroundColor(MobilePalette.surface) }
        body.addView(ui.text(title, 22f, bold = true))
        fill(body, sheet)
        sheet.setContentView(ScrollView(this).apply { addView(body) }); sheet.show()
    }

    private fun hashFields(body: LinearLayout, initial: List<String>): () -> List<String> {
        body.addView(ui.text("VK хеши", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(14), 0, 0) })
        val box = ui.column()
        fun add(value: String) {
            if (box.childCount >= 4) return
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val field = ui.input("VK хеш или ссылка на звонок", value)
            row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.iconButton(R.drawable.ic_navigation_close, "Убрать хеш") { if (box.childCount > 1) box.removeView(row) else field.setText("") },
                LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            box.addView(row)
        }
        initial.ifEmpty { listOf("") }.forEach(::add)
        body.addView(box)
        body.addView(LinearLayout(this).apply { addView(ui.textButton("+ Добавить хеш") { add("") }) })
        ui.createdCallsButton(body, 4) { picked ->
            val fields = (0 until box.childCount).map { (box.getChildAt(it) as LinearLayout).getChildAt(0) as EditText }
            for (hash in picked.filter { hash -> fields.none { VkCallHash.strip(it.text.toString()) == hash } }) {
                val empty = (0 until box.childCount).map { (box.getChildAt(it) as LinearLayout).getChildAt(0) as EditText }.firstOrNull { it.text.isBlank() }
                if (empty != null) empty.setText(hash) else add(hash)
            }
        }
        return { (0 until box.childCount).mapNotNull { ((box.getChildAt(it) as LinearLayout).getChildAt(0) as EditText).text.toString().let(VkCallHash::strip).takeIf(String::isNotEmpty) }.distinct() }
    }

    /** As in WDTT Plus: a name, VK hashes (may be empty), a term in days and an optional own password. */
    private fun createForm() = sheet("Новый клиент") { body, sheet ->
        val label = ui.field(body, "Имя (кому выдан)")
        val hashes = hashFields(body, emptyList())
        val days = ui.field(body, "Дней (0 — бессрочно)", "30", number = true)
        val password = ui.field(body, "Пароль (пусто — сгенерировать)")
        body.addView(ui.gap(12))
        body.addView(ui.button("Создать", primary = true) {
            val count = days.text.toString().toIntOrNull()?.takeIf { it in 0..365 } ?: return@button Toast.makeText(this, "Дней: от 0 до 365", Toast.LENGTH_SHORT).show()
            val own = password.text.toString().trim()
            if (own.isNotEmpty() && own.length !in 8..64) return@button Toast.makeText(this, "Пароль: от 8 до 64 символов", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            request(null, { admin.create(label.text.toString().trim(), hashes(), count, own) }) { refresh() }
        })
    }

    /** The client with its QR code and wdtt://connect link, device and every edit WDTT Plus offers. */
    private fun details(access: WdttPlusAdmin.Access) = sheet(access.title) { body, sheet ->
        val current = state ?: return@sheet
        admin.link(access, current)?.let { link ->
            body.addView(ui.linkRow("Ссылка WDTT Plus", link))
            body.addView(ui.qrRow(link))
        } ?: body.addView(ui.text("Ссылка появится после добавления VK-хеша", 13f, MobilePalette.muted))
        body.addView(ui.linkRow("Пароль", access.password, lines = 1))
        body.addView(LinearLayout(this).apply {
            setPadding(0, ui.dp(14), 0, 0)
            addView(ui.stat("Истекает", expires(access)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Сегодня", ui.bytes(access.traffic("today"))), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Месяц", ui.bytes(access.traffic("month"))), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (access.deviceId.isNotBlank()) {
            body.addView(ui.text("Устройство", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(16), 0, ui.dp(4)) })
            body.addView(ui.text(listOf(access.deviceName, access.deviceIp).filter(String::isNotBlank).joinToString(" · ").ifBlank { access.deviceId }, 14f))
            body.addView(ui.button("Отвязать устройство") { sheet.dismiss(); request(access.password, { admin.action("unbind", access.password) }) { refresh() } })
        }
        body.addView(ui.section("Изменить"))
        val label = ui.field(body, "Имя", access.label)
        val hashes = hashFields(body, access.hashes)
        val days = ui.field(body, "Новый срок в днях (пусто — без изменений)", "", number = true)
        val password = ui.field(body, "Новый пароль (пусто — без изменений)")
        body.addView(ui.gap(12))
        body.addView(ui.button("Сохранить", primary = true) {
            val newLabel = label.text.toString().trim(); val newHashes = hashes()
            val newDays = days.text.toString().takeIf(String::isNotBlank)?.toIntOrNull()
            val newPassword = password.text.toString().trim()
            if (days.text.isNotBlank() && (newDays == null || newDays !in 0..365)) return@button Toast.makeText(this, "Дней: от 0 до 365", Toast.LENGTH_SHORT).show()
            if (newPassword.isNotEmpty() && newPassword.length !in 8..64) return@button Toast.makeText(this, "Пароль: от 8 до 64 символов", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            request(access.password, {
                if (newLabel != access.label) admin.setLabel(access.password, newLabel)
                if (newHashes != access.hashes) admin.setHash(access.password, newHashes)
                newDays?.let { admin.setExpiry(access.password, it) }
                if (newPassword.isNotEmpty()) admin.setPassword(access.password, newPassword)
            }) { refresh() }
        })
    }

    private fun settingsForm(server: org.json.JSONObject) = sheet("Настройки сервера") { body, sheet ->
        val dns = ui.field(body, "DNS для клиентов", server.optString("dns"))
        val limit = ui.field(body, "Лимит доступов", server.optInt("max_passwords", 50).toString(), number = true)
        val ports = ui.field(body, "Порты по умолчанию (DTLS,WG,локальный)", server.optString("default_ports"))
        val host = ui.field(body, "Публичный адрес (пусто — авто)", server.optString("public_ip"))
        body.addView(ui.gap(12))
        body.addView(ui.button("Сохранить", primary = true) {
            val value = limit.text.toString().toIntOrNull()?.takeIf { it in 1..500 } ?: return@button Toast.makeText(this, "Лимит: от 1 до 500", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            request(null, { admin.updateSettings(dns.text.toString().trim(), value, ports.text.toString().trim(), host.text.toString().trim()) }) { refresh() }
        })
    }

    private fun maintenance() = sheet("Обслуживание") { body, sheet ->
        for ((command, label) in listOf("refresh-public-ip" to "Определить публичный адрес заново", "cleanup-expired" to "Удалить истёкших клиентов",
            "cleanup-orphans" to "Отвязать сиротские устройства", "reset-traffic" to "Сбросить статистику трафика", "restart" to "Перезапустить сервер")) {
            body.addView(ui.button(label) {
                MobileAlertDialogBuilder(this).setMessage("$label?").setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> sheet.dismiss(); request(null, { admin.maintenance(command) }) { refresh() } }.show()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
    }
}
