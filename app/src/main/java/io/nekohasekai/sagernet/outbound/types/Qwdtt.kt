package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.CarrierKind
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import java.util.UUID

/** Desktop qwdtt.cpp configuration, without persisted runtime ports or bridge secrets. */
open class Qwdtt : Outbound("qwdtt"), app.qthrone.mobile.bg.carrier.NativeCarrier {
    override val carrierKind get() = CarrierKind.QWDTT
    init { serverPort=56000 }
    var password=""
    var hashes:List<String> = emptyList()
    // The original app starts in WG mode with TURN over TCP.
    var mode="wg"
    var hashSource="manual"
    var hashSourceChosen=false
    // Set when the user edits the hash slots: a subscription refresh then keeps them all.
    var manualHashesOverride=false
    var rawPort=56003
    var workers=9
    var turnTcp=true
    var noDtls=false
    var obfs="audio"
    var goDns="yandex"
    var vkAnonPath="vkcalls"
    var captchaMode="auto"
    var deviceId=UUID.randomUUID().toString()
    var deviceIdProvided=false
    /** Same remote endpoint as privateConfig/CarrierPool, including RAW's separate port. */
    /** The VK calls this profile joins; null with automatic hashes, which come from the account per launch. */
    internal fun vkCallKey()=hashes.sorted().takeIf { hashSource!="auto" && hashSource!="auto_js" && it.isNotEmpty() }
    internal fun probeSessionKey()=listOf(type,server,
        (if(carrierKind==CarrierKind.QWDTT && mode=="raw") rawPort else serverPort).toString(),
        if(carrierKind==CarrierKind.CSQTT) password else deviceId)
    override fun displayType()=if(mode == "wg") "qWDTT (WG)" else "qWDTT (RAW)"
    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type") !in listOf("qwdtt","wdtt")) return false
        super.parseFromJson(obj)
        hashSource = if(obj.string("hash_source") == "auto") "auto" else "manual"
        hashSourceChosen = obj.contains("hash_source")
        manualHashesOverride = obj.bool("manual_hashes_override")
        if(obj.contains("peer")) {
            val peer=LinkParser.parse("udp://"+obj.string("peer"))
            if(!peer.isValid || peer.host.isEmpty()) return false
            server=peer.host; serverPort=peer.port(56000)
        }
        if(obj.contains("name")) name=obj.string("name")
        password=if(obj.contains("password")) obj.string("password") else obj.string("pass")
        val h=if(obj.contains("hashes")) "hashes" else "vkHashes"
        val items=if(obj.isArray(h)) obj.array(h).strings() else listOf(obj.string(h))
        hashes=items.flatMap { it.split(Regex("[,\\s]+")) }.map(VkCallHash::strip).filter { it.isNotBlank() }.distinct().take(4)
        mode=when(val m=obj.string("mode").lowercase()) { "raw","rawtun" -> "raw"; "","wg","vpn","socks" -> "wg"; else -> m }
        rawPort=if(obj.contains("raw_port")) obj.int("raw_port") else 56003
        workers=if(obj.contains("workers")) obj.int("workers") else if(obj.contains("workersPerHash")) obj.int("workersPerHash") else 9
        workers=workers.coerceIn(9,(if(hashSource == "auto") 4 else hashes.size.coerceIn(1,4))*27)/9*9
        turnTcp=if(obj.contains("turn_tcp")) obj.bool("turn_tcp") else true
        // The original server reserves 56002 for RTP AEAD without DTLS; explicit settings win.
        noDtls=if(obj.contains("no_dtls")) obj.bool("no_dtls") else type=="qwdtt" && serverPort==56002
        obfs=obj.string("obfs").ifEmpty { "audio" }
        goDns=obj.string("go_dns").ifEmpty { "yandex" }
        vkAnonPath=obj.string("vk_anon_path").ifEmpty { "vkcalls" }
        captchaMode=obj.string("captcha_mode").ifEmpty { "auto" }
        deviceIdProvided=obj.string("device_id").isNotEmpty()
        if(deviceIdProvided) deviceId=obj.string("device_id")
        return server.isNotEmpty() && serverPort in 1..65535 && rawPort in 1..65535 && mode in listOf("raw","wg")
    }
    override fun parseFromLink(link:String):Boolean {
        if(link.startsWith("wdtt://",true)) {
            val value=link.substring(7);val parts=value.substringBefore('#').split(':')
            if(parts.size !in 5..6) return false
            val port=parts[1].toIntOrNull() ?: return false
            return parseFromJson(jsonObjectOf("type" to "qwdtt","peer" to Hosts.displayAddress(parts[0],port),
                "password" to LinkCodec.decodeFully(parts[4]),"hashes" to if(parts.size==6) LinkCodec.decodeFully(parts[5]) else "",
                "tag" to if(value.contains('#')) LinkCodec.decodeFully(value.substringAfter('#')) else ""))
        }
        val url=LinkParser.parse(link.replaceFirst("qwdtt:config","qwdtt://config"))
        if(!url.isValid || url.host!="config" || url.scheme!="qwdtt") return false
        val q=url.query
        if(!q.has("peer")) return false
        val obj=jsonObjectOf("type" to "qwdtt")
        for(key in listOf("peer","name","pass","password","hashes","hash_source","mode","obfs","go_dns","device_id","vk_anon_path","captcha_mode"))
            if(q.has(key)) obj[key]=q.valueFormDecoded(key)
        for(key in listOf("raw_port","workers")) if(q.has(key)) obj[key]=q.value(key).toIntOrNull() ?: return false
        if(q.has("turn_tcp")) obj["turn_tcp"]=q.value(key="turn_tcp") in listOf("1","true")
        if(q.has("no_dtls")) obj["no_dtls"]=q.value("no_dtls") in listOf("1","true")
        if(!q.has("name")) obj["name"]=url.fragment ?: ""
        if(q.has("dtls_port") || q.has("server_port")) {
            val peer=LinkParser.parse("udp://"+obj.string("peer"))
            val port=q.value(if(q.has("dtls_port")) "dtls_port" else "server_port").toIntOrNull() ?: return false
            if(!peer.isValid || port !in 1..65535) return false
            obj["peer"]=Hosts.displayAddress(peer.host,port)
        }
        return parseFromJson(obj)
    }
    override fun exportToJson()=jsonObjectOf("type" to type,"tag" to name,"server" to server,"server_port" to serverPort,
        "hash_source" to hashSource,"password" to password,"hashes" to JsonArray.of(*hashes.toTypedArray()),"mode" to mode,"raw_port" to rawPort,
        "workers" to workers,"turn_tcp" to turnTcp,"no_dtls" to noDtls,"obfs" to obfs,"go_dns" to goDns,"device_id" to deviceId,
        "vk_anon_path" to vkAnonPath,"captcha_mode" to captchaMode).also { if(manualHashesOverride) it["manual_hashes_override"]=true }
    override fun exportIdentity()=exportToJson().also { it.remove("tag");it.remove("device_id");it.remove("hash_source");it.remove("manual_hashes_override") }
    override fun exportToLink():String {
        val url=LinkBuilder("qwdtt");url.host="config";url.plusAsEscape=true
        url.addQueryItem("peer",Hosts.displayAddress(server,serverPort))
        url.addQueryItem("pass",password);url.addQueryItem("hashes",hashes.joinToString(","))
        for((key,value) in exportToJson()) if(key in listOf("mode","raw_port","workers","obfs","go_dns","device_id","vk_anon_path","captcha_mode")) url.addQueryItem(key,value.toString())
        url.addQueryItem("turn_tcp",if(turnTcp) "1" else "0");url.addQueryItem("name",name)
        url.addQueryItem("no_dtls",if(noDtls) "1" else "0")
        return url.build()
    }
    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"qWDTT requires a prepared native transport bridge")
    override fun privateConfig(port:Int, udpPort:Int, credentials:CarrierBridgeCredentials):JsonObject {
        require(server.isNotBlank() && serverPort in 1..65535 && rawPort in 1..65535)
        require(password.isNotEmpty() && password.none { it=='|' || it=='\n' || it=='\r' })
        require(deviceId.isNotEmpty() && deviceId.none { it=='|' || it=='\n' || it=='\r' })
        require(hashSource == "auto" || hashes.isNotEmpty()) { "Добавьте хеши VK в профиль или выберите автоматический источник" }
        require((hashSource == "auto" || hashes.size in 1..4) && workers in 1..108)
        require(mode in listOf("raw","wg") && obfs in listOf("audio","video"))
        require(vkAnonPath in listOf("vkcalls","legacy") && captchaMode in listOf("auto","wv","rjs"))
        return exportToJson().also {
            it["peer"]=Hosts.displayAddress(server,if(mode=="raw") rawPort else serverPort)
            it["listen"]=Hosts.displayAddress("127.0.0.1",udpPort)
            it["socks"]=Hosts.displayAddress("127.0.0.1",port)
            it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
        }
    }
}
