package app.rawline.mobile.outbound.types
import app.rawline.mobile.outbound.*
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.*
import app.rawline.mobile.bg.carrier.*
import java.util.UUID

class OlcRtc:Outbound("olcrtc"),NativeCarrier {
    override val carrierKind get()=CarrierKind.OLCRTC
    var deviceId=UUID.randomUUID().toString()
    private var data=JsonObject()
    override fun displayType()="olcRTC"
    override fun parseFromJson(obj:JsonObject):Boolean {
        data=obj.copy();name=obj.string("name").ifEmpty { obj.string("tag") }
        for((key,value) in listOf("provider" to "jitsi","transport" to "datachannel","dns" to "8.8.8.8:53")) if(!data.contains(key)) data[key]=value
        if(obj.string("device_id").isNotEmpty()) deviceId=obj.string("device_id")
        val room=data.string("room");val url=LinkParser.parse(if(room.startsWith("https://")) room else "https://"+room)
        server=url.host.ifEmpty { data.string("provider") };serverPort=443
        return valid()
    }
    private fun valid():Boolean {
        val provider=data.string("provider");val transport=data.string("transport");val room=data.string("room")
        if(provider !in listOf("jitsi","telemost","wbstream") || transport !in listOf("datachannel","vp8channel","seichannel","videochannel")) return false
        if(provider=="telemost" && transport in listOf("datachannel","seichannel")) return false
        if(provider=="wbstream" && transport=="datachannel" && data.string("token").isEmpty()) return false
        if(room.isBlank() || room.any { it in "\r\n" } || !data.string("key").matches(Regex("[0-9a-fA-F]{64}"))) return false
        if(provider=="jitsi") {
            val url=LinkParser.parse(if(room.startsWith("https://")) room else "https://"+room)
            if(!url.isValid || url.scheme!="https" || url.host.isEmpty() || url.path.length<2 || url.userName.isNotEmpty()) return false
        }
        val dns=LinkParser.parse("tcp://"+data.string("dns"))
        if(!dns.isValid || dns.host.isEmpty() || dns.port !in 1..65535 || dns.path.isNotEmpty() || dns.userName.isNotEmpty()) return false
        val options=data.obj("options")
        val limits=mapOf("vp8-fps" to 1..120,"vp8-batch" to 1..64,"fps" to 1..120,"batch" to 1..64,"frag" to 1..65535,"ack-ms" to 1..60000,
            "video-w" to 1..4096,"video-h" to 1..4096,"video-fps" to 1..120,"video-qr-size" to 0..65535,"video-tile-module" to 1..270,"video-tile-rs" to 0..200)
        for((key,value) in options) {
            if(key=="video-codec") { if(value !in listOf("qrcode","tile")) return false }
            else if(key=="video-qr-recovery") { if(value !in listOf("low","medium","high","highest")) return false }
            else { val number=value.toString().toIntOrNull() ?: return false;val range=limits[key] ?: return false;if(number !in range) return false }
        }
        if(options.string("video-codec")=="tile" && (options["video-w"].toString()!="1080" || options["video-h"].toString()!="1080")) return false
        return true
    }
    override fun parseFromLink(link:String):Boolean {
        if(!link.startsWith("olcrtc://") || link.length>16384) return false
        val text=link.substring(9);val question=text.indexOf('?');val at=text.indexOf('@',question+1);val hash=text.lastIndexOf('#')
        if(question<1 || at<=question || hash<=at) return false
        val dollar=text.indexOf('$',hash+1)
        val obj=jsonObjectOf("type" to type,"provider" to text.substring(0,question),"room" to LinkCodec.decodeFully(text.substring(at+1,hash)),
            "key" to text.substring(hash+1,if(dollar<0) text.length else dollar),"name" to if(dollar<0) "" else LinkCodec.decodeFully(text.substring(dollar+1)))
        var spec=text.substring(question+1,at);val bracket=spec.indexOf('<')
        if(bracket>=0) {
            if(!spec.endsWith('>')) return false
            val query=LinkParser.parse("https://options.invalid/?"+spec.substring(bracket+1,spec.length-1)).query
            val options=JsonObject()
            for((key,value) in query.items) { if(options.contains(key)) return false;if(key !in listOf("video-bitrate","video-hw")) options[key]=LinkCodec.decodeFully(value) }
            obj["options"]=options;spec=spec.substring(0,bracket)
        }
        obj["transport"]=spec;return parseFromJson(obj)
    }
    override fun exportToJson()=data.copy().also { it["type"]=type;it["name"]=name;it["device_id"]=deviceId }
    override fun exportIdentity()=exportToJson().also { it.remove("name");it.remove("tag");it.remove("device_id") }
    override fun exportToLink():String {
        if(!valid() || data.string("token").isNotEmpty() || data.string("channel").isNotEmpty()) return ""
        var spec=data.string("transport")
        val options=data.obj("options")
        if(options.isNotEmpty()) spec+="<"+options.sortedKeys().joinToString("&") { it+"="+LinkCodec.percentEncodeAll(options[it].toString()) }+">"
        return "olcrtc://"+data.string("provider")+"?"+spec+"@"+LinkCodec.percentEncodeAll(data.string("room"))+"#"+data.string("key")+if(name.isEmpty()) "" else "$"+LinkCodec.percentEncodeAll(name)
    }
    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"olcRTC requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=exportToJson().also {
        require(valid() && deviceId.isNotBlank());it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
