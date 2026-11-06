package app.rawline.mobile.outbound.types
import app.rawline.mobile.outbound.*
import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.outbound.link.*
import app.rawline.mobile.bg.carrier.*

/**
 * An S3 Bypass profile: the fedarisha outbound of SpaceNeuroX/s3-bypass, which carries traffic as objects in
 * an S3 bucket. Imported from the original app's Xray configuration (its fedarisha outbound), or our own JSON.
 */
class S3Bypass:Outbound("s3bypass"),NativeCarrier {
    override val carrierKind get()=CarrierKind.S3BYPASS
    private var data=JsonObject()
    override fun displayType()="S3 Bypass"
    override fun displayAddress()=data.obj("storage").let { storage ->
        val host=runCatching { LinkParser.parse(storage.string("endpoint")).host }.getOrDefault("")
        listOf(host,storage.string("bucket")).filter { it.isNotEmpty() }.joinToString("/")
    }

    companion object {
        val STORAGE_KEYS=listOf("endpoint","region","bucket","prefix","sessionsDir","accessKey","secretKey")
        val TUNING_KEYS=listOf("pollIntervalMs","writeIntervalMs","idleTimeoutSec","maxFileSizeBytes")

        /** The fedarisha outbound of an Xray configuration, as the original app imports it. */
        fun fromXrayConfig(config:JsonObject):S3Bypass? {
            val outbound=config.array("outbounds").filterIsInstance<JsonObject>().firstOrNull { it.string("protocol")=="fedarisha" } ?: return null
            val settings=outbound.obj("settings")
            val profile=S3Bypass()
            val name=config.string("remarks").ifEmpty { outbound.string("tag").takeIf { it!="proxy" }.orEmpty() }
            return profile.takeIf { it.parseFromJson(jsonObjectOf("type" to "s3bypass","name" to name,
                "storage" to settings.obj("storage").copy(),"tuning" to settings.obj("tuning").copy())) }
        }
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="s3bypass") return false
        data=obj.copy();data.remove("type");data.remove("tag")
        name=obj.string("name").ifEmpty { obj.string("tag") };data.remove("name")
        server=runCatching { LinkParser.parse(data.obj("storage").string("endpoint")).host }.getOrDefault("");serverPort=443
        return valid()
    }

    private fun valid():Boolean {
        val storage=data.obj("storage")
        if(storage.string("type").ifEmpty { "s3" }!="s3") return false
        if(listOf("bucket","accessKey","secretKey").any { storage.string(it).isBlank() }) return false
        val endpoint=runCatching { LinkParser.parse(storage.string("endpoint")) }.getOrNull() ?: return false
        if(endpoint.scheme !in listOf("http","https") || endpoint.host.isEmpty()) return false
        return data.obj("tuning").all { (_,value) -> value is Number }
    }

    override fun parseFromLink(link:String)=false
    override fun exportToJson()=data.copy().also { it["type"]=type;if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=data.copy().also { it["type"]=type }
    override fun exportToLink()=""
    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"S3 Bypass requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=data.copy().also {
        require(valid())
        it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
