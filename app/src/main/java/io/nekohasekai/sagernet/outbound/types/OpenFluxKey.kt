package app.qthrone.mobile.outbound.types
import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.*

/**
 * A profile of the wlruscfd/openflux-server fork: the openflux://import?data=<base64url JSON> link its control plane
 * issues (controlplane/internal/api/deeplink.go), read as openflux-app's ProfileDeepLink reads it. The fork's exit
 * nodes frame traffic their own way, so it runs through the fork's own client (carriers/openfluxkey), not OpenFlux's.
 */
class OpenFluxKey:Outbound("openfluxkey"),NativeCarrier {
    override val carrierKind get()=CarrierKind.OPENFLUXKEY
    private var data=JsonObject()
    override fun displayType()="OpenFlux Key"
    override fun displayAddress()=runCatching { LinkParser.parse(docUrls().firstOrNull().orEmpty()).host }.getOrDefault("")

    companion object {
        const val PREFIX="openflux://import"
        /** The bridge's transports (bridge/mobile.go buildTransport), "script" aside: it needs the fork's script packages. */
        val TRANSPORTS=listOf("yandex","volga","max","boards","mailru","mts","telemost","cupsonline","avito","yandex_multistream")
        /** openflux-app turns end-to-end encryption off for these: their carriers cannot hold it. */
        private val NO_E2E=listOf("mailru","boards","telemost","cupsonline","avito")
        private val KEYS=listOf("transport","doc_url","doc_urls","key_token","control_url","e2e_encryption","max_token","max_uid","mtu")
    }

    private fun docUrls()=(listOf(data.string("doc_url"))+data.array("doc_urls").strings()).filter { it.isNotBlank() }

    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="openfluxkey") return false
        data=JsonObject()
        for(key in KEYS) if(obj.contains(key)) data[key]=obj[key]!!
        name=obj.string("name").ifEmpty { obj.string("tag") }
        data["transport"]=data.string("transport").lowercase().ifEmpty { "yandex" }
        if(data.string("transport") in NO_E2E) data.remove("e2e_encryption")
        server=displayAddress();serverPort=443
        return valid()
    }

    private fun valid():Boolean {
        val transport=data.string("transport")
        if(transport !in TRANSPORTS) return false
        if(transport=="max") return data.string("max_token").isNotBlank() && data.int("max_uid")!=0
        val docs=docUrls()
        return docs.isNotEmpty() && docs.all { address ->
            runCatching { LinkParser.parse(address) }.getOrNull()?.let { it.isValid && it.scheme=="https" && it.host.isNotEmpty() }==true
        }
    }

    override fun parseFromLink(link:String):Boolean {
        val text=link.trim()
        if(!text.startsWith(PREFIX,ignoreCase=true) || text.length>65536) return false
        val query=text.substringAfter('?',"")
        val encoded=query.split('&').firstOrNull { it.startsWith("data=") }?.substringAfter('=') ?: return false
        val body=OpenFlux.normalizeBody(LinkCodec.decodeFully(encoded))
        val json=Base64Strict.decode(body,true) ?: return false
        val obj=runCatching { JsonInput.parseObject(String(json,Charsets.UTF_8)) }.getOrNull() ?: return false
        obj["type"]="openfluxkey"
        return parseFromJson(obj)
    }

    override fun exportToJson()=data.copy().also { it["type"]=type;if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=data.copy().also { it["type"]=type }

    /** The link the fork's control plane and app write: the same fields, base64url without padding. */
    override fun exportToLink():String {
        val payload=jsonObjectOf("name" to name,"mode" to "key")
        for(key in listOf("control_url","key_token","doc_url","doc_urls","transport","e2e_encryption","max_token","max_uid"))
            if(data.contains(key)) payload[key]=data[key]!!
        return "$PREFIX?data="+Base64Strict.encode(payload.toCompact().toByteArray(Charsets.UTF_8),urlSafe=true,padding=false)
    }

    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"OpenFlux Key requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=data.copy().also {
        require(valid())
        it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
