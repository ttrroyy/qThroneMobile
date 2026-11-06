package app.rawline.mobile.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployManager
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.OutboundProfileForms
import app.rawline.mobile.deploy.OutboundServerSnapshot
import app.rawline.mobile.deploy.ProxyKind
import app.rawline.mobile.deploy.SshCredentials
import app.rawline.mobile.deploy.WdttPlusExit
import app.rawline.mobile.deploy.externalProxyCredentialsIssue
import app.rawline.mobile.deploy.friendlyDeployError
import app.rawline.mobile.deploy.isValidPublicHost
import app.rawline.mobile.deploy.localProxyCredentialsIssue
import app.rawline.mobile.deploy.tunInterfaceNameIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Выходной IP и прокси» of WDTT Plus: where the server's clients leave to the internet. */
class WdttPlusExitActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var body: LinearLayout
    private lateinit var exit: WdttPlusExit
    private var snapshot: OutboundServerSnapshot? = null
    private var busy = false
    private var running = ""
    private var step = ""
    private var result = ""
    private var failed = false
    private var pendingConfig: EditText? = null

    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() } }.getOrNull()
        pendingConfig?.setText(text.orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Выходной IP и прокси"
        }
        toolbar.menu.add("Загрузить настройки").setIcon(R.drawable.ic_mobile_refresh).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { load(); true }
        body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
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
            exit = WdttPlusExit(applicationContext, loaded); toolbar.subtitle = loaded["host"]; load()
        }
    }

    private fun load() {
        if (!this::exit.isInitialized) return
        run("Читаю настройки выходного IP") { exit.summary(exit.snapshot()) }
    }

    /**
     * WDTT Plus's runOutboundAction: modes that switch the route first check the server has no conflicting route,
     * and every action ends by re-reading the server and warning about anything left half set up.
     */
    private fun run(label: String, preflight: Boolean = false, action: suspend () -> String) {
        if (busy || !this::exit.isInitialized) return
        busy = true; running = label; step = ""; result = ""; failed = false; render()
        val onProgress: (Float, String) -> Unit = { _, text -> runOnUiThread { step = text; render() } }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + DeployManager.progress.asContextElement(onProgress)) {
                runCatching {
                    if (preflight) exit.snapshot().also { snapshot = it }.routeConflictMessage?.let { return@runCatching "Установка остановлена. $it" }
                    val text = action().ifBlank { "$label: готово" }
                    val after = runCatching { exit.snapshot() }.getOrNull()
                    if (after != null) snapshot = after
                    val warning = if (after == null) "Действие завершено, но обновить состояние сервера не удалось." else exit.warning(after)
                    listOfNotNull(text, warning?.let { "Проверьте сервер: $it" }).joinToString("\n")
                }
            }
            busy = false
            outcome.onSuccess { result = it }.onFailure { result = friendlyDeployError(it, "выходной IP"); failed = true }
            render()
        }
    }

    private fun confirm(message: String, action: () -> Unit) {
        MobileAlertDialogBuilder(this).setTitle(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Продолжить") { _, _ -> action() }.show()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(ui.card().apply {
            val s = snapshot
            addView(ui.text("Текущий выход", 14f, MobilePalette.muted))
            addView(ui.text(s?.modeLabel ?: "не загружен", 20f, bold = true))
            s?.routeConflictMessage?.let { addView(ui.text(it, 14f, android.graphics.Color.rgb(232, 170, 80)).apply { setPadding(0, ui.dp(8), 0, 0) }) }
            addView(LinearLayout(context).apply {
                setPadding(0, ui.dp(12), 0, 0)
                addView(ui.button("Статус") { run("Проверяю текущий выход WDTT") { exit.status() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(ui.button("Диагностика") { run("Собираю диагностику выхода WDTT") { exit.diagnostics() } }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(ui.button("Вернуть прямой выход", primary = true) { run("Возвращаю прямой выход WDTT") { exit.direct() } }
                .apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) } })
        })
        if (busy || result.isNotEmpty()) body.addView(ui.card().apply {
            addView(ui.text(running, 16f, bold = true))
            if (busy) {
                addView(ProgressBar(context).apply { isIndeterminate = true })
                if (step.isNotEmpty()) addView(ui.text(step, 14f, MobilePalette.muted))
            } else addView(ui.text(result, 13f, if (failed) android.graphics.Color.rgb(232, 110, 100) else MobilePalette.text, mono = true).apply { setTextIsSelectable(true) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        body.addView(ui.section("Куда выпускать клиентов"))
        val forms = snapshot?.let(exit::forms) ?: OutboundProfileForms("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
        listOf<Triple<String, String, () -> Unit>>(
            Triple("Прокси на этом VPS", "SOCKS5 3proxy на этом сервере", { localProxy(forms) }),
            Triple("Внешний TCP-прокси", "SOCKS5 или HTTP через redsocks", { externalProxy(forms) }),
            Triple("Существующий TUN-интерфейс", "Выход через уже запущенный VPN на сервере", { tun(forms) }),
            Triple("Другой сервер", "WireGuard через второй VPS", { vps(forms) }),
            Triple("Бесплатный WARP", "Cloudflare WARP через wgcf", { warp() }),
            Triple("VPN/WireGuard-файл", "Свой конфиг WireGuard", { wireGuardFile(forms) }),
        ).forEach { (name, hint, open) ->
            body.addView(ui.tile(name, hint, R.drawable.ic_mobile_arrow_forward) { if (!busy) open() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
    }

    private fun sheet(title: String, fill: (LinearLayout, MobileSheetDialog) -> Unit) {
        val sheet = MobileSheetDialog(this)
        val content = ui.column(20).apply { setBackgroundColor(MobilePalette.surface) }
        content.addView(ui.text(title, 22f, bold = true))
        fill(content, sheet)
        sheet.setContentView(ScrollView(this).apply { addView(content) }); sheet.show()
    }

    private fun field(parent: LinearLayout, label: String, value: String, number: Boolean = false, secret: Boolean = false): EditText {
        parent.addView(ui.text(label, 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        return ui.input(label, value, number).also {
            if (secret) it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            parent.addView(it)
        }
    }

    private fun actions(parent: LinearLayout, vararg buttons: Pair<String, () -> Unit>) {
        buttons.toList().chunked(2).forEach { row ->
            parent.addView(LinearLayout(this).apply {
                setPadding(0, ui.dp(10), 0, 0)
                row.forEachIndexed { index, (label, click) ->
                    addView(ui.button(label, primary = label == buttons.first().first, danger = label.startsWith("Удалить")) { click() },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0 && row.size > 1) marginEnd = ui.dp(8) })
                }
            })
        }
    }

    private fun error(parent: LinearLayout): TextView = ui.text("", 14f, android.graphics.Color.rgb(232, 110, 100)).also { parent.addView(it) }

    private fun localProxy(forms: OutboundProfileForms) = sheet("Прокси на этом VPS") { body, sheet ->
        body.addView(ui.text("Устанавливает SOCKS5 на этом сервере. Клиенты WDTT выходят как обычно, а прокси можно использовать отдельно.", 14f, MobilePalette.muted))
        val port = field(body, "Порт SOCKS5", forms.localProxyPort.ifBlank { "1080" }, number = true)
        val login = field(body, "Логин", forms.localProxyLogin)
        val password = field(body, "Пароль", forms.localProxyPassword, secret = true)
        val problem = error(body)
        fun values(): Triple<Int, String, String>? {
            val p = port.text.toString().toIntOrNull()
            val issue = if (p == null || p !in 1..65533) "Порт SOCKS5 должен быть от 1 до 65533." else localProxyCredentialsIssue(login.text.toString(), password.text.toString())
            problem.text = issue.orEmpty()
            return if (issue == null) Triple(p!!, login.text.toString(), password.text.toString()) else null
        }
        actions(body,
            "Установить" to { values()?.let { (p, l, w) -> sheet.dismiss(); run("Устанавливаю прокси на этом сервере") {
                exit.installLocal(forms.copy(localProxyPort = p.toString(), localProxyLogin = l, localProxyPassword = w), p, l, w) } } },
            "Проверить" to { values()?.let { (p, l, w) -> sheet.dismiss(); run("Проверяю прокси на этом сервере") { exit.checkLocal(p, l, w) } } },
            "Остановить" to { sheet.dismiss(); run("Останавливаю прокси на этом сервере") { exit.stopLocal() } },
            "Удалить" to { confirm("Удалить прокси с этого сервера?") { sheet.dismiss(); run("Удаляю прокси с этого сервера") { exit.removeLocal() } } },
        )
    }

    private fun externalProxy(forms: OutboundProfileForms) = sheet("Внешний TCP-прокси") { body, sheet ->
        body.addView(ui.text("TCP-трафик клиентов WDTT пойдёт через указанный прокси, остальной трафик сервера не меняется.", 14f, MobilePalette.muted))
        var kind = ProxyKind.entries.firstOrNull { it.name == forms.externalProxyKindName } ?: ProxyKind.Socks5
        val kinds = LinearLayout(this).apply { setPadding(0, ui.dp(12), 0, 0) }
        fun drawKinds() {
            kinds.removeAllViews()
            ProxyKind.entries.forEach { option ->
                kinds.addView(ui.button(option.label, primary = option == kind) { kind = option; drawKinds() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (option == ProxyKind.Socks5) marginEnd = ui.dp(8) })
            }
        }
        drawKinds(); body.addView(kinds)
        val host = field(body, "Адрес прокси", forms.externalProxyHost)
        val port = field(body, "Порт", forms.externalProxyPort, number = true)
        val login = field(body, "Логин (необязательно)", forms.externalProxyLogin)
        val password = field(body, "Пароль", forms.externalProxyPassword, secret = true)
        val problem = error(body)
        fun ok(): Boolean {
            val p = port.text.toString().toIntOrNull()
            val issue = when {
                !host.text.toString().trim().isValidPublicHost() -> "Укажите домен или IPv4 прокси без схемы и порта."
                p == null || p !in 1..65535 -> "Порт прокси должен быть от 1 до 65535."
                else -> externalProxyCredentialsIssue(login.text.toString(), password.text.toString())
            }
            problem.text = issue.orEmpty(); return issue == null
        }
        val h = { host.text.toString().trim() }; val p = { port.text.toString().toInt() }
        actions(body,
            "Включить" to { if (ok()) { sheet.dismiss(); val k = kind; val (l, w) = login.text.toString() to password.text.toString()
                run("Проверяю и включаю внешний TCP-прокси", preflight = true) {
                    exit.enableExternal(forms.copy(externalProxyKindName = k.name, externalProxyHost = h(), externalProxyPort = p().toString(),
                        externalProxyLogin = l, externalProxyPassword = w), k, h(), p(), l, w)
                } } },
            "Проверить" to { if (ok()) { sheet.dismiss(); val k = kind; val (l, w) = login.text.toString() to password.text.toString()
                run("Проверяю доступность внешнего TCP-прокси") { exit.checkExternal(k, h(), p(), l, w) } } },
            "Удалить" to { confirm("Удалить настройки внешнего TCP-прокси?") { sheet.dismiss(); run("Удаляю настройки внешнего TCP-прокси") { exit.deleteExternal() } } },
        )
    }

    private fun tun(forms: OutboundProfileForms) = sheet("Существующий TUN-интерфейс") { body, sheet ->
        body.addView(ui.text("Клиенты WDTT выйдут через интерфейс VPN, который уже работает на сервере. Если он упадёт, трафик клиентов блокируется, а не уходит напрямую.", 14f, MobilePalette.muted))
        val name = field(body, "Имя интерфейса", forms.tunInterface)
        val found = ui.column(); body.addView(found)
        val problem = error(body)
        body.addView(ui.text("Найти интерфейсы на сервере", 15f, ui.accent).apply {
            setPadding(0, ui.dp(10), 0, 0)
            setOnClickListener {
                found.removeAllViews(); found.addView(ProgressBar(context).apply { isIndeterminate = true })
                lifecycleScope.launch {
                    val list = withContext(Dispatchers.IO) { runCatching { exit.tunCandidates() } }
                    found.removeAllViews()
                    list.onFailure { problem.text = friendlyDeployError(it, "поиск интерфейсов") }.onSuccess { items ->
                        if (items.isEmpty()) found.addView(ui.text("Подходящих интерфейсов не найдено.", 14f, MobilePalette.muted))
                        items.forEach { item ->
                            found.addView(ui.text("${item.name} · ${if (item.isUp) "работает" else "остановлен"}", 15f).apply {
                                setPadding(0, ui.dp(8), 0, ui.dp(8)); setOnClickListener { name.setText(item.name) }
                            })
                        }
                    }
                }
            }
        })
        actions(body,
            "Включить" to {
                val value = name.text.toString().trim()
                val issue = tunInterfaceNameIssue(value); problem.text = issue.orEmpty()
                if (issue == null) { sheet.dismiss(); run("Включаю выход через TUN-интерфейс", preflight = true) { exit.enableTun(forms.copy(tunInterface = value), value) } }
            },
            "Проверить" to { sheet.dismiss(); run("Проверяю выход через TUN-интерфейс") { exit.checkTun() } },
            "Удалить" to { confirm("Удалить настройку TUN-выхода?") { sheet.dismiss(); run("Удаляю настройку TUN-выхода") { exit.deleteTun() } } },
        )
    }

    private fun vps(forms: OutboundProfileForms) = sheet("Другой сервер") { body, sheet ->
        body.addView(ui.text("На втором VPS ставится WireGuard, и клиенты WDTT выходят в интернет с его IP. Пароль второго сервера нужен только на время настройки и нигде не сохраняется.", 14f, MobilePalette.muted))
        val host = field(body, "Адрес второго VPS", forms.wireGuardExitHost)
        val sshPort = field(body, "SSH-порт", forms.wireGuardExitSshPort.ifBlank { "22" }, number = true)
        val user = field(body, "SSH-логин", forms.wireGuardExitUser.ifBlank { "root" })
        val password = field(body, "SSH-пароль", "", secret = true)
        val wgPort = field(body, "Порт WireGuard", forms.wireGuardExitPort.ifBlank { "51820" }, number = true)
        val problem = error(body)
        fun target(needWg: Boolean): List<Any>? {
            val sp = sshPort.text.toString().toIntOrNull(); val wp = wgPort.text.toString().toIntOrNull()
            val issue = when {
                !host.text.toString().trim().isValidPublicHost() -> "Укажите домен или IPv4 второго VPS без схемы и порта."
                sp == null || sp !in 1..65535 -> "SSH-порт должен быть от 1 до 65535."
                user.text.isBlank() -> "Укажите SSH-логин второго VPS."
                password.text.isEmpty() -> "Введите SSH-пароль второго VPS."
                needWg && (wp == null || wp !in 1..65535) -> "Порт WireGuard должен быть от 1 до 65535."
                else -> null
            }
            problem.text = issue.orEmpty()
            return if (issue == null) listOf(host.text.toString().trim(), sp!!, user.text.toString().trim(), SshCredentials(password.text.toString()), wp ?: 0) else null
        }
        actions(body,
            "Настроить" to { target(true)?.let { (h, sp, u, c, wp) -> sheet.dismiss()
                run("Настраиваю WireGuard-выход через другой сервер", preflight = true) {
                    exit.installVps(forms.copy(wireGuardExitHost = h as String, wireGuardExitSshPort = sp.toString(), wireGuardExitUser = u as String,
                        wireGuardExitPort = wp.toString()), h, sp as Int, u, c as SshCredentials, wp as Int)
                } } },
            "Проверить" to { sheet.dismiss(); run("Проверяю выход через другой сервер") { exit.checkWireGuard("wireguard_vps") } },
            "Удалить" to { target(false)?.let { (h, sp, u, c) -> confirm("Удалить выход через другой сервер?") { sheet.dismiss()
                run("Удаляю выход через другой сервер") { exit.deleteVps(h as String, sp as Int, u as String, c as SshCredentials) } } } },
        )
    }

    private fun warp() = sheet("Бесплатный WARP") { body, sheet ->
        body.addView(ui.text("Клиенты WDTT выходят через Cloudflare WARP. Сервер сам следит за соединением и перезапускает его при сбое.", 14f, MobilePalette.muted))
        val mtu = field(body, "MTU", snapshot?.warpMtu?.ifBlank { null } ?: "1280", number = true)
        val problem = error(body)
        actions(body,
            "Установить / восстановить" to {
                val value = mtu.text.toString().toIntOrNull()
                if (value == null || value !in 1280..1500) problem.text = "MTU WARP должен быть от 1280 до 1500."
                else { sheet.dismiss(); run("Устанавливаю и проверяю бесплатный WARP", preflight = true) { exit.installWarp(value) } }
            },
            "Проверить" to { sheet.dismiss(); run("Проверяю бесплатный WARP") { exit.checkWireGuard("warp_free") } },
            "Перезапустить" to { sheet.dismiss(); run("Перезапускаю и проверяю бесплатный WARP") { exit.restartWarp() } },
            "Обновить wgcf" to { sheet.dismiss(); run("Проверяю обновление wgcf") { exit.updateWgcf() } },
            "Сбросить регистрацию" to { confirm("Сбросить регистрацию WARP?") { sheet.dismiss(); run("Сбрасываю регистрацию WARP") { exit.resetWarp() } } },
            "Удалить" to { confirm("Удалить бесплатный WARP?") { sheet.dismiss(); run("Удаляю бесплатный WARP и возвращаю прямой выход") { exit.deleteWarp() } } },
        )
    }

    private fun wireGuardFile(forms: OutboundProfileForms) = sheet("VPN/WireGuard-файл") { body, sheet ->
        body.addView(ui.text("Конфиг WireGuard применяется только к клиентам WDTT. Опасные команды из файла убираются перед загрузкой.", 14f, MobilePalette.muted))
        val config = field(body, "Конфиг", forms.importedWireGuardConfig).apply {
            isSingleLine = false; minLines = 6; gravity = Gravity.TOP; typeface = android.graphics.Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val problem = error(body)
        body.addView(ui.text("Выбрать файл", 15f, ui.accent).apply {
            setPadding(0, ui.dp(10), 0, 0); setOnClickListener { pendingConfig = config; pickConfig.launch(arrayOf("*/*")) }
        })
        actions(body,
            "Включить" to {
                val text = config.text.toString()
                exit.validateWireGuard(text).onFailure { problem.text = "Ошибка VPN/WireGuard-файла: ${it.message}" }.onSuccess {
                    sheet.dismiss(); run("Включаю VPN/WireGuard-выход из файла", preflight = true) { exit.enableWireGuardFile(forms.copy(importedWireGuardConfig = text), text) }
                }
            },
            "Проверить файл" to {
                problem.text = exit.validateWireGuard(config.text.toString()).fold({ "Файл подходит." }, { "Ошибка VPN/WireGuard-файла: ${it.message}" })
            },
            "Проверить сервер" to { sheet.dismiss(); run("Проверяю установленный VPN/WireGuard-выход") { exit.checkWireGuard("imported_wg") } },
            "Удалить" to { confirm("Удалить VPN/WireGuard-файл с сервера?") { sheet.dismiss(); run("Удаляю VPN/WireGuard-файл") { exit.deleteWireGuardFile() } } },
        )
    }
}
