package app.rawline.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
import app.rawline.mobile.deploy.MrttApiException
import app.rawline.mobile.deploy.SshSessions
import app.rawline.mobile.outbound.import.ProfileImport
import app.rawline.mobile.outbound.types.Mrtt
import app.rawline.mobile.outbound.types.VkCallHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A deployed MRTT server with the sections of its own app. One SSH connection serves every request, as in the original;
 * each section keeps its page, so switching shows it at once and its data is refreshed behind it.
 */
class MrttServerActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var server: DeployServer
    private lateinit var admin: MrttAdmin
    private lateinit var scroll: ScrollView
    private lateinit var spinner: ProgressBar
    private lateinit var problem: LinearLayout
    private lateinit var waiting: TextView
    private lateinit var tabStrip: HorizontalScrollView
    private val tabViews = mutableListOf<TextView>()
    private val sections = listOf("Конфиги","Группы","Маршруты","Прокси","Настройки","Статистика","Логи")
    private val pages by lazy { sections.map { ui.column() } }
    private lateinit var content: LinearLayout
    private val drawn = HashMap<Int,String>()
    private val loading = HashMap<Int,Job>()
    private val errors = HashMap<Int,String>()
    private val scrollTops = IntArray(7)
    private var actions = 0
    private var section = 0
    private var live: Job? = null
    private var clients = JSONArray()
    private var groups = JSONArray()
    private var rules = JSONArray()
    private var settings = JSONObject()
    private var search = ""
    private var groupFilter: String? = null
    private val selected = linkedSetOf<String>()
    private var filterBox: LinearLayout? = null
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
            confirm("Импорт конфигурации","Добавить конфиги, группы и правила из файла и применить его настройки? Существующие подписки сохранятся.","Импортировать") {
                action("Импорт выполнен") { admin.request("POST","/api/import",json) }
            }
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
        spinner = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(MobilePalette.text) }
        toolbar.addView(spinner,Toolbar.LayoutParams(ui.dp(24),ui.dp(24),Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(16) })
        val tabs = LinearLayout(this).apply { setPadding(ui.dp(16),0,ui.dp(16),ui.dp(8)) }
        sections.forEachIndexed { index,title ->
            tabs.addView(ui.button(title) { open(index) }.also { tabViews += it },LinearLayout.LayoutParams(-2,-2).apply { marginEnd = ui.dp(6) })
        }
        tabStrip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        problem = ui.card().apply { visibility = View.GONE }
        waiting = ui.text("Загрузка...",14f,MobilePalette.muted).apply { setPadding(ui.dp(4),ui.dp(8),0,0); visibility = View.GONE }
        val box = FrameLayout(this)
        pages.forEach { page -> page.visibility = View.GONE; box.addView(page,FrameLayout.LayoutParams(-1,-2)) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(24))
                addView(problem,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(10) }); addView(waiting); addView(box)
            })
        }
        root.addView(toolbar); root.addView(tabStrip)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id==id } }.getOrNull() }
            if(loaded==null) { finish(); return@launch }
            server = loaded; admin = MrttAdmin(loaded)
            toolbar.title = loaded["name"].ifBlank { "Сервер MRTT" }; toolbar.subtitle = loaded["host"]
            open(0)
        }
    }
    override fun onDestroy() {
        if(isFinishing && this::server.isInitialized) SshSessions.close(server)
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun friendly(error: Throwable) = when(error) {
        is MrttApiException -> error.message.orEmpty()
        else -> "Нет связи с сервером: "+(error.message ?: error.javaClass.simpleName)
    }
    private fun showState() {
        val busy = actions>0 || loading[section]?.isActive==true
        spinner.visibility = if(busy) View.VISIBLE else View.GONE
        val error = errors[section]
        waiting.visibility = if(error==null && loading[section]?.isActive==true && pages[section].childCount==0) View.VISIBLE else View.GONE
        problem.visibility = if(error==null) View.GONE else View.VISIBLE
        if(error!=null) {
            problem.removeAllViews()
            problem.addView(ui.text("Не удалось связаться с сервером",15f,bold = true))
            ui.hint(problem,error,android.graphics.Color.rgb(232,120,112))
            ui.hint(problem,"Проверьте SSH-доступ и что MRTT установлен на сервере: «Управление сервером» → «Установить / обновить».")
            problem.addView(ui.textButton("Повторить") { reload(section,true) })
        }
    }
    /** Loads a section's data on a background thread; the page is drawn anew only when the data changed, so a form being filled stays. */
    private fun <T: Any> load(index: Int,call: suspend CoroutineScope.() -> T,draw: (T) -> Unit) {
        if(loading[index]?.isActive==true) return
        lateinit var job: Job
        job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { coroutineScope { call() } } }
                result.exceptionOrNull()?.let { if(it is CancellationException) throw it }
                result.onSuccess { data ->
                    errors.remove(index)
                    val key = data.toString()
                    if(drawn[index]!=key || pages[index].childCount==0) { drawn[index] = key; content = pages[index]; draw(data) }
                }.onFailure { errors[index] = friendly(it) }
            } finally {
                if(loading[index]===job) loading.remove(index)
                showState()
            }
        }
        loading[index] = job; job.start(); showState()
    }
    /** A change on the server: a toast as in the original, then the section reloads. */
    private fun <T> request(call: suspend () -> T,done: (T) -> Unit) {
        actions++; showState()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { call() } }
            actions--; showState()
            result.onSuccess(done).onFailure { toast(friendly(it)) }
        }
    }
    private fun action(message: String?,call: () -> Unit) = request({ call() }) { if(message!=null) toast(message); reload(section,true) }
    private fun open(index: Int) {
        scrollTops[section] = scroll.scrollY
        section = index; live?.cancel()
        pages.forEachIndexed { i,page -> page.visibility = if(i==index) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { i,tab ->
            val chosen = i==index
            tab.background = if(chosen) MobilePalette.selectedShape(this) else MobilePalette.rowShape(this)
            tab.setTextColor(if(chosen) MobilePalette.text else ui.accent)
        }
        tabViews.getOrNull(index)?.let { tab -> tabStrip.post { tabStrip.smoothScrollTo((tab.left-(tabStrip.width-tab.width)/2).coerceAtLeast(0),0) } }
        scroll.post { scroll.scrollTo(0,scrollTops[index]) }
        reload(index)
        if(index==5) live = lifecycleScope.launch { while(true) { delay(10_000); reload(5) } }
        if(index==6) live = lifecycleScope.launch { while(true) { delay(5_000); reload(6) } }
        showState()
    }
    private fun reload(index: Int,force: Boolean = false) {
        if(!this::admin.isInitialized) return
        if(force) { loading.remove(index)?.cancel() }
        when(index) {
            0 -> loadConfigs()
            1 -> loadGroups()
            2 -> loadRules()
            3 -> loadProxy()
            4 -> loadSettings()
            5 -> loadStats()
            else -> loadLogs()
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
    private fun confirm(title: String,text: String,button: String,run: () -> Unit) {
        MobileAlertDialogBuilder(this).setTitle(title).setMessage(text).setNegativeButton(android.R.string.cancel,null).setPositiveButton(button) { _,_ -> run() }.show()
    }
    private fun checkRow(parent: LinearLayout,label: String,checked: Boolean) = CheckBox(this).apply {
        text = label; setTextColor(MobilePalette.text); isChecked = checked; parent.addView(this)
    }
    private fun groupOptions() = listOf("" to "Без группы")+groups.objects().map { it.optString("id") to it.optString("name") }
    /** «Ссылки VK звонков: N», as the original's button: it opens the six fields, and the client's own calls when VK is linked. */
    private fun hashesButton(parent: LinearLayout,initial: String): () -> String {
        var value = initial.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        lateinit var button: TextView
        fun caption() = if(value.isEmpty()) "Ссылки VK звонков" else "Ссылки VK звонков: ${value.size}"
        button = ui.button(caption()) {
            var fields = emptyList<EditText>()
            ui.sheet("Ссылки VK звонков","Сохранить",{
                value = fields.map { VkCallHash.strip(it.text.toString()) }.filter { it.isNotEmpty() }.distinct().take(6)
                button.text = caption(); true
            }) { body,_ ->
                ui.hint(body,"Ссылка на звонок или его хеш, до шести.")
                fields = (0 until 6).map { index -> ui.field(body,"Звонок ${index+1}",value.getOrNull(index).orEmpty()) }
                ui.createdCallsButton(body,6) { picked ->
                    val free = fields.filter { it.text.isBlank() }
                    picked.filter { hash -> fields.none { VkCallHash.strip(it.text.toString())==hash } }.zip(free).forEach { (hash,field) -> field.setText(hash) }
                }
            }
        }
        parent.addView(button,LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(6) })
        return { value.joinToString(",") }
    }
    private fun share(title: String,link: String) = ui.sheet(title) { body,_ ->
        body.addView(ui.qrRow(link)); body.addView(ui.linkRow("Ссылка",link,lines = 4))
        body.addView(ui.button("Поделиться") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link),title))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(12) })
    }

    // Configs and subscriptions.
    private fun loadConfigs() = load(0,{
        val loadedSettings = async { runCatching { admin.settings() }.getOrDefault(JSONObject()) }
        val loadedClients = async { admin.clients() }
        val loadedGroups = async { admin.groups() }
        Triple(loadedClients.await(),loadedGroups.await(),loadedSettings.await())
    }) { (loadedClients,loadedGroups,loadedSettings) ->
        clients = loadedClients; groups = loadedGroups; settings = loadedSettings
        selected.retainAll(clients.objects().map { it.optString("password") }.toSet())
        // The head is made once: a refresh must not take the search field from under the finger.
        if(listHolder==null) {
            add(ui.button("Создать конфиг",primary = true) { editClient(null) })
            add(ui.button("Создать подписку") { buildSubscription() })
            ui.field(content,"Поиск по имени",search).addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) {}
                override fun onTextChanged(s: CharSequence?,a: Int,b: Int,c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if(search!=s.toString()) { search = s.toString(); showClientRows() } }
            })
            filterBox = ui.column().also { content.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(8) }) }
            listHolder = ui.column().also { content.addView(it) }
        }
        showFilters(); showClientRows()
    }
    private fun showFilters() {
        val box = filterBox ?: return
        box.removeAllViews()
        val options = listOf<String?>(null,"")+groups.objects().map { it.optString("id") }
        val labels = listOf("Все","Без группы")+groups.objects().map { it.optString("name") }
        box.addView(ui.chips(options.mapIndexed { index,id -> labels[index] to { groupFilter = id; showFilters(); showClientRows() } },options.indexOf(groupFilter)))
    }
    private fun showClientRows() {
        val holder = listHolder ?: return
        holder.removeAllViews()
        if(selected.isNotEmpty()) holder.addView(ui.card().apply {
            addView(ui.text("Выбрано: ${selected.size}",15f,bold = true))
            val chosen = selected.toList()
            fun batch(path: String,body: JSONObject,message: String) = action(message) { admin.post("/api/clients/batch-$path",body.put("passwords",JSONArray(chosen))) }
            addView(ui.chips(listOf(
                "Вкл" to { batch("toggle",JSONObject().put("active",true),"Включены") },
                "Выкл" to { batch("toggle",JSONObject().put("active",false),"Выключены") },
                "+30 дней" to { batch("extend",JSONObject().put("days",30),"Продлены на 30 дней") },
                "Сброс трафика" to { batch("reset-traffic",JSONObject(),"Трафик сброшен") },
                "В группу" to {
                    var target = ""
                    ui.sheet("Перенести в группу","Перенести",{ batch("group",JSONObject().put("group_id",target),"Перенесено"); true }) { body,_ ->
                        ui.dropdown(body,"Группа",groupOptions(),target) { target = it }
                    }
                },
                "Удалить" to { confirm("Удалить конфиги","Удалить выбранные конфиги (${selected.size})? Ссылки перестанут работать.","Удалить") {
                    selected.clear(); batch("delete",JSONObject(),"Удалено")
                } },
                "Снять выбор" to { selected.clear(); showClientRows() },
            )).apply { setPadding(0,ui.dp(8),0,0) })
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(8) })
        val visible = clients.objects().filter { groupFilter==null || it.optString("group_id")==groupFilter }
            .filter { search.isBlank() || it.optString("name").contains(search,ignoreCase = true) }
            .sortedWith(compareBy({ !isOwner(it) },{ it.optString("name").lowercase() }))
        if(visible.isEmpty()) holder.addView(ui.text("Конфигов нет",14f,MobilePalette.muted))
        visible.forEach { client -> holder.addView(clientRow(client),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(8) }) }
    }
    private fun clientRow(client: JSONObject): View {
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
        val password = client.optString("password")
        fun toggle() { if(owner) return; if(password in selected) selected.remove(password) else selected.add(password); showClientRows() }
        return ui.column(12).apply {
            background = if(password in selected) MobilePalette.selectedShape(context) else MobilePalette.rowShape(context)
            isFocusable = true; setOnClickListener { editClient(client) }; setOnLongClickListener { toggle(); true }; MobileFeedback.apply(this)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                if(!owner) addView(CheckBox(context).apply { isChecked = password in selected; setOnClickListener { toggle() } })
                addView(ui.column().apply {
                    addView(ui.text(client.optString("name"),15f,bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(ui.text(listOfNotNull(state,group,if(expires==0L) "бессрочно" else "до ${date(expires)}").joinToString(" · "),13f,
                        if(active || owner) MobilePalette.muted else android.graphics.Color.rgb(232,120,112)))
                },LinearLayout.LayoutParams(0,-2,1f))
            })
            addView(ui.text("Устройства: ${devices.length()}/$limit (в сети $online) · ↑ ${bytes(client.optLong("up"))} ↓ ${bytes(client.optLong("down"))}",13f,MobilePalette.muted)
                .apply { setPadding(0,ui.dp(4),0,0) })
        }
    }
    private fun editClient(client: JSONObject?) {
        val owner = client?.let(::isOwner)==true
        var name: EditText? = null
        var days: EditText? = null
        lateinit var workers: EditText
        lateinit var devices: EditText
        var groupId = client?.optString("group_id").orEmpty()
        var transport = client?.optString("transport").orEmpty()
        var roomOff = client?.optBoolean("link_room_off")==true
        var exit = client?.optString("exit").orEmpty()
        var source = when { client?.optBoolean("vk_token")==true -> "server"; !client?.optString("vk_hashes").isNullOrBlank() -> "manual"; else -> "client" }
        var hashes: () -> String = { "" }
        val password = client?.let { encode(it.optString("password")) }
        lateinit var sheet: MobileSheetDialog
        fun act(message: String,call: () -> Unit) { sheet.dismiss(); action(message,call) }
        sheet = ui.sheet(if(client==null) "Новый конфиг" else client.optString("name"),if(client==null) "Создать" else "Сохранить",{
            val workerCount = workers.text.toString().toIntOrNull() ?: -1
            val dayCount = days?.text?.toString()?.toIntOrNull() ?: 0
            val deviceCount = devices.text.toString().toIntOrNull() ?: -1
            if((!owner && name?.text.isNullOrBlank()) || dayCount !in 0..3650 || (workerCount!=0 && workerCount !in 3..100) || deviceCount !in 0..1000) {
                toast("Проверьте имя, срок, потоки и устройства"); return@sheet false
            }
            val json = JSONObject().put("name",name?.text?.toString()?.trim()?.take(128) ?: client?.optString("name").orEmpty()).put("group_id",groupId).put("days",dayCount)
                .put("hash",if(source=="manual") hashes() else "").put("vk_token",source=="server").put("workers",workerCount)
                .put("max_devices",deviceCount).put("transport",transport).put("link_room_off",roomOff).put("exit",exit)
            if(client==null) action("Конфиг создан") { admin.post("/api/clients",json) }
            else {
                json.put("peer_port",client.optInt("peer_port")).put("local_port",client.optInt("local_port"))
                action("Сохранено") { admin.post("/api/clients/"+encode(client.optString("password")),json) }
            }
            true
        }) { body,_ ->
            if(!owner) {
                name = ui.field(body,"Имя",client?.optString("name").orEmpty())
                ui.dropdown(body,"Группа",groupOptions(),groupId) { groupId = it }
                val remaining = client?.optLong("expires")?.let { if(it==0L) 0 else ((it*1000-System.currentTimeMillis()).coerceAtLeast(0)/86_400_000L+1).toInt() } ?: 30
                days = ui.field(body,"Срок, дней (0 — бессрочно)",remaining.toString(),number = true)
            }
            workers = ui.field(body,"Потоки (0 — как в приложении, 3–100)",(client?.optInt("workers") ?: 0).toString(),number = true)
            devices = ui.field(body,"Устройств (0 — без лимита)",(client?.optInt("max_devices",1) ?: 0).toString(),number = true)
            val roomBox = ui.column()
            fun showRoom() {
                roomBox.removeAllViews()
                if(transport!="jitsi") return
                ui.dropdown(roomBox,"Комната Jitsi",listOf("server" to "Комната сервера","client" to "Без комнаты"),if(roomOff) "client" else "server") { roomOff = it=="client"; showRoom() }
                ui.hint(roomBox,if(roomOff) "В ссылке нет комнаты: клиент вставляет её сам. Сервер ждёт его только в своей комнате." else "В ссылку идёт комната, в которой сидит сервер.")
            }
            ui.dropdown(body,"Транспорт в ссылке",listOf("" to "Не задан","vk" to "VK","jitsi" to "Jitsi"),transport) { transport = it; showRoom() }
            val exits = settings.optJSONArray("exits") ?: JSONArray()
            if(exits.length()>0) {
                val configDecides = settings.optString("exit_priority")=="config"
                ui.dropdown(body,"Выходной IP",listOf("" to "Как на сервере","direct" to "Этот сервер")+exits.objects().map { it.optString("id") to it.optString("name") },exit,configDecides) { exit = it }
                if(!configDecides) ui.hint(body,"Сейчас действует настройка сервера. Выбор появится, когда в настройках сервера приоритет выходного IP будет «Настройка конфига».")
            }
            body.addView(roomBox); showRoom()
            val hashBox = ui.column()
            fun showHashes() {
                hashBox.removeAllViews(); hashes = { "" }
                when(source) {
                    "manual" -> hashes = hashesButton(hashBox,client?.optString("vk_hashes").orEmpty())
                    "server" -> ui.hint(hashBox,"В ссылку идут хеши с VK-аккаунта, привязанного к серверу.")
                    else -> ui.hint(hashBox,"В ссылке нет хешей: клиент использует свой аккаунт VK или свои хеши.")
                }
            }
            ui.dropdown(body,"Хеши VK",listOf("manual" to "Свои хеши","server" to "Аккаунт сервера","client" to "Без хешей"),source) { source = it; showHashes() }
            body.addView(hashBox); showHashes()
            if(client!=null && password!=null) {
                ui.heading(body,"Устройства")
                val list = client.optJSONArray("devices") ?: JSONArray()
                if(list.length()==0) ui.hint(body,"Нет привязанных устройств")
                list.objects().forEach { device ->
                    val seen = device.optLong("last_seen")
                    body.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ui.column().apply {
                            addView(ui.text((if(device.optBoolean("online")) "● " else "○ ")+device.optString("name").ifBlank { device.optString("id").take(8) },15f).apply { maxLines = 1 })
                            addView(ui.text("${device.optString("ip")} · "+(if(seen>0) date(seen) else "—"),13f,MobilePalette.muted))
                        },LinearLayout.LayoutParams(0,-2,1f))
                        addView(ui.textButton("Отвязать") { act("Устройство отвязано") { admin.post("/api/clients/$password/unbind?device="+encode(device.optString("id"))) } })
                    })
                }
                if(list.length()>0) body.addView(ui.textButton("Отвязать все устройства") { act("Устройство отвязано") { admin.post("/api/clients/$password/unbind?device=") } })
                ui.hint(body,"Трафик: ↑ ${bytes(client.optLong("up"))} ↓ ${bytes(client.optLong("down"))} · сессий ${client.optInt("active_sessions")}")
                val buttons = mutableListOf<Pair<String,() -> Unit>>(
                    "Ссылка и QR" to { share(client.optString("name"),client.optString("link")) },
                    "В мои профили" to { addToProfiles(client.optString("link"),client.optString("name")+" · "+server["name"].ifBlank { server["host"] }) },
                )
                if(!owner) {
                    buttons += (if(client.optBoolean("active")) "Выключить" else "Включить") to { act("Статус изменён") { admin.post("/api/clients/$password/toggle") } }
                    buttons += "+30 дней" to { act("Продлено на 30 дней") { admin.post("/api/clients/$password/extend",JSONObject().put("days",30)) } }
                }
                buttons += "Сброс трафика" to { act("Трафик сброшен") { admin.post("/api/clients/$password/reset-traffic") } }
                if(!owner) buttons += "Удалить" to { confirm("Удалить конфиг","Удалить «${client.optString("name")}»? Ссылка перестанет работать на всех устройствах.","Удалить") {
                    act("Конфиг удалён") { admin.delete("/api/clients/$password") }
                } }
                body.addView(ui.chips(buttons).apply { setPadding(0,ui.dp(10),0,0) })
            }
        }
    }
    private fun addToProfiles(link: String,name: String) = lifecycleScope.launch {
        val added = withContext(Dispatchers.IO) { runCatching {
            ProfileImport.parse(link).outbounds.onEach { it.name = name; ProfileManager.createProfile(app.rawline.mobile.database.DataStore.currentGroupId(),it) }.size
        } }
        toast(if(added.getOrDefault(0)>0) "Добавлено в профили" else "Не удалось добавить профиль")
    }
    private fun buildSubscription() {
        lateinit var name: EditText
        var checks = emptyList<Pair<CheckBox,JSONObject>>()
        ui.sheet("Новая подписка","Создать",{
            val chosen = checks.filter { it.first.isChecked }.map { it.second.optString("name") to it.second.optString("link") }
            if(name.text.isBlank() || chosen.isEmpty()) { toast("Укажите название и выберите конфиги"); return@sheet false }
            share(name.text.toString().trim(),Mrtt.encodeSubscription(name.text.toString().trim().take(64),chosen)); true
        }) { body,_ ->
            name = ui.field(body,"Название подписки")
            ui.hint(body,"Подписка: одна ссылка и QR-код, внутри которых выбранные конфиги. Тот, кто её добавит, получит их все в разделе профилей.")
            checks = clients.objects().map { client -> checkRow(body,client.optString("name"),false) to client }
        }
    }

    // Groups.
    private fun loadGroups() = load(1,{
        val loaded = async { admin.groups() }; val loadedRules = async { admin.rules() }
        loaded.await() to loadedRules.await()
    }) { (loaded,loadedRules) ->
        groups = loaded; rules = loadedRules
        content.removeAllViews()
        add(ui.button("Создать группу",primary = true) {
            lateinit var name: EditText
            ui.sheet("Новая группа","Создать",{
                if(name.text.isBlank()) return@sheet false
                action("Группа создана") { admin.post("/api/groups",JSONObject().put("name",name.text.toString().trim().take(64))) }; true
            }) { body,_ -> name = ui.field(body,"Название группы") }
        })
        if(groups.length()==0) add(ui.text("Групп пока нет",14f,MobilePalette.muted))
        groups.objects().forEach { group ->
            val route = rules.objects().firstOrNull { it.optString("id")==group.optString("routing_rule_id") }?.optString("name")
            add(ui.item(group.optString("name"),"Подписок: ${group.optInt("member_count")} · в сети ${group.optInt("active_sessions")} · ↑ ${bytes(group.optLong("up"))} ↓ ${bytes(group.optLong("down"))}"+(route?.let { " · маршрут «$it»" } ?: "")) { editGroup(group) })
        }
    }
    private fun editGroup(group: JSONObject) {
        lateinit var name: EditText
        var rule = group.optString("routing_rule_id")
        lateinit var sheet: MobileSheetDialog
        sheet = ui.sheet(group.optString("name"),"Сохранить",{
            if(name.text.isBlank()) return@sheet false
            action("Сохранено") { admin.put("/api/groups/"+encode(group.optString("id")),JSONObject().put("name",name.text.toString().trim().take(64)).put("routing_rule_id",rule)) }; true
        }) { body,_ ->
            name = ui.field(body,"Название группы",group.optString("name"))
            ui.dropdown(body,"Маршрутизация",listOf("" to "Нет")+rules.objects().map { it.optString("id") to it.optString("name") },rule) { rule = it }
            body.addView(ui.textButton("Удалить группу",danger = true) {
                confirm("Удалить группу","Удалить «${group.optString("name")}» вместе со всеми её конфигами (${group.optInt("member_count")})? Их ссылки перестанут работать.","Удалить") {
                    sheet.dismiss(); action("Группа удалена") { admin.delete("/api/groups/"+encode(group.optString("id"))) }
                }
            })
        }
    }

    // Routes.
    private fun loadRules() = load(2,{
        val loaded = async { admin.rules() }; val loadedGroups = async { admin.groups() }; val loadedClients = async { admin.clients() }
        Triple(loaded.await(),loadedGroups.await(),loadedClients.await())
    }) { (loaded,loadedGroups,loadedClients) ->
        rules = loaded; groups = loadedGroups; clients = loadedClients
        content.removeAllViews()
        add(ui.text("Правила раздельной маршрутизации, которые сервер отправляет клиентам: какие приложения, домены и IP идут мимо туннеля.",13f,MobilePalette.muted))
        add(ui.button("Создать правило",primary = true) { editRule(null) })
        if(rules.length()==0) add(ui.text("Правил пока нет",14f,MobilePalette.muted))
        rules.objects().forEach { rule ->
            add(ui.item(rule.optString("name"),"Приложений: ${rule.strings("packages").size} · доменов: ${rule.strings("direct_domains").size}${if(rule.optBoolean("template_domains")) " + шаблон" else ""} · IP: ${rule.strings("ips").size} · групп: ${rule.strings("assigned_groups").size} · подписок: ${rule.strings("assigned_clients").size}") { editRule(rule) })
        }
    }
    private fun lines(text: String) = text.split(Regex("[\\n,;]+")).map(String::trim).filter(String::isNotEmpty).distinct()
    private fun editRule(rule: JSONObject?) {
        val modes = listOf("false" to "Чёрный список","true" to "Белый список")
        var apps = (rule?.optBoolean("is_whitelist")==true).toString()
        var domainsMode = (rule?.optBoolean("domains_is_whitelist")==true).toString()
        var ipMode = (rule?.optBoolean("ip_is_whitelist")==true).toString()
        var template = rule?.optBoolean("template_domains")==true
        lateinit var name: EditText; lateinit var packages: EditText; lateinit var domains: EditText; lateinit var ips: EditText
        var assignedGroups = emptyList<Pair<CheckBox,String>>(); var assignedClients = emptyList<Pair<CheckBox,String>>()
        lateinit var sheet: MobileSheetDialog
        sheet = ui.sheet(rule?.optString("name") ?: "Новое правило","Сохранить",{
            val json = JSONObject().put("name",name.text.toString().trim().ifBlank { "Правило" }.take(64)).put("is_whitelist",apps=="true")
                .put("packages",JSONArray(lines(packages.text.toString()))).put("template_domains",template).put("domains_is_whitelist",domainsMode=="true")
                .put("direct_domains",JSONArray(lines(domains.text.toString()))).put("ip_is_whitelist",ipMode=="true").put("ips",JSONArray(lines(ips.text.toString())))
                .put("assigned_groups",JSONArray(assignedGroups.filter { it.first.isChecked }.map { it.second })).put("assigned_clients",JSONArray(assignedClients.filter { it.first.isChecked }.map { it.second }))
            action("Правило сохранено") { if(rule==null) admin.post("/api/routing-rules",json) else admin.put("/api/routing-rules/"+encode(rule.optString("id")),json) }; true
        }) { body,_ ->
            name = ui.field(body,"Название",rule?.optString("name").orEmpty())
            ui.heading(body,"Режим исключения приложений"); ui.segmented(body,modes,apps) { apps = it }
            packages = ui.field(body,"Пакеты, по одному в строке",rule?.strings("packages").orEmpty().joinToString("\n"),multiline = true)
            ui.heading(body,"Режим исключения доменов"); ui.segmented(body,modes,domainsMode) { domainsMode = it }
            ui.switchRow(body,"Готовый список RU доменов",template) { template = it }
            domains = ui.field(body,"Домены, по одному в строке",rule?.strings("direct_domains").orEmpty().joinToString("\n"),multiline = true)
            ui.heading(body,"Режим исключения адресов"); ui.segmented(body,modes,ipMode) { ipMode = it }
            ips = ui.field(body,"Адреса: IP, CIDR или диапазон, по одному в строке",rule?.strings("ips").orEmpty().joinToString("\n"),multiline = true)
            val chosenGroups = rule?.strings("assigned_groups").orEmpty(); val chosenClients = rule?.strings("assigned_clients").orEmpty()
            if(groups.length()>0) { ui.heading(body,"Применить к группам"); assignedGroups = groups.objects().map { checkRow(body,it.optString("name"),it.optString("id") in chosenGroups) to it.optString("id") } }
            ui.heading(body,"Применить к конфигам")
            assignedClients = clients.objects().map { checkRow(body,it.optString("name"),it.optString("password") in chosenClients) to it.optString("password") }
            if(rule!=null) body.addView(ui.textButton("Удалить правило",danger = true) { sheet.dismiss(); action("Правило удалено") { admin.delete("/api/routing-rules/"+encode(rule.optString("id"))) } })
        }
    }

    // Proxy.
    private fun loadProxy() = load(3,{ admin.proxy() }) { proxy ->
        content.removeAllViews()
        add(ui.text("Локальный SOCKS5 на сервере: весь трафик клиентов можно отправить через прокси, запущенный на этом же VPS (например, каскад).",13f,MobilePalette.muted))
        val active = proxy.optString("active_profile_id")
        add(ui.card().apply {
            addView(ui.text(if(active.isEmpty()) "Маршрут через прокси выключен" else "Маршрут через прокси включён",15f,bold = true))
            if(active.isNotEmpty()) {
                ui.hint(this,if(proxy.optBoolean("route_active")) "Маршрут применён" else "Маршрут применяется...")
                if(!proxy.optBoolean("port_listening",true)) ui.hint(this,"Порт прокси не слушает",android.graphics.Color.rgb(232,120,112))
                proxy.optString("health_error").takeUnless { it.isBlank() || it=="null" }?.let { ui.hint(this,it,android.graphics.Color.rgb(232,120,112)) }
                addView(ui.textButton("Выключить") { action("Прокси выключен") { admin.post("/api/local-proxy/deactivate") } })
            }
        })
        add(ui.button("Добавить профиль SOCKS5",primary = true) { editProxy(null) })
        (proxy.optJSONArray("profiles") ?: JSONArray()).objects().forEach { profile ->
            val id = profile.optString("id")
            add(ui.item(profile.optString("name")+if(id==active) " · активен" else "","127.0.0.1:${profile.optInt("port",45000)}"+if(profile.optString("username").isNotEmpty()) " · с авторизацией" else "") { editProxy(profile) }.apply {
                if(id!=active) addView(ui.textButton("Сделать активным") { action("Прокси включён") { admin.post("/api/local-proxy/activate/"+encode(id)) } })
            })
        }
    }
    private fun editProxy(profile: JSONObject?) {
        lateinit var name: EditText; lateinit var port: EditText; lateinit var user: EditText; lateinit var secret: EditText
        lateinit var sheet: MobileSheetDialog
        sheet = ui.sheet(profile?.optString("name") ?: "Новый SOCKS5","Сохранить",{
            val number = port.text.toString().toIntOrNull()
            if(number==null || number !in 1..65535) { toast("Некорректный порт"); return@sheet false }
            val json = JSONObject().put("name",name.text.toString().trim().take(64)).put("port",number).put("username",user.text.toString()).put("password",secret.text.toString())
            action("Сохранено") { if(profile==null) admin.post("/api/local-proxy",json) else admin.put("/api/local-proxy/profiles/"+encode(profile.optString("id")),json) }; true
        }) { body,_ ->
            name = ui.field(body,"Название",profile?.optString("name").orEmpty())
            port = ui.field(body,"Порт на 127.0.0.1",(profile?.optInt("port",45000) ?: 45000).toString(),number = true)
            user = ui.field(body,"Логин (необязательно)",profile?.optString("username").orEmpty())
            secret = ui.field(body,"Пароль (необязательно)",profile?.optString("password").orEmpty(),secret = true)
            if(profile!=null) body.addView(ui.textButton("Удалить профиль",danger = true) { sheet.dismiss(); action("Удалено") { admin.delete("/api/local-proxy/profiles/"+encode(profile.optString("id"))) } })
        }
    }

    // Settings.
    private fun loadSettings() = load(4,{ admin.settings() }) { loaded ->
        settings = loaded
        content.removeAllViews()
        if(loaded.optBoolean("restart_required")) add(ui.text("Пароль владельца изменён: перезапустите сервер, чтобы он применился.",13f,android.graphics.Color.rgb(232,120,112)))
        lateinit var owner: EditText
        add(ui.card().apply {
            addView(ui.text("Пароль владельца",15f,bold = true))
            owner = ui.field(this,"Пароль",loaded.optString("main_password"))
            addView(ui.textButton("Сгенерировать") { owner.setText(Mrtt.generatePassword()) })
        })
        var dns = loaded.optString("dns_provider","yandex")
        var restart = loaded.optInt("auto_restart_interval_hours").toString()
        lateinit var primary: EditText; lateinit var secondary: EditText
        add(ui.card().apply {
            addView(ui.text("DNS для клиентов",15f,bold = true))
            val custom = ui.column()
            ui.dropdown(this,"Провайдер",DNS_PROVIDERS,dns) { dns = it; custom.visibility = if(it=="custom") View.VISIBLE else View.GONE }
            primary = ui.field(custom,"Основной IPv4",loaded.optString("dns_primary"))
            secondary = ui.field(custom,"Запасной IPv4 (необязательно)",loaded.optString("dns_secondary"))
            custom.visibility = if(dns=="custom") View.VISIBLE else View.GONE
            addView(custom)
            ui.dropdown(this,"Автоперезапуск",listOf("0" to "Выключен","12" to "12 ч","24" to "24 ч","48" to "48 ч","72" to "72 ч"),restart) { restart = it }
        })
        var vkToken = loaded.optString("vk_token")
        var hashes: () -> String = { "" }
        add(ui.card().apply {
            addView(ui.text("VK-аккаунт сервера",15f,bold = true))
            val state = ui.column().also { addView(it) }
            fun showVk() {
                state.removeAllViews()
                ui.hint(state,if(vkToken.isBlank()) "Не привязан. Привяжите аккаунт, и конфиги с режимом «Аккаунт сервера» будут получать хеши автоматически."
                    else "Привязан. Конфиги с режимом «Аккаунт сервера» получают хеши с этого аккаунта автоматически.")
                state.addView(LinearLayout(context).apply {
                    // The client's one VK binding hands its token to the server, which keeps it; the app shows nothing of it.
                    addView(ui.textButton(if(vkToken.isBlank()) "Привязать VK" else "Перепривязать") {
                        request({ check(VkRelayAccount.linked()) { "Сначала привяжите аккаунт VK в настройках VK-релея" }; VkRelayAccount.tokenForCalls() }) { token ->
                            vkToken = token; showVk()
                            action("VK-аккаунт привязан к серверу") { admin.post("/api/settings",JSONObject().put("vk_token",token)) }
                        }
                    })
                    if(vkToken.isNotBlank()) addView(ui.textButton("Отвязать") { vkToken = ""; showVk() })
                })
            }
            showVk()
            ui.hint(this,"Общие VK-хеши, необязательно")
            hashes = hashesButton(this,loaded.optString("vk_hashes"))
            addView(ui.textButton("Применить ко всем конфигам") {
                action("VK применён ко всем конфигам") { admin.post("/api/links/batch-vk",JSONObject().put("vk_token",vkToken).put("vk_hashes",hashes()).put("apply_to_all_clients",true)) }
            })
        })
        lateinit var room: EditText
        var seats = loaded.optInt("jitsi_seats",6).toString()
        add(ui.card().apply {
            addView(ui.text("Jitsi",15f,bold = true))
            room = ui.field(this,"Ссылка на комнату Jitsi",loaded.optString("jitsi_room").takeIf { it.isNotBlank() }?.let { "https://$it" }.orEmpty())
            ui.dropdown(this,"Участников в комнате",listOf(2,4,6,8,12,16).map { it.toString() to it.toString() },seats) { seats = it }
            ui.hint(this,if(Mrtt.parseJitsiRoom(room.text.toString())==null) "Создайте конференцию на любом сервере Jitsi и вставьте ссылку: сервер зайдёт в неё сразу после сохранения, переустанавливать не нужно."
                else "Сервер сидит в ${loaded.optString("jitsi_room")}. Новые ссылки подписок несут эту комнату; у своего профиля её можно вставить в «Комнату Jitsi».")
        })
        val exits = (loaded.optJSONArray("exits") ?: JSONArray()).objects()
        var priority = if(loaded.optString("exit_priority")=="config") "config" else ""
        var defaultExit = loaded.optString("default_exit").takeIf { id -> exits.any { it.optString("id")==id } }.orEmpty()
        add(ui.card().apply {
            addView(ui.text("Выходной IP",15f,bold = true))
            ui.dropdown(this,"Приоритет выходного IP",listOf("" to "Настройка сервера","config" to "Настройка конфига"),priority) { priority = it }
            ui.dropdown(this,"Выход сервера",listOf("" to "Этот сервер")+exits.map { it.optString("id") to it.optString("name") },defaultExit) { defaultExit = it }
            exits.forEach { exit ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.text("${exit.optString("name")} · ${exit.optString("host")}",13f,MobilePalette.muted),LinearLayout.LayoutParams(0,-2,1f))
                    addView(ui.textButton("Убрать") { removeExit(exit,exits) })
                })
            }
            addView(ui.button("Добавить выход через другой VPS") { addExit(exits) },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(8) })
            ui.hint(this,"Трафик клиентов выходит через выбранный VPS по протоколу MRTT, UDP тоже. Если связь между серверами упадёт, такие клиенты теряют интернет, а не выходят через этот сервер.")
        })
        add(ui.button("Сохранить настройки",primary = true) {
            val roomValue = room.text.toString().trim()
            val parsedRoom = if(roomValue.isEmpty()) "" else Mrtt.parseJitsiRoom(roomValue)
            if(parsedRoom==null) { toast("Неверная ссылка на комнату Jitsi"); return@button }
            if(!Mrtt.validPassword(owner.text.toString())) { toast("Пароль владельца: без пробелов и «|», не длиннее 128 байт"); return@button }
            val json = JSONObject().put("default_exit",defaultExit).put("exit_priority",priority).put("main_password",owner.text.toString())
                .put("dns_provider",dns).put("auto_restart_interval_hours",restart.toInt()).put("vk_token",vkToken).put("vk_hashes",hashes())
                .put("jitsi_room",parsedRoom).put("jitsi_seats",seats.toInt())
            if(dns=="custom") json.put("dns_primary",primary.text.toString().trim()).put("dns_secondary",secondary.text.toString().trim())
            action("Настройки сохранены") { admin.post("/api/settings",json) }
        })
        add(ui.card().apply {
            addView(ui.text("Обслуживание",15f,bold = true))
            addView(ui.chips(listOf(
                "Экспорт" to { exportFile.launch("mrtt-config.json") },
                "Импорт" to { importFile.launch(arrayOf("application/json","text/*","*/*")) },
                "Очистить память" to { action("Память освобождена") { admin.post("/api/memory/trim") } },
                "Перезапустить" to { confirm("Перезапуск сервера","Перезапустить MRTT на сервере? Все клиенты переподключатся.","Перезапустить") { action("Перезапуск запрошен") { admin.post("/api/reboot") } } },
            )).apply { setPadding(0,ui.dp(8),0,0) })
        })
    }
    private fun addExit(exits: List<JSONObject>) = request({ DeployStore.all().filter { other -> other.kind=="mrtt" && other.id!=server.id && other["installed"]=="true" && exits.none { it.optString("host")==other["host"] } } }) { candidates ->
        if(candidates.isEmpty()) { toast("Других серверов нет: добавьте VPS в разделе деплоя MRTT."); return@request }
        var chosen = candidates.first().id
        ui.sheet("Выход через другой VPS","Добавить",{
            val target = candidates.first { it.id==chosen }
            toast("Подключаем серверы...")
            action("Выход добавлен") {
                val created = admin.addExit(target)
                admin.post("/api/settings",JSONObject().put("exits",JSONArray(exits).put(created)).put("default_exit",settings.optString("default_exit")))
            }
            true
        }) { body,_ ->
            ui.dropdown(body,"Сервер",candidates.map { it.id to it["name"].ifBlank { it["host"] } },chosen) { chosen = it }
            ui.hint(body,"Этот сервер подключится к выбранному как обычный конфиг, он появится там в списке конфигов. Удалять его там не нужно, для этого есть кнопка «Убрать» здесь.")
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
    private fun loadStats() = load(5,{ admin.stats() }) { s ->
        content.removeAllViews()
        val uptime = s.optLong("uptime")
        fun card(rows: List<Pair<String,String>>) = add(ui.card().apply {
            rows.forEach { (label,value) -> addView(LinearLayout(this@MrttServerActivity).apply {
                setPadding(0,ui.dp(3),0,ui.dp(3))
                addView(ui.text(label,13f,MobilePalette.muted),LinearLayout.LayoutParams(0,-2,1f))
                addView(ui.text(value.ifBlank { "—" },13f,bold = true))
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
        add(ui.textButton("Обновить") { reload(5,true); toast("Обновлено") })
    }
    private fun loadLogs() = load(6,{ admin.logs() }) { json ->
        content.removeAllViews()
        ui.switchRow(content,"Запись логов на сервере",json.optBoolean("active")) { on -> action(null) { admin.post("/api/logs/toggle",JSONObject().put("active",on)) } }
        val array = json.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until array.length()).map { array.optString(it) }
        add(ui.card().apply {
            addView(ui.text(if(lines.isEmpty()) "Логи пусты" else lines.takeLast(400).joinToString("\n"),12f,mono = true).apply { setTextIsSelectable(true) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(ui.iconButton(R.drawable.ic_mobile_share,"Поделиться логом сервера") {
                    if(lines.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,lines.joinToString("\n")),"MRTT server log"))
                },LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)))
                addView(ui.iconButton(R.drawable.ic_mobile_delete,"Очистить логи сервера") { action("Логи очищены") { admin.post("/api/logs/clear") } },LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)))
            })
        }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(8) })
        // The newest lines are at the bottom, and the screen stays on them.
        if(section==6) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    companion object {
        val DNS_PROVIDERS = listOf("yandex" to "Yandex DNS","cloudflare" to "Cloudflare","google" to "Google","adguard" to "AdGuard","xbox" to "Xbox DNS","comms" to "Comss DNS",
            "geohide" to "GeoHide DNS","quad9" to "Quad9","opendns" to "OpenDNS","rostelecom" to "Rostelecom DNS","nextdns" to "NextDNS","bizone" to "BI.ZONE DNS","nsdi" to "НСДИ DNS","custom" to "Ручной DNS")
    }
}
