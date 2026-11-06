package app.qthrone.mobile.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.DeployServer
import app.qthrone.mobile.deploy.DeployStore
import app.qthrone.mobile.deploy.QwdttAdmin
import app.qthrone.mobile.outbound.types.VkCallHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Пользователи и подписки» of a qWDTT server, the original app's access list over the admin API. */
class QwdttAccessActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var list: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var progress: ProgressBar
    private lateinit var search: android.widget.EditText
    private lateinit var server: DeployServer
    private lateinit var admin: QwdttAdmin
    private var items: List<QwdttAdmin.Access> = emptyList()
    private var busy: String? = null
    private val dates = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Пользователи и подписки"
        }
        toolbar.menu.add("Обновить").setIcon(R.drawable.ic_mobile_refresh).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { refresh(); true }
        toolbar.menu.add("Новый доступ").setIcon(R.drawable.ic_mobile_add).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { form(null); true }
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        search = ui.input("Поиск").apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); background = ui.card().background
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
        }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = ui.text("", 14f, MobilePalette.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, 0) }
        list = ui.column()
        content.addView(search); content.addView(progress); content.addView(status); content.addView(list)
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
            server = loaded; admin = QwdttAdmin(loaded); toolbar.subtitle = loaded["host"]; refresh()
        }
    }

    /** Runs a server call off the UI thread; failures land in the status line, as in the original. */
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
        request(null, { admin.list() }) { items = it; status.text = if (it.isEmpty()) "Доступов пока нет. Создайте первый кнопкой «+»." else ""; render() }
    }

    private fun render() {
        if (!this::list.isInitialized) return
        list.removeAllViews()
        val query = search.text.toString().trim()
        items.filter { query.isEmpty() || it.label.contains(query, true) || it.password.contains(query, true) || it.vkHash.contains(query, true) }
            .forEach { list.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) }) }
    }

    private fun expires(access: QwdttAdmin.Access) = if (access.expiresAt > 0) dates.format(Date(access.expiresAt * 1000)) else "бессрочно"

    private fun card(access: QwdttAdmin.Access) = ui.card().apply {
        isFocusable = true; setOnClickListener { details(access) }; MobileFeedback.apply(this)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.column().apply {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.text(access.title, 18f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    val chip = when { access.deactivated -> "выключен"; access.activeDevices > 0 -> "активен"; else -> null }
                    if (chip != null) addView(ui.text(chip, 12f, if (access.deactivated) android.graphics.Color.rgb(232, 170, 80) else android.graphics.Color.rgb(110, 200, 120))
                        .apply { setPadding(ui.dp(10), 0, 0, 0) })
                })
                if (access.label.isNotBlank()) addView(ui.text(access.password, 13f, MobilePalette.muted, mono = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ic_mobile_copy_outline, "Скопировать пароль") {
                app.qthrone.mobile.SagerNet.trySetPrimaryClip(access.password)
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        })
        addView(LinearLayout(context).apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            addView(ui.stat("Истекает", expires(access)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Устройства", "${access.deviceIds.size}/${access.maxDevices}"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Трафик", ui.bytes(access.upBytes + access.downBytes)), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (busy == access.password) addView(ProgressBar(context).apply { isIndeterminate = true })
        else addView(LinearLayout(context).apply {
            addView(ui.button(if (access.deactivated) "Включить" else "Выключить") {
                request(access.password, { admin.setActive(access.password, access.deactivated) }) { refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            addView(ui.button("Удалить", danger = true) { confirmDelete(access) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun confirmDelete(access: QwdttAdmin.Access) {
        MobileAlertDialogBuilder(this).setTitle("Удалить пароль?")
            .setMessage("Пароль «${access.title}» и все привязанные устройства будут удалены безвозвратно.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Удалить") { _, _ -> request(access.password, { admin.delete(access.password) }) { refresh() } }.show()
    }

    /** Create or edit: name, VK hashes one per field (sent comma-joined), days and devices. */
    private fun form(existing: QwdttAdmin.Access?) {
        val sheet = MobileSheetDialog(this)
        val body = ui.column(20).apply { setBackgroundColor(MobilePalette.surface) }
        body.addView(ui.text(if (existing == null) "Новый пароль доступа" else "Изменить доступ", 22f, bold = true))
        val label = ui.input("Имя (кому выдан)", existing?.label.orEmpty())
        body.addView(label)
        body.addView(ui.text("VK хеши", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(14), 0, 0) })
        val hashBox = ui.column()
        fun addHash(value: String) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val field = ui.input("VK хеш или ссылка на звонок", value)
            row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.iconButton(R.drawable.ic_navigation_close, "Убрать хеш") { if (hashBox.childCount > 1) hashBox.removeView(row) else field.setText("") },
                LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            hashBox.addView(row)
        }
        (existing?.hashes?.ifEmpty { null } ?: listOf("")).forEach(::addHash)
        body.addView(hashBox)
        body.addView(ui.text("+ Добавить хеш", 15f, ui.accent).apply {
            setPadding(0, ui.dp(10), 0, ui.dp(10)); setOnClickListener { addHash("") }
        })
        val days = ui.input(if (existing == null) "Дней" else "Продлить на дней (пусто — без изменений)", if (existing == null) "30" else "", number = true)
        val devices = ui.input("Устройств", (existing?.maxDevices ?: 1).toString(), number = true)
        body.addView(LinearLayout(this).apply {
            addView(days, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            addView(devices, LinearLayout.LayoutParams(0, -2, 1f))
        })
        body.addView(ui.gap(12))
        body.addView(ui.button(if (existing == null) "Создать" else "Сохранить", primary = true) {
            val hashes = (0 until hashBox.childCount).mapNotNull { index ->
                ((hashBox.getChildAt(index) as LinearLayout).getChildAt(0) as android.widget.EditText).text.toString()
                    .let(VkCallHash::strip).takeIf { it.isNotEmpty() }
            }.distinct()
            val maxDevices = devices.text.toString().toIntOrNull()?.takeIf { it in 1..100 }
                ?: return@button Toast.makeText(this, "Устройств: от 1 до 100", Toast.LENGTH_SHORT).show()
            val dayCount = days.text.toString().toIntOrNull()
            if (existing == null && (dayCount == null || dayCount !in 1..3650)) return@button Toast.makeText(this, "Дней: от 1 до 3650", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            if (existing == null) request(null, { admin.create(label.text.toString().trim(), hashes, dayCount!!, maxDevices) }) { refresh(); details(it) }
            else request(existing.password, { admin.update(existing.password, label.text.toString().trim(), hashes, maxDevices, dayCount) }) { refresh(); details(it) }
        })
        sheet.setContentView(ScrollView(this).apply { addView(body) }); sheet.show()
    }

    /** The access with its QR code, both share links, password and bound devices. */
    private fun details(access: QwdttAdmin.Access) {
        val sheet = MobileSheetDialog(this)
        val body = ui.column(20).apply { setBackgroundColor(MobilePalette.surface) }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(access.title, 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ic_mobile_edit, "Изменить") { sheet.dismiss(); form(access) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        })
        val (qwdtt, wdtt) = admin.links(access)
        body.addView(ui.linkRow("Ссылка qWDTT", qwdtt))
        body.addView(ui.qrRow(qwdtt))
        body.addView(ui.linkRow("Ссылка WDTT (легаси)", wdtt))
        body.addView(ui.linkRow("Пароль", access.password, lines = 1))
        body.addView(LinearLayout(this).apply {
            setPadding(0, ui.dp(14), 0, 0)
            addView(ui.stat("Истекает", expires(access)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Устройства", "${access.deviceIds.size}/${access.maxDevices}"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.stat("Трафик", ui.bytes(access.upBytes + access.downBytes)), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (access.deviceIds.isNotEmpty()) {
            body.addView(ui.text("Привязанные устройства", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(16), 0, ui.dp(4)) })
            access.deviceIds.forEach { device ->
                body.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.text(device, 13f, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.button("Отвязать") {
                        sheet.dismiss(); request(access.password, { admin.unbind(access.password, device) }) { refresh(); details(it) }
                    })
                })
            }
        }
        sheet.setContentView(ScrollView(this).apply { addView(body) }); sheet.show()
    }
}
