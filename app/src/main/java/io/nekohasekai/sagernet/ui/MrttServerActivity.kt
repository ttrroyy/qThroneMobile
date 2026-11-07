package app.rawline.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.R
import app.rawline.mobile.bg.carrier.VkRelayAccount
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.MrttAdmin
import app.rawline.mobile.deploy.MrttAdmin.Companion.encode
import app.rawline.mobile.outbound.import.ProfileImport
import app.rawline.mobile.outbound.types.Mrtt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A deployed MRTT server, with the sections of its own app: configs, groups, routes, proxy, settings, statistics and logs. */
class MrttServerActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var server: DeployServer
    private lateinit var admin: MrttAdmin
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: android.widget.TextView
    private lateinit var tabs: LinearLayout
    private var section = 0
    private var live: Job? = null
    private val sections = listOf("Конфиги","Группы","Маршруты","Прокси","Настройки","Статистика","Логи")
    private var clients = JSONArray()
    private var groups = JSONArray()
    private var rules = JSONArray()
    private var settings = JSONObject()
    private var search = ""
    private var groupFilter: String? = null
    private val selected = linkedSetOf<String>()
    private var listHolder: LinearLayout? = null

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        request({
            val json = admin.get("/api/export")
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }) { toast("Конфигурация сохранена") }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        request({ contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty() }) { json ->
            MobileAlertDialogBuilder(this).setTitle("Импорт конфигурации")
                .setMessage("Добавить конфиги, группы и правила из файла и применить его настройки? Существующие подписки сохранятся.")
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton("Импортировать") { _,_ -> action("Импорт выполнен") { admin.request("POST","/api/import",json) } }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Сервер MRTT"
            setTitleTextColor(MobilePalette.text); setSubtitleTextColor(MobilePalette.muted)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        tabs = LinearLayout(this).apply { setPadding(ui.dp(12),0,ui.dp(12),ui.dp(8)) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = ui.text("",14f,MobilePalette.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(4)); visibility = View.GONE }
        content = ui.column(16)
        root.addView(toolbar)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        root.addView(progress,LinearLayout.LayoutParams(-2,-2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(status)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id==id } }.getOrNull() }
            if(loaded==null) { finish(); return@launch }
            server = loaded; admin = MrttAdmin(loaded); toolbar.subtitle = loaded["name"].ifBlank { loaded["host"] }
            showTabs(); open(0)
        }
    }
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun <T> request(call: () -> T,done: (T) -> Unit) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call) }
            progress.visibility = View.GONE
            result.onSuccess { status.visibility = View.GONE; done(it) }.onFailure {
                status.text = it.message ?: "Сервер недоступен"; status.visibility = View.VISIBLE
            }
        }
    }
    private fun action(message: String?,call: () -> Unit) = request(call) { if(message!=null) toast(message); open(section) }
    private fun showTabs() {
        tabs.removeAllViews()
        sections.forEachIndexed { index,title ->
            tabs.addView(ui.button(title,primary = index==section) { open(index) },LinearLayout.LayoutParams(-2,-2).apply { marginEnd = ui.dp(6) })
        }
    }
    private fun open(index: Int) {
        section = index; live?.cancel(); showTabs()
        when(index) {
            0 -> loadConfigs()
            1 -> loadGroups()
            2 -> loadRules()
            3 -> loadProxy()
            4 -> loadSettings()
            5 -> { loadStats(); live = lifecycleScope.launch { while(true) { delay(10_000); loadStats() } } }
            else -> { loadLogs(); live = lifecycleScope.launch { while(true) { delay(5_000); loadLogs() } } }
        }
    }
    private fun add(view: View) = content.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(8) })
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.strings(key: String) = optJSONArray(key)?.let { list -> (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
    private fun bytes(value: Long): String {
        if(value<1024) return "$value Б"
        val units = listOf("КБ","МБ","ГБ","ТБ"); var size = value/1024.0; var unit = 0
        while(size>=1024 && unit<units.lastIndex) { size /= 1024; unit++ }
        return String.format(Locale.US,"%.1f %s",size,units[unit])
    }
    private fun date(seconds: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("ru")).format(Date(seconds*1000))
    private fun isOwner(client: JSONObject) = client.optString("ip")=="10.66.67.2" && client.optLong("expires")==0L && client.optString("group_id").isEmpty() && client.optString("name")=="Владелец"
    private fun input(hint: String,value: String = "",number: Boolean = false,secret: Boolean = false,multiline: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(MobilePalette.text); setHintTextColor(MobilePalette.muted); MobilePalette.tintCursor(this)
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
    }
    private class Choice(var value: String)
    /** A labelled choice row: the value lives in [state], [changed] redraws what depends on it. */
    private fun picker(parent: LinearLayout,label: String,state: Choice,options: List<Pair<String,String>>,enabled: Boolean = true,changed: () -> Unit = {}) {
        fun caption() = "$label: "+(options.firstOrNull { it.first==state.value }?.second ?: options.firstOrNull()?.second.orEmpty())
        lateinit var row: android.widget.TextView
        row = ui.button(caption()) {
            if(!enabled) return@button
            MobileAlertDialogBuilder(this).setTitle(label).setItems(options.map { it.second }.toTypedArray()) { _,index ->
                state.value = options[index].first; row.text = caption(); changed()
            }.show()
        }
        row.alpha = if(enabled) 1f else .5f
        parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) })
    }
    private fun hint(parent: LinearLayout,text: String) { parent.addView(ui.text(text,13f,MobilePalette.muted).apply { setPadding(0,ui.dp(6),0,0) }) }
    private fun form(title: String,body: LinearLayout,confirm: String = "Сохранить",onConfirm: () -> Boolean): androidx.appcompat.app.AlertDialog {
        val dialog = MobileAlertDialogBuilder(this).setTitle(title).setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(confirm,null).create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { if(onConfirm()) dialog.dismiss() }
        return dialog
    }
    private fun confirm(title: String,text: String,button: String,run: () -> Unit) {
        MobileAlertDialogBuilder(this).setTitle(title).setMessage(text).setNegativeButton(android.R.string.cancel,null).setPositiveButton(button) { _,_ -> run() }.show()
    }
    private fun hashFields(parent: LinearLayout,joined: String): List<EditText> {
        val values = joined.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        return (0 until 6).map { index -> input("VK хеш ${index+1}",values.getOrNull(index).orEmpty()).also { parent.addView(it) } }
    }
    private fun hashes(fields: List<EditText>) = fields.map { app.rawline.mobile.outbound.link.VkCallHash.strip(it.text.toString()) }.filter { it.isNotEmpty() }.distinct().take(6).joinToString(",")
    private fun share(title: String,link: String) {
        MobileAlertDialogBuilder(this).setTitle(title).setView(ScrollView(this).apply { addView(ui.column(16).apply {
            addView(ui.qrRow(link)); addView(ui.linkRow("Ссылка",link,lines = 4))
        }) }).setPositiveButton(android.R.string.ok,null)
            .setNeutralButton("Поделиться") { _,_ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link),title)) }.show()
    }

    // Configs and subscriptions.
    private fun loadConfigs() = request({
        val loadedSettings = runCatching { admin.settings() }.getOrDefault(JSONObject())
        Triple(admin.clients(),admin.groups(),loadedSettings)
    }) { (loadedClients,loadedGroups,loadedSettings) ->
        clients = loadedClients; groups = loadedGroups; settings = loadedSettings
        selected.retainAll(clients.objects().map { it.optString("password") }.toSet())
        showConfigs()
    }
    private fun showConfigs() {
        if(section!=0) return
        content.removeAllViews()
        add(ui.button("Создать конфиг",primary = true) { editClient(null) })
        add(ui.button("Создать подписку") { buildSubscription() })
        add(input("Поиск по имени",search).apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) {}
                override fun onTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if(search!=s.toString()) { search = s.toString(); listHolder?.let { showClientRows(it) } }
                }
            })
        })
        val filters = ui.column().also { add(it) }
        val choice = Choice(groupFilter ?: "\u0000")
        picker(filters,"Группа",choice,listOf("\u0000" to "Все","" to "Без группы")+groups.objects().map { it.optString("id") to it.optString("name") }) {
            groupFilter = choice.value.takeIf { it!="\u0000" }; listHolder?.let { showClientRows(it) }
        }
        val holder = ui.column(); listHolder = holder; add(holder); showClientRows(holder)
    }
    private fun showClientRows(holder: LinearLayout) {
        holder.removeAllViews()
        if(selected.isNotEmpty()) holder.addView(ui.card().apply {
            addView(ui.text("Выбрано: ${selected.size}",15f,bold = true))
            val chosen = selected.toList()
            fun batch(path: String,body: JSONObject,message: String) = action(message) { admin.post("/api/clients/batch-$path",body.put("passwords",JSONArray(chosen))) }
            listOf<Pair<String,() -> Unit>>(
                "Вкл" to { batch("toggle",JSONObject().put("active",true),"Включены") },
                "Выкл" to { batch("toggle",JSONObject().put("active",false),"Выключены") },
                "+30 дней" to { batch("extend",JSONObject().put("days",30),"Продлены на 30 дней") },
                "Сброс трафика" to { batch("reset-traffic",JSONObject(),"Трафик сброшен") },
                "В группу" to {
                    val options = listOf("" to "Без группы")+groups.objects().map { it.optString("id") to it.optString("name") }
                    MobileAlertDialogBuilder(this@MrttServerActivity).setTitle("Перенести в группу").setItems(options.map { it.second }.toTypedArray()) { _,index ->
                        batch("group",JSONObject().put("group_id",options[index].first),"Перенесено")
                    }.show()
                },
                "Удалить" to { confirm("Удалить конфиги","Удалить выбранные конфиги (${selected.size})? Ссылки перестанут работать.","Удалить") {
                    selected.clear(); batch("delete",JSONObject(),"Удалено")
                } },
                "Снять выбор" to { selected.clear(); showClientRows(holder) },
            ).forEach { (title,run) -> addView(ui.button(title) { run() },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) }) }
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(8) })
        val visible = clients.objects().filter { groupFilter==null || it.optString("group_id")==groupFilter }
            .filter { search.isBlank() || it.optString("name").contains(search,ignoreCase = true) }
            .sortedWith(compareBy({ !isOwner(it) },{ it.optString("name").lowercase() }))
        if(visible.isEmpty()) holder.addView(ui.text("Конфигов нет",14f,MobilePalette.muted))
        visible.forEach { client ->
            val owner = isOwner(client); val expires = client.optLong("expires"); val active = client.optBoolean("active")
            val state = when {
                owner -> "владелец"
                !active && expires!=0L && expires*1000<System.currentTimeMillis() -> "истекла"
                !active -> "выключена"
                else -> "активна"
            }
            val group = groups.objects().firstOrNull { it.optString("id")==client.optString("group_id") }?.optString("name")
            val devices = client.optJSONArray("devices") ?: JSONArray()
            val online = devices.objects().count { it.optBoolean("online") }
            val limit = client.optInt("max_devices",1).let { if(it==0) "∞" else it.toString() }
            val line = listOfNotNull(state,group,if(expires==0L) "бессрочно" else "до ${date(expires)}").joinToString(" · ")+
                "\nУстройства: ${devices.length()}/$limit (в сети $online) · ↑ ${bytes(client.optLong("up"))} ↓ ${bytes(client.optLong("down"))}"
            val password = client.optString("password")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if(!owner) row.addView(CheckBox(this).apply {
                isChecked = password in selected
                setOnClickListener { if(isChecked) selected.add(password) else selected.remove(password); showClientRows(holder) }
            })
            row.addView(ui.tile(client.optString("name"),line,R.drawable.ic_baseline_vpn_key_24) { editClient(client) },LinearLayout.LayoutParams(0,-2,1f))
            holder.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(8) })
        }
    }
    private fun editClient(client: JSONObject?) {
        val owner = client?.let(::isOwner)==true
        val body = ui.column(16)
        val name = input("Имя",client?.optString("name").orEmpty())
        val group = Choice(client?.optString("group_id").orEmpty())
        val remaining = client?.optLong("expires")?.let { if(it==0L) 0 else ((it*1000-System.currentTimeMillis()).coerceAtLeast(0)/86_400_000L+1).toInt() } ?: 30
        val days = input("Срок, дней (0 — бессрочно)",remaining.toString(),number = true)
        if(!owner) {
            body.addView(name)
            picker(body,"Группа",group,listOf("" to "Без группы")+groups.objects().map { it.optString("id") to it.optString("name") })
            body.addView(days)
        }
        val workers = input("Потоки (0 — как в приложении, 3–100)",(client?.optInt("workers") ?: 0).toString(),number = true)
        val devices = input("Устройств (0 — без лимита)",(client?.optInt("max_devices",1) ?: 0).toString(),number = true)
        body.addView(workers); body.addView(devices)
        val transport = Choice(client?.optString("transport").orEmpty())
        val roomOff = Choice(if(client?.optBoolean("link_room_off")==true) "client" else "server")
        val roomBox = ui.column()
        fun showRoom() {
            roomBox.removeAllViews()
            if(transport.value!="jitsi") return
            picker(roomBox,"Комната Jitsi",roomOff,listOf("server" to "Комната сервера","client" to "Без комнаты")) { showRoom() }
            hint(roomBox,if(roomOff.value=="client") "В ссылке нет комнаты: клиент вставляет её сам. Сервер ждёт его только в своей комнате." else "В ссылку идёт комната, в которой сидит сервер.")
        }
        picker(body,"Транспорт в ссылке",transport,listOf("" to "Не задан","vk" to "VK","jitsi" to "Jitsi")) { showRoom() }
        body.addView(roomBox); showRoom()
        val exits = settings.optJSONArray("exits") ?: JSONArray()
        val exit = Choice(client?.optString("exit").orEmpty())
        if(exits.length()>0) {
            val configDecides = settings.optString("exit_priority")=="config"
            picker(body,"Выходной IP",exit,listOf("" to "Как на сервере","direct" to "Этот сервер")+exits.objects().map { it.optString("id") to it.optString("name") },configDecides)
            if(!configDecides) hint(body,"Сейчас действует настройка сервера. Выбор появится, когда в настройках сервера приоритет выходного IP будет «Настройка конфига».")
        }
        val source = Choice(when { client?.optBoolean("vk_token")==true -> "server"; !client?.optString("vk_hashes").isNullOrBlank() -> "manual"; else -> "client" })
        val hashBox = ui.column()
        var hashInputs = emptyList<EditText>()
        val initialHashes = client?.optString("vk_hashes").orEmpty()
        fun showHashes() {
            hashBox.removeAllViews(); hashInputs = emptyList()
            when(source.value) {
                "manual" -> hashInputs = hashFields(hashBox,initialHashes)
                "server" -> hint(hashBox,"В ссылку идут хеши с VK-аккаунта, привязанного к серверу.")
                else -> hint(hashBox,"В ссылке нет хешей: клиент использует свой аккаунт VK или свои хеши.")
            }
        }
        picker(body,"Хеши VK",source,listOf("manual" to "Свои хеши","server" to "Аккаунт сервера","client" to "Без хешей")) { showHashes() }
        body.addView(hashBox); showHashes()
        var dialog: androidx.appcompat.app.AlertDialog? = null
        if(client!=null) {
            val password = encode(client.optString("password"))
            body.addView(ui.text("Устройства",15f,bold = true).apply { setPadding(0,ui.dp(12),0,0) })
            val list = client.optJSONArray("devices") ?: JSONArray()
            if(list.length()==0) hint(body,"Нет привязанных устройств")
            list.objects().forEach { device ->
                val seen = device.optLong("last_seen")
                hint(body,(if(device.optBoolean("online")) "● " else "○ ")+device.optString("name").ifBlank { device.optString("id").take(8) }+" · ${device.optString("ip")} · "+(if(seen>0) date(seen) else "—"))
                body.addView(ui.button("Отвязать") { dialog?.dismiss(); action("Устройство отвязано") { admin.post("/api/clients/$password/unbind?device="+encode(device.optString("id"))) } })
            }
            if(list.length()>0) body.addView(ui.button("Отвязать все устройства") { dialog?.dismiss(); action("Устройство отвязано") { admin.post("/api/clients/$password/unbind?device=") } })
            hint(body,"Трафик: ↑ ${bytes(client.optLong("up"))} ↓ ${bytes(client.optLong("down"))} · сессий ${client.optInt("active_sessions")}")
            val buttons = mutableListOf<Pair<String,() -> Unit>>(
                "Ссылка и QR" to { share(client.optString("name"),client.optString("link")) },
                "В мои профили" to { addToProfiles(client.optString("link"),client.optString("name")) },
            )
            if(!owner) {
                buttons += (if(client.optBoolean("active")) "Выключить" else "Включить") to { dialog?.dismiss(); action("Статус изменён") { admin.post("/api/clients/$password/toggle") } }
                buttons += "+30 дней" to { dialog?.dismiss(); action("Продлено на 30 дней") { admin.post("/api/clients/$password/extend",JSONObject().put("days",30)) } }
            }
            buttons += "Сброс трафика" to { dialog?.dismiss(); action("Трафик сброшен") { admin.post("/api/clients/$password/reset-traffic") } }
            if(!owner) buttons += "Удалить" to { confirm("Удалить конфиг","Удалить «${client.optString("name")}»? Ссылка перестанет работать на всех устройствах.","Удалить") {
                dialog?.dismiss(); action("Конфиг удалён") { admin.delete("/api/clients/$password") }
            } }
            buttons.forEach { (title,run) -> body.addView(ui.button(title) { run() },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) }) }
        }
        dialog = form(if(client==null) "Новый конфиг" else client.optString("name"),body) {
            val workerCount = workers.text.toString().toIntOrNull() ?: -1
            val dayCount = days.text.toString().toIntOrNull() ?: -1
            val deviceCount = devices.text.toString().toIntOrNull() ?: -1
            if((!owner && name.text.isBlank()) || dayCount !in 0..3650 || (workerCount!=0 && workerCount !in 3..100) || deviceCount !in 0..1000) {
                toast("Проверьте имя, срок, потоки и устройства"); return@form false
            }
            val json = JSONObject().put("name",name.text.toString().trim().take(128)).put("group_id",group.value).put("days",dayCount)
                .put("hash",if(source.value=="manual") hashes(hashInputs) else "").put("vk_token",source.value=="server").put("workers",workerCount)
                .put("max_devices",deviceCount).put("transport",transport.value).put("link_room_off",roomOff.value=="client").put("exit",exit.value)
            if(client==null) action("Конфиг создан") { admin.post("/api/clients",json) }
            else {
                json.put("peer_port",client.optInt("peer_port")).put("local_port",client.optInt("local_port"))
                action("Сохранено") { admin.post("/api/clients/"+encode(client.optString("password")),json) }
            }
            true
        }
    }
    private fun addToProfiles(link: String,name: String) = lifecycleScope.launch {
        val added = withContext(Dispatchers.IO) { runCatching {
            ProfileImport.parse(link).outbounds.onEach { it.name = name; ProfileManager.createProfile(app.rawline.mobile.database.DataStore.currentGroupId(),it) }.size
        } }
        toast(if(added.getOrDefault(0)>0) "Добавлено в профили" else "Не удалось добавить профиль")
    }
    private fun buildSubscription() {
        val body = ui.column(16)
        val name = input("Название подписки")
        body.addView(name)
        hint(body,"Подписка: одна ссылка и QR-код, внутри которых выбранные конфиги. Тот, кто её добавит, получит их все в разделе профилей.")
        val checks = clients.objects().map { client -> CheckBox(this).apply { text = client.optString("name"); setTextColor(MobilePalette.text); body.addView(this) } to client }
        form("Новая подписка",body,"Создать") {
            val chosen = checks.filter { it.first.isChecked }.map { it.second.optString("name") to it.second.optString("link") }
            if(name.text.isBlank() || chosen.isEmpty()) { toast("Укажите название и выберите конфиги"); return@form false }
            share(name.text.toString().trim(),Mrtt.encodeSubscription(name.text.toString().trim().take(64),chosen)); true
        }
    }

    // Groups.
    private fun loadGroups() = request({ admin.groups() to admin.rules() }) { (loaded,loadedRules) ->
        groups = loaded; rules = loadedRules
        if(section!=1) return@request
        content.removeAllViews()
        add(ui.button("Создать группу",primary = true) {
            val name = input("Название группы")
            form("Новая группа",ui.column(16).apply { addView(name) },"Создать") {
                if(name.text.isBlank()) return@form false
                action("Группа создана") { admin.post("/api/groups",JSONObject().put("name",name.text.toString().trim().take(64))) }; true
            }
        })
        if(groups.length()==0) add(ui.text("Групп пока нет",14f,MobilePalette.muted))
        groups.objects().forEach { group ->
            val route = rules.objects().firstOrNull { it.optString("id")==group.optString("routing_rule_id") }?.optString("name")
            add(ui.tile(group.optString("name"),"Подписок: ${group.optInt("member_count")} · в сети ${group.optInt("active_sessions")} · ↑ ${bytes(group.optLong("up"))} ↓ ${bytes(group.optLong("down"))}"+(route?.let { " · маршрут «$it»" } ?: ""),R.drawable.ic_mobile_subscriptions) { editGroup(group) })
        }
    }
    private fun editGroup(group: JSONObject) {
        val body = ui.column(16)
        val name = input("Название группы",group.optString("name"))
        body.addView(name)
        val rule = Choice(group.optString("routing_rule_id"))
        picker(body,"Маршрутизация",rule,listOf("" to "Нет")+rules.objects().map { it.optString("id") to it.optString("name") })
        var dialog: androidx.appcompat.app.AlertDialog? = null
        body.addView(ui.button("Удалить группу",danger = true) {
            confirm("Удалить группу","Удалить «${group.optString("name")}» вместе со всеми её конфигами (${group.optInt("member_count")})? Их ссылки перестанут работать.","Удалить") {
                dialog?.dismiss(); action("Группа удалена") { admin.delete("/api/groups/"+encode(group.optString("id"))) }
            }
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(8) })
        dialog = form(group.optString("name"),body) {
            if(name.text.isBlank()) return@form false
            action("Сохранено") { admin.put("/api/groups/"+encode(group.optString("id")),JSONObject().put("name",name.text.toString().trim().take(64)).put("routing_rule_id",rule.value)) }; true
        }
    }

    // Routes.
    private fun loadRules() = request({ Triple(admin.rules(),admin.groups(),admin.clients()) }) { (loaded,loadedGroups,loadedClients) ->
        rules = loaded; groups = loadedGroups; clients = loadedClients
        if(section!=2) return@request
        content.removeAllViews()
        add(ui.text("Правила раздельной маршрутизации, которые сервер отправляет клиентам: какие приложения, домены и IP идут мимо туннеля.",13f,MobilePalette.muted))
        add(ui.button("Создать правило",primary = true) { editRule(null) })
        if(rules.length()==0) add(ui.text("Правил пока нет",14f,MobilePalette.muted))
        rules.objects().forEach { rule ->
            add(ui.tile(rule.optString("name"),"Приложений: ${rule.strings("packages").size} · доменов: ${rule.strings("direct_domains").size}${if(rule.optBoolean("template_domains")) " + шаблон" else ""} · IP: ${rule.strings("ips").size} · групп: ${rule.strings("assigned_groups").size} · подписок: ${rule.strings("assigned_clients").size}",R.drawable.ic_mobile_route) { editRule(rule) })
        }
    }
    private fun lines(text: String) = text.split(Regex("[\\n,;]+")).map(String::trim).filter(String::isNotEmpty).distinct()
    private fun editRule(rule: JSONObject?) {
        val body = ui.column(16)
        val name = input("Название",rule?.optString("name").orEmpty()); body.addView(name)
        val modes = listOf("false" to "Чёрный список","true" to "Белый список")
        val apps = Choice((rule?.optBoolean("is_whitelist")==true).toString())
        picker(body,"Режим исключения приложений",apps,modes)
        val packages = input("Пакеты, по одному в строке",rule?.strings("packages").orEmpty().joinToString("\n"),multiline = true); body.addView(packages)
        val domainsMode = Choice((rule?.optBoolean("domains_is_whitelist")==true).toString())
        picker(body,"Режим исключения доменов",domainsMode,modes)
        val template = CheckBox(this).apply { text = "Готовый список RU доменов"; setTextColor(MobilePalette.text); isChecked = rule?.optBoolean("template_domains")==true }
        body.addView(template)
        val domains = input("Домены, по одному в строке",rule?.strings("direct_domains").orEmpty().joinToString("\n"),multiline = true); body.addView(domains)
        val ipMode = Choice((rule?.optBoolean("ip_is_whitelist")==true).toString())
        picker(body,"Режим исключения адресов",ipMode,modes)
        val ips = input("Адреса: IP, CIDR или диапазон, по одному в строке",rule?.strings("ips").orEmpty().joinToString("\n"),multiline = true); body.addView(ips)
        fun checks(title: String,items: JSONArray,key: String,assigned: List<String>): List<Pair<CheckBox,String>> {
            if(items.length()==0) return emptyList()
            body.addView(ui.text(title,15f,bold = true).apply { setPadding(0,ui.dp(10),0,0) })
            return items.objects().map { item -> CheckBox(this).apply { text = item.optString("name"); setTextColor(MobilePalette.text); isChecked = item.optString(key) in assigned; body.addView(this) } to item.optString(key) }
        }
        val assignedGroups = checks("Применить к группам",groups,"id",rule?.strings("assigned_groups").orEmpty())
        val assignedClients = checks("Применить к конфигам",clients,"password",rule?.strings("assigned_clients").orEmpty())
        var dialog: androidx.appcompat.app.AlertDialog? = null
        if(rule!=null) body.addView(ui.button("Удалить правило",danger = true) { dialog?.dismiss(); action("Правило удалено") { admin.delete("/api/routing-rules/"+encode(rule.optString("id"))) } },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(8) })
        dialog = form(rule?.optString("name") ?: "Новое правило",body) {
            val json = JSONObject().put("name",name.text.toString().trim().ifBlank { "Правило" }.take(64)).put("is_whitelist",apps.value=="true")
                .put("packages",JSONArray(lines(packages.text.toString()))).put("template_domains",template.isChecked).put("domains_is_whitelist",domainsMode.value=="true")
                .put("direct_domains",JSONArray(lines(domains.text.toString()))).put("ip_is_whitelist",ipMode.value=="true").put("ips",JSONArray(lines(ips.text.toString())))
                .put("assigned_groups",JSONArray(assignedGroups.filter { it.first.isChecked }.map { it.second })).put("assigned_clients",JSONArray(assignedClients.filter { it.first.isChecked }.map { it.second }))
            action("Правило сохранено") { if(rule==null) admin.post("/api/routing-rules",json) else admin.put("/api/routing-rules/"+encode(rule.optString("id")),json) }; true
        }
    }

    // Proxy.
    private fun loadProxy() = request({ admin.proxy() }) { proxy ->
        if(section!=3) return@request
        content.removeAllViews()
        add(ui.text("Локальный SOCKS5 на сервере: весь трафик клиентов можно отправить через прокси, запущенный на этом же VPS (например, каскад).",13f,MobilePalette.muted))
        val active = proxy.optString("active_profile_id")
        add(ui.card().apply {
            addView(ui.text(if(active.isEmpty()) "Маршрут через прокси выключен" else "Маршрут через прокси включён",15f,bold = true))
            if(active.isNotEmpty()) {
                addView(ui.text(if(proxy.optBoolean("route_active")) "Маршрут применён" else "Маршрут применяется...",13f,MobilePalette.muted))
                if(!proxy.optBoolean("port_listening",true)) addView(ui.text("Порт прокси не слушает",13f,android.graphics.Color.rgb(232,120,112)))
                proxy.optString("health_error").takeUnless { it.isBlank() || it=="null" }?.let { addView(ui.text(it,13f,android.graphics.Color.rgb(232,120,112))) }
                addView(ui.button("Выключить") { action("Прокси выключен") { admin.post("/api/local-proxy/deactivate") } },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(8) })
            }
        })
        add(ui.button("Добавить профиль SOCKS5",primary = true) { editProxy(null) })
        (proxy.optJSONArray("profiles") ?: JSONArray()).objects().forEach { profile ->
            val id = profile.optString("id")
            add(ui.tile(profile.optString("name")+if(id==active) " · активен" else "","127.0.0.1:${profile.optInt("port",45000)}"+if(profile.optString("username").isNotEmpty()) " · с авторизацией" else "",R.drawable.ic_mobile_core) { editProxy(profile) })
            if(id!=active) add(ui.button("Сделать активным") { action("Прокси включён") { admin.post("/api/local-proxy/activate/"+encode(id)) } })
        }
    }
    private fun editProxy(profile: JSONObject?) {
        val body = ui.column(16)
        val name = input("Название",profile?.optString("name").orEmpty()); val port = input("Порт на 127.0.0.1",(profile?.optInt("port",45000) ?: 45000).toString(),number = true)
        val user = input("Логин (необязательно)",profile?.optString("username").orEmpty()); val secret = input("Пароль (необязательно)",profile?.optString("password").orEmpty(),secret = true)
        listOf(name,port,user,secret).forEach(body::addView)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        if(profile!=null) body.addView(ui.button("Удалить профиль",danger = true) { dialog?.dismiss(); action("Удалено") { admin.delete("/api/local-proxy/profiles/"+encode(profile.optString("id"))) } },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(8) })
        dialog = form(profile?.optString("name") ?: "Новый SOCKS5",body) {
            val number = port.text.toString().toIntOrNull()
            if(number==null || number !in 1..65535) { toast("Некорректный порт"); return@form false }
            val json = JSONObject().put("name",name.text.toString().trim().take(64)).put("port",number).put("username",user.text.toString()).put("password",secret.text.toString())
            action("Сохранено") { if(profile==null) admin.post("/api/local-proxy",json) else admin.put("/api/local-proxy/profiles/"+encode(profile.optString("id")),json) }; true
        }
    }

    // Settings.
    private fun loadSettings() = request({ admin.settings() }) { loaded ->
        settings = loaded
        if(section!=4) return@request
        content.removeAllViews()
        if(loaded.optBoolean("restart_required")) add(ui.text("Пароль владельца изменён: перезапустите сервер, чтобы он применился.",13f,android.graphics.Color.rgb(232,120,112)))
        val owner = input("Пароль владельца",loaded.optString("main_password"))
        add(ui.card().apply {
            addView(ui.text("Пароль владельца",15f,bold = true)); addView(owner)
            addView(ui.button("Сгенерировать") { owner.setText(Mrtt.generatePassword()) })
        })
        val dns = Choice(loaded.optString("dns_provider","yandex"))
        val primary = input("Основной IPv4",loaded.optString("dns_primary")); val secondary = input("Запасной IPv4 (необязательно)",loaded.optString("dns_secondary"))
        val restart = Choice(loaded.optInt("auto_restart_interval_hours").toString())
        add(ui.card().apply {
            addView(ui.text("DNS для клиентов",15f,bold = true))
            fun customVisible() { val on = dns.value=="custom"; primary.visibility = if(on) View.VISIBLE else View.GONE; secondary.visibility = primary.visibility }
            picker(this,"Провайдер",dns,DNS_PROVIDERS) { customVisible() }
            addView(primary); addView(secondary); customVisible()
            picker(this,"Автоперезапуск",restart,listOf("0" to "Выключен","12" to "12 ч","24" to "24 ч","48" to "48 ч","72" to "72 ч"))
        })
        var vkToken = loaded.optString("vk_token")
        val vkHint = ui.text("",13f,MobilePalette.muted)
        lateinit var vkBind: android.widget.TextView
        lateinit var vkUnbind: android.widget.TextView
        fun vkShow() {
            vkHint.text = if(vkToken.isBlank()) "Не привязан. Привяжите аккаунт, и конфиги с режимом «Аккаунт сервера» будут получать хеши автоматически."
                else "Привязан. Конфиги с режимом «Аккаунт сервера» получают хеши с этого аккаунта автоматически."
            vkBind.text = if(vkToken.isBlank()) "Привязать VK" else "Перепривязать"
            vkUnbind.visibility = if(vkToken.isBlank()) View.GONE else View.VISIBLE
        }
        // The client's one VK binding hands its token to the server.
        vkBind = ui.button("") {
            request({ check(VkRelayAccount.linked()) { "Сначала привяжите аккаунт VK в настройках VK-релея" }; VkRelayAccount.tokenForCalls() }) { token -> vkToken = token; vkShow() }
        }
        vkUnbind = ui.button("Отвязать") { vkToken = ""; vkShow() }
        val hashBox = ui.column()
        val common = hashFields(hashBox,loaded.optString("vk_hashes"))
        add(ui.card().apply {
            addView(ui.text("VK-аккаунт сервера",15f,bold = true)); addView(vkHint); addView(vkBind); addView(vkUnbind)
            hint(this,"Общие VK-хеши, необязательно"); addView(hashBox)
            addView(ui.button("Применить ко всем конфигам") {
                action("VK применён ко всем конфигам") { admin.post("/api/links/batch-vk",JSONObject().put("vk_token",vkToken).put("vk_hashes",hashes(common)).put("apply_to_all_clients",true)) }
            })
        })
        vkShow()
        val room = input("Ссылка на комнату Jitsi",loaded.optString("jitsi_room").takeIf { it.isNotBlank() }?.let { "https://$it" }.orEmpty())
        val seats = Choice(loaded.optInt("jitsi_seats",6).toString())
        add(ui.card().apply {
            addView(ui.text("Jitsi",15f,bold = true)); addView(room)
            picker(this,"Участников в комнате",seats,listOf(2,4,6,8,12,16).map { it.toString() to it.toString() })
            hint(this,if(Mrtt.parseJitsiRoom(room.text.toString())==null) "Создайте конференцию на любом сервере Jitsi и вставьте ссылку: сервер зайдёт в неё сразу после сохранения, переустанавливать не нужно."
                else "Сервер сидит в ${loaded.optString("jitsi_room")}. Новые ссылки подписок несут эту комнату; у своего профиля её можно вставить в «Комнату Jitsi».")
        })
        val exits = (loaded.optJSONArray("exits") ?: JSONArray()).objects()
        val priority = Choice(if(loaded.optString("exit_priority")=="config") "config" else "")
        val defaultExit = Choice(loaded.optString("default_exit").takeIf { id -> exits.any { it.optString("id")==id } }.orEmpty())
        add(ui.card().apply {
            addView(ui.text("Выходной IP",15f,bold = true))
            picker(this,"Приоритет выходного IP",priority,listOf("" to "Настройка сервера","config" to "Настройка конфига"))
            picker(this,"Выход сервера",defaultExit,listOf("" to "Этот сервер")+exits.map { it.optString("id") to it.optString("name") })
            exits.forEach { exit ->
                hint(this,"${exit.optString("name")} · ${exit.optString("host")}")
                addView(ui.button("Убрать") { removeExit(exit,exits) })
            }
            addView(ui.button("Добавить выход через другой VPS") { addExit(exits) },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) })
            hint(this,"Трафик клиентов выходит через выбранный VPS по протоколу MRTT, UDP тоже. Если связь между серверами упадёт, такие клиенты теряют интернет, а не выходят через этот сервер.")
        })
        add(ui.button("Сохранить настройки",primary = true) {
            val roomValue = room.text.toString().trim()
            val parsedRoom = if(roomValue.isEmpty()) "" else Mrtt.parseJitsiRoom(roomValue)
            if(parsedRoom==null) { toast("Неверная ссылка на комнату Jitsi"); return@button }
            if(!Mrtt.validPassword(owner.text.toString())) { toast("Пароль владельца: без пробелов и «|», не длиннее 128 байт"); return@button }
            val json = JSONObject().put("default_exit",defaultExit.value).put("exit_priority",priority.value).put("main_password",owner.text.toString())
                .put("dns_provider",dns.value).put("auto_restart_interval_hours",restart.value.toInt()).put("vk_token",vkToken).put("vk_hashes",hashes(common))
                .put("jitsi_room",parsedRoom).put("jitsi_seats",seats.value.toInt())
            if(dns.value=="custom") json.put("dns_primary",primary.text.toString().trim()).put("dns_secondary",secondary.text.toString().trim())
            action("Настройки сохранены") { admin.post("/api/settings",json) }
        })
        add(ui.card().apply {
            addView(ui.text("Обслуживание",15f,bold = true))
            listOf<Pair<String,() -> Unit>>(
                "Экспорт" to { exportFile.launch("mrtt-config.json") },
                "Импорт" to { importFile.launch(arrayOf("application/json","text/*","*/*")) },
                "Очистить память" to { action("Память освобождена") { admin.post("/api/memory/trim") } },
                "Перезапустить" to { confirm("Перезапуск сервера","Перезапустить MRTT на сервере? Все клиенты переподключатся.","Перезапустить") { action("Перезапуск запрошен") { admin.post("/api/reboot") } } },
            ).forEach { (title,run) -> addView(ui.button(title) { run() },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) }) }
        })
    }
    private fun addExit(exits: List<JSONObject>) = request({ DeployStore.all().filter { other -> other.kind=="mrtt" && other.id!=server.id && other["installed"]=="true" && exits.none { it.optString("host")==other["host"] } } }) { candidates ->
        if(candidates.isEmpty()) { toast("Других серверов нет: добавьте VPS в разделе деплоя MRTT."); return@request }
        val body = ui.column(16)
        val chosen = Choice(candidates.first().id)
        picker(body,"Сервер",chosen,candidates.map { it.id to it["name"].ifBlank { it["host"] } })
        hint(body,"Этот сервер подключится к выбранному как обычный конфиг, он появится там в списке конфигов. Удалять его там не нужно, для этого есть кнопка «Убрать» здесь.")
        form("Выход через другой VPS",body,"Добавить") {
            val target = candidates.first { it.id==chosen.value }
            status.text = "Подключаем серверы..."; status.visibility = View.VISIBLE
            action("Выход добавлен") {
                val created = admin.addExit(target)
                val list = JSONArray(exits).put(created)
                admin.post("/api/settings",JSONObject().put("exits",list).put("default_exit",settings.optString("default_exit")))
            }
            true
        }
    }
    private fun removeExit(exit: JSONObject,exits: List<JSONObject>) = confirm("Убрать выход","Убрать выход через ${exit.optString("name")}? Конфиги с этим выходом пойдут через этот сервер.","Убрать") {
        action("Выход убран") {
            // Settings first: clients must not stay routed into a table that is gone.
            admin.post("/api/settings",JSONObject().put("exits",JSONArray(exits.filter { it.optString("id")!=exit.optString("id") }))
                .put("default_exit",settings.optString("default_exit").takeUnless { it==exit.optString("id") }.orEmpty()))
            admin.removeExit(exit.optString("id"),DeployStore.all().firstOrNull { it.kind=="mrtt" && it["host"]==exit.optString("host") },exit.optString("remote_password"))
        }
    }

    // Statistics and logs: live while their section is open.
    private fun loadStats() = request({ admin.stats() }) { s ->
        if(section!=5) return@request
        content.removeAllViews()
        val uptime = s.optLong("uptime")
        fun card(rows: List<Pair<String,String>>) = add(ui.card().apply {
            rows.forEach { (label,value) -> addView(LinearLayout(this@MrttServerActivity).apply {
                addView(ui.text(label,13f,MobilePalette.muted),LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.text(value.ifBlank { "—" },13f))
            }) }
        })
        card(listOf("Статус" to s.optString("status"),"Публичный IP" to s.optString("public_ip"),"Порт подключения" to s.optInt("peer_port").toString(),
            "Аптайм" to "${uptime/86400} д ${uptime%86400/3600} ч ${uptime%3600/60} мин","Сессий сейчас" to s.optInt("active").toString(),
            "Подключений всего" to s.optLong("total").toString(),"Подписок" to s.optInt("passwords").toString()))
        card(listOf("CPU" to "${s.optInt("cpu_normalized")}%","Память" to s.optString("ram"),"Пик памяти" to s.optString("ram_peak"),
            "Трафик от клиентов" to bytes(s.optLong("up")),"Трафик к клиентам" to bytes(s.optLong("down")),
            "UDP пакетов RX / TX" to "${s.optLong("udp_rx_packets")} / ${s.optLong("udp_tx_packets")}","UDP ошибок RX / TX" to "${s.optLong("udp_rx_errors")} / ${s.optLong("udp_tx_errors")}",
            "Аллокатор" to s.optString("allocator")))
        card(listOf("Локальный прокси" to if(s.optBoolean("local_proxy_active")) "включён" else "выключен",
            "TCP / UDP через прокси" to "${s.optInt("local_proxy_tcp_sessions")} / ${s.optInt("local_proxy_udp_flows")}",
            "Горячих сессий" to "${s.optInt("hot_sessions")} / ${s.optInt("hot_session_capacity")}","Буфер логов" to "${s.optInt("mem_log_ring")} строк","DPI-кадров в буфере" to s.optInt("mem_dpi_ring").toString()))
        add(ui.button("Обновить") { loadStats(); toast("Обновлено") })
    }
    private fun loadLogs() = request({ admin.logs() }) { json ->
        if(section!=6) return@request
        content.removeAllViews()
        add(CheckBox(this).apply {
            text = "Запись логов на сервере"; setTextColor(MobilePalette.text); isChecked = json.optBoolean("active")
            setOnClickListener { val on = isChecked; action(null) { admin.post("/api/logs/toggle",JSONObject().put("active",on)) } }
        })
        val array = json.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until array.length()).map { array.optString(it) }
        add(ui.button("Поделиться логом сервера") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,lines.joinToString("\n")),"MRTT server log"))
        })
        add(ui.button("Очистить логи") { action("Логи очищены") { admin.post("/api/logs/clear") } })
        add(ui.card().apply { addView(ui.text(if(lines.isEmpty()) "Логи пусты" else lines.takeLast(400).joinToString("\n"),12f,mono = true).apply { setTextIsSelectable(true) }) })
    }
    companion object {
        val DNS_PROVIDERS = listOf("yandex" to "Yandex DNS","cloudflare" to "Cloudflare","google" to "Google","adguard" to "AdGuard","xbox" to "Xbox DNS","comms" to "Comss DNS",
            "geohide" to "GeoHide DNS","quad9" to "Quad9","opendns" to "OpenDNS","rostelecom" to "Rostelecom DNS","nextdns" to "NextDNS","bizone" to "BI.ZONE DNS","nsdi" to "НСДИ DNS","custom" to "Ручной DNS")
    }
}
