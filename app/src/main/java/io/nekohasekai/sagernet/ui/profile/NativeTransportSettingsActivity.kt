package app.qthrone.mobile.ui.profile

import app.qthrone.mobile.outbound.types.VkCallHash
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.*
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.bg.carrier.NativeCarrier
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import app.qthrone.mobile.ui.AnchoredTextPreference
import app.qthrone.mobile.ui.HashTextPreference
import app.qthrone.mobile.bg.carrier.CarrierHashCheck
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import app.qthrone.mobile.R

/** Typed controls edit the exported document, preserving unrecognized members. */
class NativeTransportSettingsActivity : ProfileSettingsActivity<Outbound>() {
    override fun createEntity(): Outbound = OutboundFactory.newByType(intent.getStringExtra("qmobileProfileType") ?: proxyEntity?.type ?: "qwdtt")
    private fun cache(path: String) = "native.$path"
    private var document = JsonObject()
    private val fields = linkedMapOf<String, Any>()
    private var hashCheck: CarrierHashCheck? = null
    private var hashCheckJob: Job? = null
    private var captchaController: app.qthrone.mobile.ui.MobileCaptchaController? = null
    private var resetHashResults: (() -> Unit)? = null

    private fun stopHashCheck() {
        val session=hashCheck
        hashCheck=null
        session?.close()
        hashCheckJob?.cancel();hashCheckJob=null
    }

    override fun onStop() {
        stopHashCheck()
        resetHashResults?.invoke()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captchaController=app.qthrone.mobile.ui.MobileCaptchaController(this)
    }

    override fun onDestroy() {
        stopHashCheck()
        resetHashResults=null
        captchaController?.close()
        super.onDestroy()
    }

