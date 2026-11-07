package app.rawline.mobile.outbound.types

import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.bg.carrier.MaxAccount
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The MRTT transport: its own client over VK, MAX or Jitsi. Rules and numbers are those of the original app
 * (UiUtils.kt, WorkerCountPolicy.kt, TunnelManager.startTunnel). It shares the hash fields and the one VK binding
 * of the client with the other VK transports; a link carries VK or Jitsi, MAX is private data of this client.
 */
class Mrtt:Qwdtt() {
    override val carrierKind get() = app.rawline.mobile.bg.carrier.CarrierKind.MRTT
    var transport="vk"
    var vkAccessToken=""
    /** A link's group; empty takes the bound one. */
    var maxGroup=""
    var jitsiRoom=""
    var jitsiSeats=0
    var jitsiWorkers=DEFAULT_JITSI_WORKERS
    var turnTransport="udp"
    var srtpMode="auto"
    var fingerprint="firefox"
    var clientIds="8202606,6287487"
    var customTurnIps=""
    var mtu=1280
    init { type="mrtt";serverPort=46000;workers=18;turnTcp=false;obfs="unified" }
    override fun displayType()="MRTT ("+when(transport) { "max" -> "MAX";"jitsi" -> "Jitsi";else -> "VK" }+")"
    private val anyCount get()=hashSource=="auto_js" || transport=="max"
    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="mrtt") return false
        val copy=obj.copy();copy["type"]="qwdtt"
        if(!copy.contains("turn_tcp")) copy["turn_tcp"]=false
        if(!super.parseFromJson(copy)) return false
        hashSource=obj.string("hash_source").takeIf { it in listOf("manual","auto","auto_js") } ?: "manual"
        transport=obj.string("transport").ifEmpty { "vk" }
        vkAccessToken=obj.string("vk_access_token")
        maxGroup=obj.string("max_group").trim()
        jitsiRoom=obj.string("jitsi_room").trim().lowercase()
        jitsiSeats=if(obj.contains("jitsi_seats")) obj.int("jitsi_seats").coerceIn(0,MAX_JITSI_WORKERS) else 0
        jitsiWorkers=(if(obj.contains("jitsi_workers")) obj.int("jitsi_workers") else DEFAULT_JITSI_WORKERS).coerceIn(MIN_JITSI_WORKERS,MAX_JITSI_WORKERS)
        turnTransport=obj.string("turn_transport").ifEmpty { "udp" }
        srtpMode=obj.string("srtp_mode").ifEmpty { "auto" }
        fingerprint=obj.string("fingerprint").ifEmpty { "firefox" }
        clientIds=obj.string("client_ids").ifEmpty { "8202606,6287487" }
        customTurnIps=obj.string("custom_turn_ips")
        mtu=if(obj.contains("mtu")) obj.int("mtu") else 1280
        obfs="unified"
        hashes=obj.array("hashes").strings().take(MAX_HASHES).map(VkCallHash::strip).filter { it.isNotEmpty() }
        workers=normalizeWorkers(if(obj.contains("workers")) obj.int("workers") else 18,hashes.size,anyCount || hashSource=="auto")
        return transport in listOf("vk","max","jitsi") && turnTransport in listOf("udp","tcp_tls") &&
            srtpMode in listOf("auto","wrap","dtls") && vkAnonPath in listOf("vkcalls","legacy") && captchaMode in listOf("auto","wv","rjs") &&
            mtu in listOf(1280,1400,1500) && (maxGroup.isEmpty() || validMaxGroup(maxGroup)) && (jitsiRoom.isEmpty() || validJitsiRoom(jitsiRoom)) &&
            vkAccessToken.length<=4096 && vkAccessToken.none(Char::isWhitespace)
    }
    override fun parseFromLink(link:String):Boolean {
        val decoded=parseLink(link) ?: return false
        val json=jsonObjectOf("type" to "mrtt","tag" to decoded.host,"server" to decoded.host,"server_port" to decoded.port,"password" to decoded.password,
            "hashes" to JsonArray.of(*decoded.hashes.toTypedArray()),"vk_access_token" to decoded.token,"max_group" to decoded.maxGroup,
            "jitsi_room" to decoded.jitsiRoom,"jitsi_seats" to decoded.jitsiSeats,
            // The transport comes from the link, VK when it names none; MAX is chosen by hand here.
            "transport" to decoded.transport.ifEmpty { "vk" },"hash_source" to if(decoded.token.isNotEmpty()) "auto_js" else "manual")
        return parseFromJson(json)
    }
    override fun exportToJson()=super.exportToJson().also {
        for(key in listOf("mode","raw_port","go_dns","device_id")) it.remove(key)
        it["transport"]=transport;it["vk_access_token"]=vkAccessToken;it["max_group"]=maxGroup;it["jitsi_room"]=jitsiRoom
        it["jitsi_seats"]=jitsiSeats;it["jitsi_workers"]=jitsiWorkers;it["turn_transport"]=turnTransport;it["srtp_mode"]=srtpMode
        it["fingerprint"]=fingerprint;it["client_ids"]=clientIds;it["custom_turn_ips"]=customTurnIps;it["mtu"]=mtu
        it["device_id"]=deviceId
    }
    override fun exportToLink():String = formatLink(server,serverPort,password,if(hashSource=="manual") hashes else emptyList(),
        if(hashSource=="auto_js") vkAccessToken else "",maxGroup,jitsiRoom,jitsiSeats,transport)
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject {
        require(server.isNotBlank() && serverPort in 1..65535)
        require(validPassword(password)) { "MRTT: пароль без пробелов и «|», не длиннее 128 байт" }
        val config=jsonObjectOf("backend" to "mrtt","peer" to Hosts.displayAddress(server,serverPort),"password" to password,"transport" to transport,
            "device_id" to deviceId,"device_name" to android.os.Build.MODEL.orEmpty().replace('|',' ').take(64),"obfs" to "unified","turn_transport" to turnTransport,
            "srtp_mode" to srtpMode,"fingerprint" to fingerprint,"client_ids" to clientIds,"custom_turn_ips" to customTurnIps,"captcha_mode" to captchaMode,
            "vk_auth_mode" to vkAnonPath,"mtu" to mtu,"listen" to Hosts.displayAddress("127.0.0.1",udpPort),"socks" to Hosts.displayAddress("127.0.0.1",port),
            "socks_user" to credentials.username,"socks_pass" to credentials.password)
        when(transport) {
            "jitsi" -> {
                require(validJitsiRoom(jitsiRoom)) { "Вставьте ссылку на комнату Jitsi" }
                config["jitsi_room"]=jitsiRoom
                // A worker is a room participant: the seats the server named in the link, otherwise the own count.
                config["workers"]=(if(jitsiSeats>0) jitsiSeats else jitsiWorkers).coerceIn(MIN_JITSI_WORKERS,MAX_JITSI_WORKERS)
            }
            "max" -> {
                val account=MaxAccount.current() ?: error("Чтобы подключиться через MAX, привяжите MAX в настройках VK-релея")
                config["max_token"]=account.token
                config["max_group"]=maxGroup.ifEmpty { account.group }
                config["workers"]=normalizeWorkers(workers,0,true)
            }
            else -> {
                require(hashSource=="auto_js" || hashes.size in 1..MAX_HASHES) { "Добавьте хеши VK в профиль или выберите автоматический источник" }
                require(hashes.distinct().size==hashes.size && hashes.all { h -> h.length>=16 && h.none { it==',' || it=='|' || it.isWhitespace() } })
                config["hashes"]=JsonArray.of(*hashes.toTypedArray())
                if(hashSource=="auto_js") {
                    config["vk_hash_mode"]="auto_js";config["vk_auth_mode"]="auto_js";config["vk_access_token"]=vkAccessToken
                }
                config["workers"]=normalizeWorkers(workers,hashes.size,hashSource=="auto_js")
            }
        }
        return config
    }
    class Link(val host:String,val port:Int,val password:String,val hashes:List<String>,val token:String,val maxGroup:String,
        val jitsiRoom:String,val jitsiSeats:Int,val transport:String)
    companion object {
        const val WORKERS_PER_GROUP=9
        const val GROUPS_PER_HASH=3
        const val MAX_HASHES=6
        const val MAX_WORKERS=126
        const val MIN_JITSI_WORKERS=3
        const val MAX_JITSI_WORKERS=16
        const val DEFAULT_JITSI_WORKERS=6
        const val SCHEME="mrtt://"
        const val SUBSCRIPTION_SCHEME="mrttsub://"
        private val JITSI_ROOM=Regex("^[a-z0-9.:-]+/[a-z0-9]{1,64}$")
        private val MAX_GROUP=Regex("^-?[0-9]{1,64}$")
        private val key by lazy { SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("mrtt-chiper-key".toByteArray()),"AES") }
        fun maxWorkers(hashCount:Int,anyCount:Boolean)=if(anyCount) MAX_WORKERS/WORKERS_PER_GROUP*WORKERS_PER_GROUP
            else hashCount.coerceIn(1,MAX_HASHES)*GROUPS_PER_HASH*WORKERS_PER_GROUP
        fun normalizeWorkers(requested:Int,hashCount:Int,anyCount:Boolean)=
            requested.coerceIn(WORKERS_PER_GROUP,maxWorkers(hashCount,anyCount))/WORKERS_PER_GROUP*WORKERS_PER_GROUP
        fun validJitsiRoom(value:String)=JITSI_ROOM.matches(value)
        fun validMaxGroup(value:String)=MAX_GROUP.matches(value)
        fun validPassword(value:String)=value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size<=128 && value.none { it=='|' || it.isWhitespace() || it.isISOControl() }
        /** A conference link (https://meet.example.org/Room) as host/room; null when it is not one. */
        fun parseJitsiRoom(raw:String):String? = raw.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('?').substringBefore('#').trimEnd('/').lowercase().takeIf(::validJitsiRoom)
        fun generatePassword():String {
            val alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val random=java.security.SecureRandom()
            return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
        /** Nonce (HMAC of the text) || AES-256-GCM ciphertext || tag, in url-safe base64 without padding. */
        fun encrypt(plain:String):String {
            val bytes=plain.toByteArray(Charsets.UTF_8)
            val nonce=Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(bytes).copyOf(12)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,nonce)) }
            return Base64Strict.encode(String(nonce+cipher.doFinal(bytes),Charsets.ISO_8859_1).toByteArray(Charsets.ISO_8859_1),urlSafe=true,padding=false)
        }
        fun decrypt(payload:String):String? {
            val clean=payload.replace("\n","").replace("\r","").replace(" ","").trim()
            val bytes=Base64Strict.decode(clean.trimEnd('='),true) ?: Base64Strict.decode(clean) ?: return null
            if(bytes.size<28) return null
            return runCatching {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes,0,12)) }
                String(cipher.doFinal(bytes,12,bytes.size-12),Charsets.UTF_8)
            }.getOrNull()
        }
        private fun component(value:String)=runCatching { java.net.URLDecoder.decode(value.replace("+","%2B"),"UTF-8") }.getOrNull()
        private val CONCATENATED=Regex("(?:^|[&;]|(?<=[a-zA-Z0-9_]))(v|host|peer|password|client|hashes|token|max|jitsi|seats|workers|transport)=([^&?]*?)(?=(?:v|host|peer|password|client|hashes|token|max|jitsi|seats|workers|transport)=|$)",RegexOption.IGNORE_CASE)
        private fun query(raw:String):Map<String,String>? {
            val text=raw.replace("&amp;","&")
            val result=linkedMapOf<String,String>()
            for(part in text.split(Regex("[&;]"))) {
                val at=part.indexOf('=');if(at<=0) continue
                val name=component(part.substring(0,at)) ?: continue
                if(name in result) return null
                result[name]=part.substring(at+1)
            }
            if(listOf("v","host","peer").all { it in result }) return result
            val found=linkedMapOf<String,String>()
            for(match in CONCATENATED.findAll(text)) {
                val name=match.groupValues[1].lowercase()
                if(name in found) return null
                found[name]=match.groupValues[2]
            }
            return found.takeIf { f -> listOf("v","host","peer").all { it in f } } ?: result.takeIf { it.isNotEmpty() }
        }
        /** parseMrttLink of the original: only the encrypted form, no nested links. */
        fun parseLink(raw:String):Link? {
            val text=raw.trim()
            if(!text.startsWith(SCHEME,ignoreCase=true)) return null
            val plain=decrypt(text.substring(SCHEME.length)) ?: return null
            if(plain.contains("://") || (!plain.contains("host=") && !plain.contains("connect?"))) return null
            val values=query(if(plain.startsWith("connect?",ignoreCase=true)) plain.substring(8) else plain) ?: return null
            fun value(name:String)=values[name]?.let(::component)
            if(value("v")!="2") return null
            val host=value("host")?.takeIf { it.isNotBlank() } ?: return null
            if(host=="0.0.0.0" || host=="127.0.0.1" || host.equals("localhost",true) || host=="::1" || host.any(Char::isWhitespace)) return null
            val port=value("peer")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val password=value("password")?.trim().orEmpty()
            if(password.any(Char::isWhitespace)) return null
            var hashes=emptyList<String>()
            values["hashes"]?.let { joined ->
                val parts=joined.split('+')
                if(parts.size !in 1..MAX_HASHES || parts.any { it.isEmpty() }) return null
                hashes=parts.map { VkCallHash.strip(component(it).orEmpty()) }
                if(hashes.any { it.length<16 || it.any(Char::isWhitespace) } || hashes.distinct().size!=hashes.size) return null
            }
            val token=if("token" in values) value("token")?.trim().orEmpty().also { if(it.isEmpty() || it.length>4096 || it.any(Char::isWhitespace)) return null } else ""
            if(hashes.isNotEmpty() && token.isNotEmpty()) return null
            val group=if("max" in values) value("max")?.trim().orEmpty().also { if(!validMaxGroup(it)) return null } else ""
            if(group.isNotEmpty() && (hashes.isNotEmpty() || token.isNotEmpty())) return null
            val room=if("jitsi" in values) value("jitsi")?.trim()?.lowercase().orEmpty().also { if(!validJitsiRoom(it)) return null } else ""
            val seats=value("seats")?.toIntOrNull()?.coerceIn(1,MAX_JITSI_WORKERS) ?: 0
            val transport=value("transport")?.trim()?.lowercase().orEmpty().takeIf { it=="vk" || it=="jitsi" }.orEmpty()
            return Link(host,port,password,hashes,token,group,room,seats,transport)
        }
        /** formatMrttLink of the original. */
        fun formatLink(host:String,peer:Int,password:String,hashes:List<String>,token:String,maxGroup:String,jitsiRoom:String,jitsiSeats:Int,transport:String):String {
            fun encode(value:String)=java.net.URLEncoder.encode(value,"UTF-8")
            val query=mutableListOf("v=2","host=$host","peer=$peer","password="+encode(password))
            if(maxGroup.isNotEmpty()) query+="max=$maxGroup"
            else if(hashes.isNotEmpty()) query+="hashes="+hashes.joinToString("+")
            else if(token.isNotEmpty()) query+="token=$token"
            if(jitsiRoom.isNotEmpty()) {
                query+="jitsi="+encode(jitsiRoom)
                if(jitsiSeats>0) query+="seats=$jitsiSeats"
            }
            if(transport=="vk" || transport=="jitsi") query+="transport=$transport"
            return SCHEME+encrypt("connect?"+query.joinToString("&"))
        }
        /** A subscription: one link with several configs. Only valid mrtt links enter: this is a trust boundary. */
        fun decodeSubscription(raw:String):List<Pair<String,String>>? {
            val text=raw.trim()
            if(!text.startsWith(SUBSCRIPTION_SCHEME,ignoreCase=true)) return null
            val json=decrypt(text.substring(SUBSCRIPTION_SCHEME.length))?.let(JsonInput::parseObjectOrNull) ?: return null
            if(json.int("v")!=1) return null
            val links=json.array("links").filterIsInstance<JsonObject>().mapIndexedNotNull { index,item ->
                val link=item.string("link").trim()
                if(parseLink(link)==null) null else item.string("name").ifBlank { "Ссылка ${index+1}" } to link
            }
            return links.ifEmpty { null }
        }
        fun encodeSubscription(name:String,links:List<Pair<String,String>>):String {
            val items=JsonArray.of(*links.map { (title,link) -> jsonObjectOf("name" to title,"link" to link) }.toTypedArray())
            return SUBSCRIPTION_SCHEME+encrypt(jsonObjectOf("v" to 1,"name" to name,"links" to items).toCompact())
        }
    }
}
