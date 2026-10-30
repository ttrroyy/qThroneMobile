package app.qthrone.mobile.outbound.types
import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.*
import java.util.zip.Inflater
import java.util.zip.Deflater

/**
 * An openflux:// profile, read and checked the way OpenFlux's share package does it, so a link the
 * original apps accept is accepted here too. The native client hands the configuration to OpenFlux's
 * own mobile entry points; nothing is derived on this side (the encryption context included).
 */
class OpenFlux:Outbound("openflux"),NativeCarrier {
    override val carrierKind get()=CarrierKind.OPENFLUX
    private var data=JsonObject()
    override fun displayType()="OpenFlux"

    companion object {
        const val PREFIX="openflux://v1/"
        /** share.knownTypes; MAX (oneme) belongs to one account and is never shared. */
        val TYPES=listOf("yandex","vyandex","boards","mailru","cupsonline","avito","telemost","direct")
        /** Carriers the phpbox stream mode rides. */
        val STREAM_TYPES=listOf("cupsonline","mailru")
        /** utils.MinSecretChars, counted in UTF-16 units like the core does. */
        const val MIN_SECRET=16
        private const val MAX_PAYLOAD=16 shl 10
        /** Earlier builds stored this when a link had no context; the core derives the real one. */
        private const val INVENTED_CONTEXT="http://openflux"

        /** share.normalizeBody: what chats, terminals and QR apps do to the base64url part. */
        fun normalizeBody(body:String)=buildString {
            for(c in body) when(c) {
                ' ','\t','\r','\n',' ','​' -> {}
                '+' -> append('-')
                '/' -> append('_')
                else -> append(c)
            }
        }.trimEnd('=')
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="openflux") return false
        data=obj.copy();data.remove("type");data.remove("tag")
        name=obj.string("name").ifEmpty { obj.string("tag") }
        data.remove("name")
        if(data.string("context")==INVENTED_CONTEXT) data.remove("context")
        if(data.string("codec")=="batched") data.remove("codec")
        // Hand-written JSON may leave the mode out where only a Session can work.
        if(!data.contains("negotiate") && data.string("mode").isEmpty() && data.array("transports").let { list ->
                list.size>1 || list.any { (it as? JsonObject)?.string("type")=="direct" } }) data["negotiate"]=true
        server="";serverPort=0
        for(item in data.array("transports")) {
            val t=item as? JsonObject ?: return false
            if(t.string("type")=="direct") runCatching { LinkParser.parse("tcp://"+t.string("dial").trim()) }.getOrNull()?.let {
                server=it.host;serverPort=it.port
            }
        }
        return valid()
    }

    /** share.Config.Validate. */
    private fun valid():Boolean {
        val transports=data.array("transports")
        val secret=data.string("secret")
        val negotiate=data.bool("negotiate")
        val mode=data.string("mode")
        if(transports.isEmpty() || transports.size>16) return false
        if(mode.isNotEmpty()) {
            if(mode!="stream" || transports.size!=1 || negotiate || secret.isNotEmpty()) return false
            if((transports[0] as? JsonObject)?.string("type") !in STREAM_TYPES) return false
        }
        if(transports.size>1 && !negotiate) return false
        if(negotiate && secret.length<MIN_SECRET) return false
        if(secret.isNotEmpty() && secret.length<MIN_SECRET) return false
        if(data.string("codec") !in listOf("","batched","legacy")) return false
        if(listOf(secret,data.string("context")).any { it.any { c -> c=='\r' || c=='\n' } }) return false
        for(item in transports) {
            val t=item as? JsonObject ?: return false
            val type=t.string("type")
            if(type !in TYPES) return false
            if(listOf(t.string("url"),t.string("dial"),t.string("name")).any { it.any { c -> c=='\r' || c=='\n' } }) return false
            if(type=="direct") {
                if(t.string("dial").isBlank() || !negotiate) return false
                val u=runCatching { LinkParser.parse("tcp://"+t.string("dial").trim()) }.getOrNull() ?: return false
                if(!u.isValid || u.host.isEmpty() || u.port !in 1..65535) return false
            } else if(type!="telemost" && t.string("url").isBlank()) return false // Telemost without a link starts a new conference
        }
        return true
    }

    /** share.Decode: lenient about how the link travelled, strict about what it says. */
    override fun parseFromLink(link:String):Boolean {
        val text=link.trim()
        if(!text.startsWith(PREFIX) || text.length>65536) return false
        val fragment=text.indexOf('#')
        val body=normalizeBody(text.substring(PREFIX.length,if(fragment<0) text.length else fragment))
        val packed=Base64Strict.decode(body,true) ?: return false
        val inflater=Inflater(true)
        return try {
            inflater.setInput(packed)
            val out=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(4096)
            while(!inflater.finished()) {
                val count=inflater.inflate(buffer)
                if(count==0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer,0,count)
                if(out.size()>MAX_PAYLOAD) return false
            }
            if(!inflater.finished()) return false
            val obj=JsonInput.parseObject(out.toString(Charsets.UTF_8.name()));obj["type"]="openflux"
            if(fragment>=0) LinkCodec.decodeFully(text.substring(fragment+1)).trim().takeIf { it.isNotEmpty() }?.let { obj["name"]=it }
            parseFromJson(obj)
        } catch(_:Exception) { false } finally { inflater.end() }
    }

    override fun exportToJson()=data.copy().also { it["type"]=type;if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=data.copy().also { it["type"]=type }

    /** share.Make's canonical form, so the link reads the same in every OpenFlux client. */
    override fun exportToLink():String {
        if(!valid()) return ""
        val config=data.copy()
        if(name.isNotBlank()) config["name"]=name.trim()
        val transports=config.array("transports")
        if(transports.size==1) (transports[0] as? JsonObject)?.remove("priority")
        val bytes=config.toCompact().toByteArray()
        if(bytes.size>MAX_PAYLOAD) return ""
        val deflater=Deflater(Deflater.BEST_COMPRESSION,true)
        return try {
            deflater.setInput(bytes);deflater.finish()
            val out=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(4096)
            while(!deflater.finished()) out.write(buffer,0,deflater.deflate(buffer))
            PREFIX+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally { deflater.end() }
    }

    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"OpenFlux requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=data.copy().also {
        require(valid());it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