    override fun Outbound.init() {
        if(this is app.qthrone.mobile.outbound.types.Qwdtt && DataStore.editingId == 0L) {
            app.qthrone.mobile.bg.carrier.NativeHashPolicy.applyDefaultSource(this,
                DataStore.configurationStore.getString(app.qthrone.mobile.bg.carrier.VkRelayAccount.DEFAULT_SOURCE,"manual"))
        }
        document = exportToJson().copy()
        fields.clear()
        if(type == "olcrtc") {
            for((key,value) in mapOf("provider" to "jitsi","transport" to "datachannel","room" to "","key" to "","token" to "","channel" to "","dns" to "8.8.8.8:53"))
                if(!document.contains(key)) document[key]=value
            val options=document.obj("options")
            for((key,value) in optionDefaults)
                if(!options.contains(key)) options[key]=value
            document["options"]=options
        }
        // A new profile is a Session; an imported one keeps exactly what its link says.
        if(type == "openflux" && document.array("transports").isEmpty()) {
            for((key,value) in mapOf("secret" to "","negotiate" to true))
                if(!document.contains(key)) document[key]=value
        }
        if(type == "openflux" && !document.contains("context")) document["context"]=""
        if(type == "openfluxkey")
            for((key,value) in mapOf("transport" to "boards","doc_url" to "","key_token" to "","e2e_encryption" to false))
                if(!document.contains(key)) document[key]=value
        if(type in dnsTypes) {
            val defaults=linkedMapOf<String,Any>("domain" to "","resolvers" to JsonArray())
            when(type) {
                "dnstt" -> defaults["pubkey"]=""
                "vaydns" -> defaults.putAll(mapOf("pubkey" to "","record_type" to "txt","dnstt_compat" to false,"clientid_size" to 2,"idle_timeout" to "","keepalive" to ""))
                "slipstream" -> defaults.putAll(mapOf("cert" to "","authoritative" to false,"congestion_control" to "bbr","keep_alive_interval" to 400))
                "masterdns","cottendns" -> defaults.putAll(mapOf("mkey" to "","encryption" to 1))
                "soar" -> defaults["pubkey"]=""
            }
            for((key,value) in defaults) if(!document.contains(key)) document[key]=value
            if(type=="soar") {
                // The advanced Soar settings of an imported profile stay as they came.
                val soar=document.obj("soar")
                for((key,value) in mapOf("discovery" to true,"country" to "IR")) if(!soar.contains(key)) soar[key]=value
                document["soar"]=soar
            }
            if(!app.qthrone.mobile.outbound.types.DnsTunnel.keyed(type)) {
                val backend=document.obj("backend")
                for(key in listOf("user","password","pk","method")) if(!backend.contains(key)) backend[key]=""
                document["backend"]=backend
            }
        }
        if(type == "sstp") {
            for((key,value) in mapOf("server" to "","server_port" to 443,"username" to "","password" to "","sni" to "","allow_insecure" to false,"use_server_dns" to false))
                if(!document.contains(key)) document[key]=value
        }
        if(type == "s3bypass") {
            val storage=document.obj("storage")
            for(key in listOf("endpoint","region","bucket","prefix","accessKey","secretKey")) if(!storage.contains(key)) storage[key]=""
            if(!storage.contains("sessionsDir")) storage["sessionsDir"]="sessions"
            document["storage"]=storage
            // The original app's tuning; both sides keep their own.
            val tuning=document.obj("tuning")
            for((key,value) in mapOf("pollIntervalMs" to 100,"writeIntervalMs" to 20,"idleTimeoutSec" to 300,"maxFileSizeBytes" to 2097152))
                if(!tuning.contains(key)) tuning[key]=value
            document["tuning"]=tuning
        }
        fun write(obj: JsonObject, prefix: String = "") {
            for ((key, value) in obj) {
                if (key == "type") continue
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (value is JsonObject) write(value, path)
                else {
                    fields[path] = value
                    DataStore.profileCacheStore.putString(cache(path), when(value) {
                        is JsonArray -> if(key in listOf("hashes","resolvers")) value.strings().joinToString("\n") else value.toCompact()
                        else -> value.toString()
                    })
                }
            }
        }
        write(document)
        val order = when(type) {
            "qwdtt" -> listOf("name","server","server_port","mode","password","hash_source") + (1..4).map { "hash_$it" } + listOf("raw_port","workers","turn_tcp","vk_anon_path","captcha_mode","obfs","go_dns","device_id")
            "csqtt" -> listOf("name","server","server_port","password","hash_source","vk_access_token") + (1..6).map { "hash_$it" } + listOf("workers","turn_transport","srtp_mode","vk_anon_path","captcha_mode","obfs","fingerprint","client_ids","custom_turn_ips","device_id")
            "wdttplus" -> listOf("name","server","server_port","wg_port","local_port","password","hash_source") + (1..4).map { "hash_$it" } + listOf("workers","captcha_mode","tcp_only","turn_sni","rt_masque","rt_masque_server_bootstrap","device_id")
            "openflux" -> listOf("name","secret") + openFluxKinds.map { "transport_$it" } + listOf("context")
            "dnstt" -> listOf("name","domain","pubkey","resolvers") + backendFields
            "vaydns" -> listOf("name","domain","pubkey","resolvers","record_type","dnstt_compat","clientid_size","idle_timeout","keepalive") + backendFields
            "slipstream" -> listOf("name","domain","cert","resolvers","authoritative","congestion_control","keep_alive_interval") + backendFields
            "masterdns","cottendns" -> listOf("name","domain","mkey","encryption","resolvers")
            "soar" -> listOf("name","domain","pubkey","resolvers","soar.discovery","soar.country")
            "sstp" -> listOf("name","server","server_port","username","password","sni","allow_insecure","use_server_dns")
            "openfluxkey" -> listOf("name","transport","doc_url","key_token","e2e_encryption")
            "s3bypass" -> listOf("name") + listOf("endpoint","region","bucket","prefix","sessionsDir","accessKey","secretKey").map { "storage.$it" } +
                listOf("pollIntervalMs","writeIntervalMs","idleTimeoutSec","maxFileSizeBytes").map { "tuning.$it" }
            else -> listOf("name","provider","transport","room","key","token","channel","dns","device_id") + optionDefaults.keys.map { "options.$it" }
        }
        if(type in listOf("qwdtt","csqtt","wdttplus")) {
            val hashes=document.array("hashes").strings()
            for(i in 1..if(type=="csqtt") 6 else 4) {
                fields["hash_$i"]=hashes.getOrElse(i-1) { "" }
                DataStore.profileCacheStore.putString(cache("hash_$i"),hashes.getOrElse(i-1) { "" })
            }
        }
        if(type=="openflux") for(kind in openFluxKinds) {
            val existing=document.array("transports").filterIsInstance<JsonObject>().firstOrNull { it.string("type")==kind }
            // Rarer carriers get a field only when the profile already uses them.
            if(existing==null && kind !in listOf("yandex","mailru","cupsonline")) continue
            val value=existing?.string(if(kind=="direct") "dial" else "url").orEmpty()
            fields["transport_$kind"]=value
            DataStore.profileCacheStore.putString(cache("transport_$kind"),value)
        }
        // write() skips every "type" member; the backend's is a field of its own.
        if(type in dnsTypes && !app.qthrone.mobile.outbound.types.DnsTunnel.keyed(type)) {
            val kind=document.obj("backend").string("type").ifEmpty { "socks" }
            fields["backend_type"]=kind
            DataStore.profileCacheStore.putString(cache("backend_type"),kind)
        }
        if(!fields.containsKey("name")) {
            fields["name"]=name
            DataStore.profileCacheStore.putString(cache("name"),name)
        }
        val ordered=order.mapNotNull { path -> fields[path]?.let { path to it } }
        fields.clear()
        fields.putAll(ordered)

    }

