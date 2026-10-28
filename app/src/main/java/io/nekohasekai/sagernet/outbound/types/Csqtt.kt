package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials

/** Shares the authenticated qWDTT bridge; the external backend is the CSQTT Rust client. */
class Csqtt:Qwdtt() {
    override val carrierKind get() = app.qthrone.mobile.bg.carrier.CarrierKind.CSQTT
    var vkAccessToken = ""
    var turnTransport = "udp"
    var srtpMode = "auto"
    var fingerprint = "firefox"
    var clientIds = "8202606,6287487"
    var customTurnIps = ""
    init { type="csqtt";serverPort=46000;workers=10;turnTcp=false;obfs="unified" }
    override fun displayType()="CSQTT (${turnTransport.uppercase()})"
    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="csqtt") return false
        val copy=obj.copy();copy["type"]="qwdtt"
        if(!copy.contains("turn_tcp")) copy["turn_tcp"]=false
        if(!super.parseFromJson(copy)) return false
        hashSource = obj.string("hash_source").takeIf { it in listOf("manual","auto","auto_js") } ?: "manual"
        if(hashSource=="auto_js" || (!obj.contains("hash_source") && vkAnonPath=="auto_js")) {
            hashSource="auto_js";vkAnonPath="auto_js"
        } else if(vkAnonPath=="auto_js") vkAnonPath="vkcalls"
        vkAccessToken = obj.string("vk_access_token")
        turnTransport = obj.string("turn_transport").ifEmpty { if(turnTcp) "tcp" else "udp" }
        turnTransport = when(turnTransport.lowercase()) { "tcp","tcp-tls","tcp/tls" -> "tcp_tls"; else -> turnTransport.lowercase() }
        srtpMode = when(val value=obj.string("srtp_mode").lowercase()) {
            "wrap-srtp","wrap_srtp","legacy" -> "wrap"
            "dtls-srtp","dtls_srtp" -> "dtls"
            "" -> "auto"
            else -> value
        }
        fingerprint = obj.string("fingerprint").ifEmpty { "firefox" }
        clientIds = obj.string("client_ids").ifEmpty { "8202606,6287487" }
        customTurnIps = obj.string("custom_turn_ips")
        obfs = obj.string("obfs").ifEmpty { "unified" }
        hashes=obj.array("hashes").strings().take(6).map(VkCallHash::strip).filter { it.isNotEmpty() }
        workers=normalizeWorkers(if(obj.contains("workers")) obj.int("workers") else 18)
        return obfs in listOf("audio","video","unified") && vkAnonPath in listOf("vkcalls","legacy","auto_js") &&
            captchaMode in listOf("auto","wv","rjs") && turnTransport in listOf("udp","tcp","tcp_tls") &&
            srtpMode in listOf("auto","wrap","dtls") && vkAccessToken.length <= 4096 && vkAccessToken.none(Char::isWhitespace)
    }
    override fun parseFromLink(link:String):Boolean {
        parseCsqttLink(link)?.let { decoded ->
            return parseFromJson(jsonObjectOf("type" to "csqtt", "server" to decoded.host,
                "server_port" to decoded.port, "password" to decoded.password, "tag" to decoded.name,
                "hashes" to JsonArray.of(*decoded.hashes.toTypedArray()),
                "vk_access_token" to decoded.vkAccessToken,
                "hash_source" to if(decoded.vkAccessToken.isNotEmpty()) "auto_js" else "manual",
                "vk_anon_path" to if(decoded.vkAccessToken.isNotEmpty()) "auto_js" else "vkcalls"))
        }
        val url=LinkParser.parse(link.trim());if(!url.isValid || url.scheme!="csqtt") return false
        val obj=jsonObjectOf("type" to "csqtt","tag" to (url.fragment ?: ""))
        if(url.host=="connect") {
            val q=url.query
            if(q.valueFully("v")!="2" || url.port!=-1 || url.userName.isNotEmpty() || url.path.isNotEmpty()) return false
            obj["server"]=q.valueFully("host");obj["server_port"]=q.valueFully("peer").toIntOrNull() ?: return false
            obj["password"]=q.valueFully("password")
            if(q.has("name")) obj["tag"]=q.valueFully("name") else if(q.has("remark")) obj["tag"]=q.valueFully("remark")
            if(q.has("device_id")) obj["device_id"]=q.valueFully("device_id")
            if(q.has("hashes")) {
                val hashes=q.value("hashes").split('+').map { LinkCodec.decodeFully(it) }.filter { it.isNotBlank() }.distinct().take(6)
                if(hashes.size !in 1..6 || hashes.any { it.length<16 || it.any(Char::isWhitespace) } || hashes.distinct().size!=hashes.size) return false
                obj["hashes"]=JsonArray.of(*hashes.toTypedArray())
            }
        } else {
            obj["server"]=url.host;obj["server_port"]=url.port
            obj["password"]=url.userName + if(url.password.isNotEmpty()) ":"+url.password else ""
        }
        if((obj.string("server")+obj.string("password")).any(Char::isWhitespace) || obj.string("password").isEmpty()) return false
        return parseFromJson(obj)
    }
    override fun exportToJson()=super.exportToJson().also {
        for(key in listOf("mode","raw_port","go_dns","no_dtls")) it.remove(key)
        it["turn_transport"]=turnTransport;it["srtp_mode"]=srtpMode;it["fingerprint"]=fingerprint
        it["client_ids"]=clientIds;it["custom_turn_ips"]=customTurnIps
        it["vk_access_token"]=vkAccessToken
    }
    override fun exportToLink():String {
        return formatCsqttLink(server,serverPort,password,if(hashSource=="auto_js") emptyList() else hashes,
            if(hashSource=="auto_js") vkAccessToken else "",name)
    }
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject {
        require(server.isNotBlank() && serverPort in 1..65535)
        require(password.isNotEmpty() && (password+deviceId).none { it=='|' || it=='\n' || it=='\r' })
        require(hashSource in listOf("auto","auto_js") || hashes.isNotEmpty()) { "Добавьте хеши VK в профиль или выберите автоматический источник" }
        require(deviceId.isNotEmpty() && (hashSource in listOf("auto","auto_js") || hashes.size in 1..6) && hashes.distinct().size==hashes.size)
        require(hashes.all { h -> h.length>=16 && h.none { it==',' || it=='|' || it.isWhitespace() } })
        require(workers == normalizeWorkers(workers) && obfs in listOf("audio","video","unified"))
        // The editor validates before the linked account supplies its launch-only token.
        return exportToJson().also {
            // Like TunnelService in 2.5.0: every launch owns a new server session.
            it["generation"]=generation.updateAndGet { previous -> maxOf(previous+1,System.currentTimeMillis()/1000) }
            it["salt"]=java.util.UUID.randomUUID().toString().replace("-", "")
            it["backend"]="csqtt";it["peer"]=Hosts.displayAddress(server,serverPort)
            it["listen"]=Hosts.displayAddress("127.0.0.1",udpPort);it["socks"]=Hosts.displayAddress("127.0.0.1",port)
            it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
        }
    }
    companion object {
        private val generation=java.util.concurrent.atomic.AtomicLong()
        fun normalizeWorkers(requested: Int) = if(requested <= 5) 5 else maxOf(5, requested.coerceIn(5,100)/10*10)
    }
}