    override fun Outbound.serialize() {
        val result = document.copy()
        for ((path, original) in fields) {
            if(path.startsWith("transport_") || path=="backend_type" || (path.startsWith("hash_") && path != "hash_source")) continue
            val parts = path.split('.')
            var obj = result
            for (part in parts.dropLast(1)) obj = obj.obj(part)
            val key = parts.last()
            val text = DataStore.profileCacheStore.getString(cache(path)) ?: ""
            if(path.startsWith("options.") && text.isBlank()) { obj.remove(key); continue }
            obj[key] = when(original) {
                is Boolean -> text.toBooleanStrict()
                is Number -> text.toLong()
                is JsonArray -> if(key in listOf("hashes","resolvers")) JsonArray.of(*text.split(Regex("[,\\s]+" )).filter { it.isNotBlank() }.toTypedArray())
                    else JsonInput.parseValue(text) as? JsonArray ?: error("$key: JSON array required")
                else -> text
            }
        }
        if(type in listOf("qwdtt","csqtt","wdttplus")) {
            result["hashes"]=JsonArray.of(*fields.keys.filter { it.startsWith("hash_") && it != "hash_source" }.map { DataStore.profileCacheStore.getString(cache(it))?.trim().orEmpty() }.filter { it.isNotEmpty() }.toTypedArray())
            // Hand-edited slots win over the subscription's on the next refresh.
            if(result.array("hashes").strings()!=document.array("hashes").strings()) result["manual_hashes_override"]=true
        }
        if(type=="olcrtc") {
            val transport=DataStore.profileCacheStore.getString(cache("transport")).orEmpty()
            val options=result.obj("options")
            for(key in optionDefaults.keys) if(!optionVisible(key,transport)) options.remove(key)
        }
        if(type=="openflux") {
            val transports=JsonArray()
            fun edited(kind:String)=DataStore.profileCacheStore.getString(cache("transport_$kind"))?.trim().orEmpty()
            val handled=mutableSetOf<String>()
            // The first carrier of a type is the one its field edits; any further ones pass through untouched.
            for(item in document.array("transports").filterIsInstance<JsonObject>()) {
                val kind=item.string("type")
                if(fields.containsKey("transport_$kind") && handled.add(kind)) {
                    val value=edited(kind)
                    if(value.isNotEmpty()) transports.add(item.copy().also { it[if(kind=="direct") "dial" else "url"]=value })
                } else transports.add(item.copy())
            }
            for(kind in openFluxKinds) {
                if(!fields.containsKey("transport_$kind") || kind in handled || edited(kind).isEmpty()) continue
                transports.add(jsonObjectOf("type" to kind,(if(kind=="direct") "dial" else "url") to edited(kind)))
            }
            result["transports"]=transports
            if(result.string("context").isBlank()) result.remove("context")
            if(result.string("secret").isEmpty()) result.remove("secret")
            // Several carriers only work as a Session.
            if(transports.size>1) result["negotiate"]=true
        }
        if(fields.containsKey("backend_type")) {
            val backend=result.obj("backend")
            backend["type"]=DataStore.profileCacheStore.getString(cache("backend_type")) ?: "socks"
            // Keep only what the chosen service uses, so a link shares no stale password or key.
            val used=when(backend.string("type")) { "ssh" -> listOf("user","password","pk"); "shadowsocks" -> listOf("password","method"); else -> listOf("user","password") }
            for(key in listOf("user","password","pk","method")) if(key !in used || backend.string(key).isEmpty()) backend.remove(key)
            result["backend"]=backend
        }
        if(type in dnsTypes) for(key in listOf("cert","idle_timeout","keepalive")) if(result.string(key).isBlank()) result.remove(key)
        val candidate = OutboundFactory.newByType(type)
        // A profile may be saved without hashes and get them later; the launch asks for them.
        val checked = if(type in listOf("qwdtt","csqtt","wdttplus") && result.array("hashes").isEmpty()) result.copy().also { it["hashes"]=JsonArray.of("0".repeat(16)) } else result
        require(candidate.parseFromJson(checked)) { "Проверьте параметры профиля" }
        (candidate as NativeCarrier).privateConfig(1080,1081,CarrierBridgeCredentials("validation","validation"))
        require(parseFromJson(result))
    }

    override suspend fun saveAndExit() {
        try { super.saveAndExit() }
        catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) { Toast.makeText(this@NativeTransportSettingsActivity, e.message ?: "Проверьте параметры профиля", Toast.LENGTH_LONG).show() }
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val labels = mapOf("tag" to "Название", "name" to "Название", "server" to "Адрес сервера",
            "server_port" to "Порт сервера", "password" to "Пароль", "hashes" to "Хэши VK",
            "hash_source" to "Источник хешей", "workers" to "Воркеры", "turn_tcp" to "Транспорт TURN", "mode" to "Режим",
            "turn_transport" to "Транспорт TURN", "srtp_mode" to "Режим SRTP", "fingerprint" to "TLS-профиль VK",
            "client_ids" to "Client ID VK", "custom_turn_ips" to "Серверы TURN", "vk_access_token" to "VK access token",
            "raw_port" to "Порт RAW", "wg_port" to "Порт WG", "local_port" to "Локальный порт", "obfs" to "Маскировка", "go_dns" to "DNS VK",
            "device_id" to "Идентификатор устройства", "vk_anon_path" to "Авторизация VK", "captcha_mode" to "Режим капчи",
            "tcp_only" to "TCP/TLS", "turn_sni" to "Внешний SNI", "rt_masque" to "Резерв MASQUE",
            "secret" to "Секрет", "context" to "Контекст", "codec" to "Кодек",
            "negotiate" to "Согласование транспорта", "transports" to "Транспорты (JSON)",
            "provider" to "Сервис", "transport" to "Транспорт", "room" to "Комната / ссылка",
            "key" to "Ключ (64 HEX)", "token" to "Токен сервиса", "channel" to "Канал комнаты", "dns" to "DNS",
            "vp8-fps" to "VP8: кадров/с", "vp8-batch" to "VP8: кадров в пакете", "fps" to "SEI: кадров/с", "batch" to "SEI: кадров в пакете",
            "frag" to "SEI: размер фрагмента", "ack-ms" to "SEI: ожидание ACK, мс", "video-w" to "Видео: ширина", "video-h" to "Видео: высота",
            "video-fps" to "Видео: кадров/с", "video-qr-size" to "QR: размер фрагмента (0 — авто)", "video-tile-module" to "Tile: размер модуля",
            "video-tile-rs" to "Tile: восстановление, %", "video-codec" to "Кодек видео", "video-qr-recovery" to "Восстановление QR",
            "domain" to "Домен туннеля", "pubkey" to "Публичный ключ сервера", "resolvers" to "DNS-резолверы",
            "record_type" to "Тип DNS-записи", "dnstt_compat" to "Совместимость с DNSTT", "clientid_size" to "Размер ClientID, байт",
            "idle_timeout" to "Тайм-аут простоя", "keepalive" to "Интервал keepalive", "cert" to "Сертификат сервера (PEM)",
            "authoritative" to "Резолвер — авторитетный сервер", "congestion_control" to "Управление перегрузкой",
            "keep_alive_interval" to "Keepalive QUIC, мс", "mkey" to "Ключ шифрования", "encryption" to "Шифрование",
            "discovery" to "Искать рабочие резолверы страны", "country" to "Страна сети",
            "use_server_dns" to getString(R.string.sstp_use_server_dns),
            "backend_type" to "Сервис на сервере", "user" to "Логин", "username" to "Имя пользователя", "sni" to "SNI (Server Name)", "allow_insecure" to "Разрешить недоверенный сертификат", "pk" to "Приватный ключ SSH", "method" to "Метод шифрования",
            "endpoint" to "Адрес хранилища (endpoint)", "region" to "Регион", "bucket" to "Корзина (bucket)", "prefix" to "Префикс",
            "sessionsDir" to "Каталог сессий", "accessKey" to "Access Key", "secretKey" to "Secret Key",
            "pollIntervalMs" to "Опрос хранилища, мс", "writeIntervalMs" to "Запись в хранилище, мс",
            "idleTimeoutSec" to "Тайм-аут простоя, с", "maxFileSizeBytes" to "Наибольший объект, байт",
            "doc_url" to "Ссылка на документ или доску", "key_token" to "Ключ доступа", "e2e_encryption" to "Сквозное шифрование ключом")
        val choices = mutableMapOf("hash_source" to listOf("manual","auto"),"mode" to listOf("raw","wg"), "obfs" to listOf("audio","video"),
            "vk_anon_path" to listOf("vkcalls","legacy"), "captcha_mode" to listOf("auto","wv","rjs"),
            "provider" to listOf("jitsi","telemost","wbstream"),
            "transport" to listOf("datachannel","vp8channel","seichannel","videochannel"),
            "turn_tcp" to listOf("false","true"), "video-codec" to listOf("qrcode","tile"),
            "video-qr-recovery" to listOf("low","medium","high","highest"),
            "backend_type" to listOf("socks","ssh","shadowsocks"), "record_type" to app.qthrone.mobile.outbound.types.DnsTunnel.RECORD_TYPES,
            "congestion_control" to listOf("bbr","dcubic"), "encryption" to listOf("0","1","2","3","4","5"),
            "method" to listOf("chacha20-ietf-poly1305","aes-256-gcm","aes-128-gcm"))
        val captions=mutableMapOf("backend_type" to listOf("SOCKS5","SSH","Shadowsocks"),"record_type" to app.qthrone.mobile.outbound.types.DnsTunnel.RECORD_TYPES.map { it.uppercase() },
            "congestion_control" to listOf("BBR","DCUBIC"),"encryption" to listOf("Нет","XOR","ChaCha20","AES-128-GCM","AES-192-GCM","AES-256-GCM"),"hash_source" to listOf("Вручную","Авто"),"mode" to listOf("RAW","WG"),"turn_tcp" to listOf("UDP","TCP"),
            "vk_anon_path" to listOf("Звонок VK","Капча"),"captcha_mode" to listOf("Автоматически, с переходом в браузер","Окно браузера","Автоматически"),
            "provider" to listOf("Jitsi","Телемост","WBStream"),"obfs" to listOf("Аудио","Видео"))
        if(editingOutbound.type=="openfluxkey") {
            choices["transport"]=app.qthrone.mobile.outbound.types.OpenFluxKey.TRANSPORTS
            captions["transport"]=listOf("Яндекс Документы","Яндекс Волга","MAX","Яндекс Доски","Mail.ru","МТС Линк","Телемост","Cups.online","Авито","Яндекс Документы, несколько")
        }
        if(editingOutbound.type=="csqtt") {
            choices["hash_source"]=listOf("manual","auto","auto_js")
            captions["hash_source"]=listOf("Вручную","Auto API","Auto JS")
            choices["vk_anon_path"]=listOf("vkcalls","legacy","auto_js")
            captions["vk_anon_path"]=listOf("Звонок VK","Капча","Auto JS")
            choices["obfs"]=listOf("unified","audio","video")
            captions["obfs"]=listOf("Универсальная","Аудио","Видео")
            choices["turn_transport"]=listOf("udp","tcp_tls")
            captions["turn_transport"]=listOf("UDP","TCP/TLS")
            choices["srtp_mode"]=listOf("auto","wrap","dtls")
            captions["srtp_mode"]=listOf("Автоматически","WRAP-SRTP","DTLS-SRTP")
            choices["fingerprint"]=listOf("firefox","chrome","safari","edge","opera")
        }
        val editors=linkedMapOf<String,Preference>()
        val checkButton=Preference(requireContext()).apply {
            title="Проверить хеши";isPersistent=false;setIcon(R.drawable.ic_mobile_hash)
        }
        fun refreshEditors() {
            val source=DataStore.profileCacheStore.getString(cache("hash_source"))
            val automatic = source in listOf("auto","auto_js")
            editors["vk_access_token"]?.isVisible=source=="auto_js"
            (editors["vk_access_token"] as? EditTextPreference)?.dialogMessage="Необязательно: если поле пустое, токен берётся из общей привязки VK в настройках VK-релея."
            editors["rt_masque_server_bootstrap"]?.isEnabled=DataStore.profileCacheStore.getString(cache("rt_masque")) == "true"
            editors.filterKeys { it.startsWith("hash_") && it != "hash_source" }.values.forEach { it.isVisible = !automatic }
            checkButton.isVisible=!automatic
            checkButton.isEnabled=hashCheck!=null || fields.keys.any { it.startsWith("hash_") && it!="hash_source" && !DataStore.profileCacheStore.getString(cache(it)).isNullOrBlank() }
            editors["raw_port"]?.isEnabled=DataStore.profileCacheStore.getString(cache("mode"))!="wg"
            if(editingOutbound.type in listOf("qwdtt","csqtt","wdttplus")) {
                val count=editors.keys.filter { it.startsWith("hash_") && it != "hash_source" }.map { DataStore.profileCacheStore.getString(cache(it))?.trim().orEmpty() }.filter { it.isNotEmpty() }.distinct().size.coerceAtLeast(1)
                val csqtt=editingOutbound.type=="csqtt"
                val max=if(csqtt) 100 else if(editingOutbound is app.qthrone.mobile.outbound.types.WdttPlus) app.qthrone.mobile.bg.carrier.NativeHashPolicy.workerLimit(editingOutbound as app.qthrone.mobile.outbound.types.WdttPlus)
                    else if(automatic) 108 else (count*27).coerceAtMost(108)
                (editors["workers"] as? ListPreference)?.apply {
                    entries=(if(csqtt) listOf(5)+(10..100 step 10) else (9..max step 9).toList()).map(Int::toString).toTypedArray();entryValues=entries
                    val raw=DataStore.profileCacheStore.getString(cache("workers"))?.toIntOrNull() ?: 9
                    val selected=if(csqtt) app.qthrone.mobile.outbound.types.Csqtt.normalizeWorkers(raw) else raw.coerceIn(9,max)/9*9
                    value=selected.toString()
                }
            }
            val backend=DataStore.profileCacheStore.getString(cache("backend_type")) ?: "socks"
            val backendUses=when(backend) { "ssh" -> listOf("user","password","pk"); "shadowsocks" -> listOf("password","method"); else -> listOf("user","password") }
            for(key in listOf("user","password","pk","method")) editors["backend.$key"]?.isVisible=key in backendUses
            val transport=DataStore.profileCacheStore.getString(cache("transport")).orEmpty()
            for((path,preference) in editors) if(path.startsWith("options.")) {
                val key=path.substringAfter('.')
                if(key in listOf("video-codec","video-qr-recovery")) { preference.isVisible=true;preference.isEnabled=transport=="videochannel" }
                else preference.isVisible=optionVisible(key,transport)
            }
        }
        for ((path, value) in fields) {
            val key = path.substringAfterLast('.')
            val preference: Preference = when {
                value is Boolean && key!="turn_tcp" -> SwitchPreference(requireContext()).apply {
                    isPersistent = false
                    if(key == "use_server_dns") summary = getString(R.string.sstp_use_server_dns_summary)
                    isChecked = DataStore.profileCacheStore.getString(cache(path)).toBoolean()
                    setOnPreferenceChangeListener { _, next -> DataStore.profileCacheStore.putString(cache(path), next.toString()); true }
                }
                choices.containsKey(key) || key=="workers" -> SimpleMenuPreference(requireContext()).apply {
                    if(key == "hash_source") optionEnabled = { index -> index != 1 || (editingOutbound.type in listOf("qwdtt","csqtt","wdttplus") && app.qthrone.mobile.bg.carrier.VkRelayAccount.linked() && !app.qthrone.mobile.bg.carrier.VkRelayAccount.needsRelink()) }
                    entryValues = (choices[key] ?: listOf("9")).toTypedArray()
                    entries = (captions[key] ?: choices[key] ?: listOf("9")).toTypedArray()
                    this.value=DataStore.profileCacheStore.getString(cache(path))
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
                else -> (if(path in listOf("go_dns","dns")) AnchoredTextPreference(requireContext())
                    else if(path.startsWith("hash_") && path!="hash_source") HashTextPreference(requireContext())
                    else EditTextPreference(requireContext())).apply {
                    summaryProvider = if (key in listOf("password", "secret") || path.endsWith(".password")) {
                        androidx.preference.Preference.SummaryProvider<EditTextPreference> { pref ->
                            val text = pref.text
                            if (text.isNullOrEmpty()) "Не указано" else "••••••••"
                        }
                    } else {
                        EditTextPreference.SimpleSummaryProvider.getInstance()
                    }
                    positiveButtonText=getString(android.R.string.ok)
                    negativeButtonText=getString(android.R.string.cancel)
                    setOnBindEditTextListener {
                        it.inputType = when {
                            value is Number -> InputType.TYPE_CLASS_NUMBER
                            key in listOf("password","secret") -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            key in listOf("hashes","cert","pk") || value is JsonArray -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                    }
                    setOnPreferenceChangeListener { _, next ->
                        val text = next.toString()
                        when {
                            value is Number -> text.toLongOrNull() != null
                            value is JsonArray && key != "hashes" -> JsonInput.parseValue(text) is JsonArray
                            else -> true
                        }
                    }
                }
            }
            preference.key = cache(path)
            preference.title = when {
                path=="transport_direct" -> "Сервер (адрес:порт)"
                path.startsWith("hash_") && path != "hash_source" -> "Хэш VK " + path.removePrefix("hash_")
                path=="transport_yandex" -> "Документ Яндекс"
                path=="transport_mailru" -> "Документ Mail.ru"
                path=="transport_cupsonline" -> "Документ Cups.online"
                path=="transport_vyandex" -> "Документ Яндекс (Volga)"
                path=="transport_boards" -> "Доска Яндекс"
                path=="transport_telemost" -> "Конференция Телемост"
                path=="transport_avito" -> "Комната Авито"
                else -> labels[key] ?: path.removePrefix("options.")
            }
            if(preference is EditTextPreference) preference.dialogTitle = preference.title
            if(editingOutbound.type=="wdttplus") when(key) {
                "tcp_only" -> preference.summary="Отключить внешний UDP и использовать TCP/TLS"
                "rt_masque" -> preference.summary="Резерв через Cloudflare. При первом включении потребуется регистрация Cloudflare"
                "rt_masque_server_bootstrap" -> { preference.title="Регистрация MASQUE по SSH"; preference.summary="Для первой регистрации использовать сохранённый SSH-доступ к этому серверу" }
            }
            preference.setOnPreferenceChangeListener { _, next ->
                // A full VK call link pasted into a hash field keeps only its hash, as in the original apps.
                val text=if(path.startsWith("hash_") && path!="hash_source") VkCallHash.strip(next.toString()) else next.toString()
                if(key == "hash_source" && text == "auto" && (editingOutbound.type !in listOf("qwdtt","csqtt","wdttplus") || !app.qthrone.mobile.bg.carrier.VkRelayAccount.linked() || app.qthrone.mobile.bg.carrier.VkRelayAccount.needsRelink())) return@setOnPreferenceChangeListener false
                val range=optionLimits[path.removePrefix("options.")]
                if(value is Number && text.toLongOrNull()==null) return@setOnPreferenceChangeListener false
                if(range!=null && text.toIntOrNull()?.let { it in range }!=true) return@setOnPreferenceChangeListener false
                if(key in listOf("server_port","raw_port") && text.toIntOrNull()?.let { it in 1..65535 }!=true) return@setOnPreferenceChangeListener false
                DataStore.profileCacheStore.putString(cache(path),text)
                if(editingOutbound.type=="csqtt") {
                    val linked=when {
                        path=="hash_source" -> "vk_anon_path" to if(text=="auto_js") "auto_js" else "vkcalls"
                        path=="vk_anon_path" && text=="auto_js" -> "hash_source" to "auto_js"
                        path=="vk_anon_path" && DataStore.profileCacheStore.getString(cache("hash_source"))=="auto_js" -> "hash_source" to "manual"
                        else -> null
                    }
                    linked?.let { (key,value) -> DataStore.profileCacheStore.putString(cache(key),value);(editors[key] as? ListPreference)?.value=value }
                }
                if(path.startsWith("hash_") && path!="hash_source") (editors[path] as? HashTextPreference)?.setCheckStatus(null)
                if(path=="provider") {
                    val current=DataStore.profileCacheStore.getString(cache("transport"))
                    if((text=="telemost" && current in listOf("datachannel","seichannel")) || (text=="wbstream" && current=="datachannel")) {
                        DataStore.profileCacheStore.putString(cache("transport"),"vp8channel")
                        (editors["transport"] as? ListPreference)?.value="vp8channel"
                    }
                }
                refreshEditors()
                if(text!=next.toString()) { (preference as EditTextPreference).text=text;false } else true
            }
            if(path in listOf("go_dns","dns")) (preference as AnchoredTextPreference).onSelect = { anchor ->
                val values=(if(path=="go_dns") listOf("yandex","cloudflare","google","doh-yandex","doh-cloudflare","doh-google")
                    else listOf("8.8.8.8:53","1.1.1.1:53","77.88.8.8:53")).toMutableList()
                val current=DataStore.profileCacheStore.getString(cache(path)).orEmpty()
                if(current.isNotEmpty() && current !in values) values.add(current)
                values.add("Ввести вручную")
                app.qthrone.mobile.ui.MobileSelector.showAt(anchor, values,
                    values.indexOf(DataStore.profileCacheStore.getString(cache(path)))) { index ->
                    if(index==values.lastIndex) onDisplayPreferenceDialog(preference)
                    else if(preference.callChangeListener(values[index])) preference.text=values[index]
                }
            }
            editors[path]=preference
            preferenceScreen.addPreference(preference)
            if(path=="hash_"+(if(editingOutbound.type=="csqtt") 6 else 4)) {
                preferenceScreen.addPreference(checkButton)
            }
        }
        val results=linkedMapOf<Int,String>()
        fun showResults() {
            for((slot,status) in results) (editors["hash_$slot"] as? HashTextPreference)?.setCheckStatus(status)
        }
        fun unlockHashes() {
            editors.filterKeys { it.startsWith("hash_") }.values.forEach { it.isEnabled=true }
            checkButton.title="Проверить хеши"
            refreshEditors()
        }
        resetHashResults={
            results.clear()
            editors.values.filterIsInstance<HashTextPreference>().forEach { it.setCheckStatus(null) }
            unlockHashes()
        }
        checkButton.setOnPreferenceClickListener {
            val active=hashCheck
            if(active!=null) {
                stopHashCheck()
                results.replaceAll { _, status -> if(status in listOf("В очереди","Проверяется…")) "Остановлено" else status }
                showResults();unlockHashes()
            } else {
                val hashes=fields.keys.filter { it.startsWith("hash_") && it!="hash_source" }.mapNotNull { path ->
                    DataStore.profileCacheStore.getString(cache(path))?.let(VkCallHash::strip)?.takeIf { it.isNotEmpty() }?.let { path.removePrefix("hash_").toInt() to it }
                }
                if(hashes.any { !it.second.matches(Regex("[A-Za-z0-9_-]{16,512}")) }) {
                    Toast.makeText(this@NativeTransportSettingsActivity,"Некорректный хеш VK",Toast.LENGTH_SHORT).show()
                } else if(hashes.isNotEmpty()) {
                    val session=CarrierHashCheck();hashCheck=session
                    checkButton.title="Стоп"
                    editors.filterKeys { it.startsWith("hash_") }.values.forEach { it.isEnabled=false }
                    results.clear();hashes.forEach { results[it.first]="В очереди" };showResults()
                    val captchaMode=DataStore.profileCacheStore.getString(cache("captcha_mode")) ?: "auto"
                    val anonPath=DataStore.profileCacheStore.getString(cache("vk_anon_path")) ?: "vkcalls"
                    val dns=DataStore.profileCacheStore.getString(cache("go_dns"))?.takeIf { it.isNotBlank() } ?: "yandex"
                    val deviceId=DataStore.profileCacheStore.getString(cache("device_id"))
                        ?.takeIf { it.isNotBlank() } ?: (editingOutbound as? app.qthrone.mobile.outbound.types.Qwdtt)?.deviceId.orEmpty()
                    hashCheckJob=lifecycleScope.launch {
                        try {
                            val update: (Int,String) -> Unit = { slot,status -> runOnUiThread { if(hashCheck===session) { results[slot]=status;showResults() } } }
                            withContext(Dispatchers.IO) {
                                // Each client checks its hashes its own way, as its original app does.
                                if(editingOutbound.type=="csqtt") session.runCsqtt(this@NativeTransportSettingsActivity,hashes,update)
                                else if(editingOutbound.type=="wdttplus") session.runWdttPlus(this@NativeTransportSettingsActivity,hashes,captchaMode,update)
                                else session.run(this@NativeTransportSettingsActivity,hashes,captchaMode,anonPath,dns,deviceId,update)
                            }
                        } catch(error: kotlinx.coroutines.CancellationException) { throw error }
                        catch(_: Exception) { if(hashCheck===session) { results.replaceAll { _,status -> if(status in listOf("В очереди","Проверяется…")) "Ошибка проверки" else status };showResults() } }
                        finally { if(hashCheck===session) { session.close();hashCheck=null;hashCheckJob=null;unlockHashes() } }
                    }
                }
            }
            true
        }
        refreshEditors()
        // The original qWDTT list shows the password's device slots and frees this phone's one.
        val qwdtt=editingOutbound as? app.qthrone.mobile.outbound.types.Qwdtt
        if(qwdtt!=null && qwdtt.type=="qwdtt" && DataStore.editingId!=0L) preferenceScreen.addPreference(Preference(requireContext()).apply {
            title="Устройства на сервере";summary="Сколько устройств привязано к паролю";isPersistent=false
            setOnPreferenceClickListener {
                summary="Проверка…"
                lifecycleScope.launch {
                    val status=withContext(Dispatchers.IO) { app.qthrone.mobile.deploy.QwdttProfileApi.status(qwdtt) }
                    if(status==null) { summary="Сервер не сообщает об устройствах для этого пароля";return@launch }
                    summary="Привязано ${status.boundDevices} из ${status.maxDevices}, активно ${status.activeDevices}"+
                        if(status.currentBound) ". Это устройство привязано" else ". Это устройство не привязано"
                    if(!status.currentBound) return@launch
                    app.qthrone.mobile.ui.MobileAlertDialogBuilder(requireContext()).setTitle("Отвязать устройство")
                        .setMessage("Отвязать это устройство от пароля? Место на сервере освободится, а следующее подключение привяжет устройство снова, если место будет свободно.")
                        .setPositiveButton("Отвязать") { _,_ ->
                            lifecycleScope.launch {
                                val ok=withContext(Dispatchers.IO) { app.qthrone.mobile.deploy.QwdttProfileApi.unbind(qwdtt) }
                                Toast.makeText(requireContext(),if(ok) "Устройство отвязано" else "Не удалось отвязать устройство",Toast.LENGTH_SHORT).show()
                                if(ok) summary="Это устройство отвязано"
                            }
                        }.setNegativeButton(android.R.string.cancel,null).show()
                }
                true
            }
        })
        if(editingOutbound.type in dnsTypes) preferenceScreen.addPreference(Preference(requireContext()).apply {
            summary=(if(editingOutbound.type=="slipstream" || app.qthrone.mobile.outbound.types.DnsTunnel.keyed(editingOutbound.type)) "Резолверы: по одному в строке, IP или IP:порт (UDP)."
                else "Резолверы: по одному в строке — IP или IP:порт (UDP), https://… (DoH), tls://… (DoT).")+
                " Без резолверов используются DNS-серверы текущей сети."+
                if(app.qthrone.mobile.outbound.types.DnsTunnel.keyed(editingOutbound.type)) "" else " Через туннель передаётся только TCP, DNS-запросы идут по TCP."
            isSelectable=false
        })
        if(editingOutbound.type=="s3bypass") preferenceScreen.addPreference(Preference(requireContext()).apply {
            summary="Доступ к корзине S3 и префикс выдаёт сервер S3 Bypass. Параметры опроса и записи совпадают с оригинальным клиентом."
            isSelectable=false
        })
        if(editingOutbound.type=="olcrtc") preferenceScreen.addPreference(Preference(requireContext()).apply {
            summary="Только TCP. DNS внутри туннеля использует TCP. Параметры транспорта должны совпадать с сервером."
            isSelectable=false
        })
    }

    private val dnsTypes=app.qthrone.mobile.outbound.types.DnsTunnel.TYPES
    private val backendFields=listOf("backend_type","backend.user","backend.password","backend.pk","backend.method")
    /** OpenFlux carriers in the order the original apps list them. */
    private val openFluxKinds=listOf("yandex","vyandex","boards","mailru","cupsonline","avito","telemost","direct")
    private val optionDefaults=linkedMapOf<String,Any>("vp8-fps" to 30,"vp8-batch" to 64,"fps" to 30,"batch" to 64,"frag" to 900,"ack-ms" to 2000,
        "video-w" to 1080,"video-h" to 1080,"video-fps" to 30,"video-qr-size" to 0,"video-tile-module" to 4,"video-tile-rs" to 0,"video-codec" to "qrcode","video-qr-recovery" to "low")
    private val optionLimits=mapOf("vp8-fps" to 1..120,"vp8-batch" to 1..64,"fps" to 1..120,"batch" to 1..64,"frag" to 1..65535,"ack-ms" to 1..60000,
        "video-w" to 1..4096,"video-h" to 1..4096,"video-fps" to 1..120,"video-qr-size" to 0..65535,"video-tile-module" to 1..270,"video-tile-rs" to 0..200)
    private fun optionVisible(key:String,transport:String)=when(transport) {
        "videochannel" -> key.startsWith("video-")
        "vp8channel" -> key.startsWith("vp8-")
        "seichannel" -> !key.contains('-') || key=="ack-ms"
        else -> false
    }
}
