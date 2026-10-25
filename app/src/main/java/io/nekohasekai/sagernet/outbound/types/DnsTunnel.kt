package app.qthrone.mobile.outbound.types
import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.*

/**
 * A DNS tunnel profile: DNSTT, VayDNS, Slipstream, MasterDNS, CottenDNS or Soar. The fields are those of the dnst://
 * format (net2share/dnst-url-spec); links of the apps that predate it are read too (slipnet://, and the masterdns://,
 * stormdns://, cottendns:// and soar:// profiles). The resolvers are the profile's own; without them the network's
 * DNS servers are used at launch.
 */
class DnsTunnel(type:String="dnstt"):Outbound(type),NativeCarrier {
    override val carrierKind get()=when(type) {
        "vaydns" -> CarrierKind.VAYDNS
        "slipstream" -> CarrierKind.SLIPSTREAM
        "masterdns" -> CarrierKind.MASTERDNS
        "cottendns" -> CarrierKind.COTTENDNS
        "soar" -> CarrierKind.SOAR
        else -> CarrierKind.DNSTT
    }
    private var data=JsonObject()
    override fun displayType()=TITLES[type] ?: "DNS"
    override fun displayAddress()=data.string("domain")

    companion object {
        val TYPES=listOf("dnstt","vaydns","slipstream","masterdns","cottendns","soar")
        val TITLES=mapOf("dnstt" to "DNSTT","vaydns" to "VayDNS","slipstream" to "SlipStream","masterdns" to "MasterDNS",
            "cottendns" to "CottenDNS","soar" to "Soar")
        /** The engines with a domain and key of their own and their own client, not VayDNS's. */
        fun keyed(type:String)=type=="masterdns" || type=="cottendns" || type=="soar"
        private val SOAR_LABEL=Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
        val BACKENDS=listOf("socks","ssh","shadowsocks")
        val RECORD_TYPES=listOf("txt","cname","a","aaaa","mx","ns","srv","null","caa")
        private const val PREFIX="dnst://"
        private val WHITEDNS_SCHEMES=listOf("masterdns","stormdns","cottendns","soar")
        private val HEX64=Regex("[0-9a-fA-F]{64}")

        /** SlipNet's tunnel types that are DNS tunnels this client runs, by its export name. */
        private val SLIPNET_TYPES=mapOf("dnstt" to ("dnstt" to "socks"),"dnstt_ssh" to ("dnstt" to "ssh"),
            "vaydns" to ("vaydns" to "socks"),"vaydns_ssh" to ("vaydns" to "ssh"),
            "ss" to ("slipstream" to "socks"),"slipstream_ssh" to ("slipstream" to "ssh"))

        /** The network's DNS servers right now, for profiles that name no resolver. */
        fun systemResolvers():List<String> = runCatching {
            val connectivity=app.qthrone.mobile.SagerNet.connectivity
            val network=app.qthrone.mobile.SagerNet.underlyingNetwork ?: connectivity.activeNetwork
            connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { address ->
                address.hostAddress?.substringBefore('%')?.let { Hosts.displayAddress(it,53) }
            }
        }.getOrDefault(emptyList())
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        val kind=obj.string("type")
        if(kind !in TYPES) return false
        type=kind
        data=obj.copy();data.remove("type");data.remove("tag")
        name=obj.string("name").ifEmpty { obj.string("tag") };data.remove("name")
        data["domain"]=data.string("domain").trim().trimEnd('.')
        if(!keyed(type) && !data.contains("backend")) data["backend"]=jsonObjectOf("type" to "socks")
        if(keyed(type)) data.remove("backend")
        server=data.string("domain");serverPort=0
        return valid()
    }

    private fun valid():Boolean {
        val domain=data.string("domain")
        if(domain.isEmpty() || domain.length>253 || domain.any { it.isWhitespace() || it=='/' }) return false
        when(type) {
            "dnstt","vaydns" -> if(!data.string("pubkey").matches(HEX64)) return false
            "masterdns","cottendns" -> if(data.string("mkey").isBlank() || data.int("encryption") !in 0..5) return false
            "soar" -> {
                if(domain.length>45 || !domain.contains('.') || domain.lowercase().split('.').any { it.length>63 || !it.matches(SOAR_LABEL) }) return false
                if(Base64Strict.decode(data.string("pubkey").trim())?.size!=32) return false
            }
        }
        if(type=="vaydns" && data.string("record_type").isNotEmpty() && data.string("record_type") !in RECORD_TYPES) return false
        if(!keyed(type)) {
            val backend=data.obj("backend")
            when(backend.string("type")) {
                "socks" -> {}
                "ssh" -> if(backend.string("user").isEmpty() || (backend.string("password").isEmpty() && backend.string("pk").isEmpty())) return false
                "shadowsocks" -> if(backend.string("password").isEmpty() || backend.string("method").isEmpty()) return false
                else -> return false
            }
        }
        return data.array("resolvers").strings().none { it.any { c -> c=='\n' || c=='\r' } }
    }

    override fun parseFromLink(link:String):Boolean {
        val text=link.trim()
        return try {
            when {
                text.startsWith(PREFIX) -> parseDnst(text.removePrefix(PREFIX))
                text.startsWith("slipnet://") -> parseSlipNet(text.removePrefix("slipnet://"))
                else -> WHITEDNS_SCHEMES.firstOrNull { text.startsWith("$it://",ignoreCase=true) }
                    ?.let { parseWhiteDns(text.substring(it.length+3),it) } ?: false
            }
        } catch(_:Exception) { false }
    }

    /** dnst:// in either form: the URL with the domain as its host, or base64url JSON (§2.2: no '/' in it). */
    private fun parseDnst(body:String):Boolean {
        val content=body.substringBefore('#')
        if(!content.contains('/')) {
            val json=JsonInput.parseObject(String(Base64Strict.decode(content.trimEnd('='),true) ?: return false,Charsets.UTF_8))
            val transport=json.obj("transport");val backend=json.obj("backend")
            val obj=jsonObjectOf("type" to transport.string("type"),"name" to json.string("tag"))
            for((key,value) in transport) if(key!="type") obj[key]=value
            if(backend.isNotEmpty()) obj["backend"]=backend.copy()
            return parseFromJson(obj)
        }
        val url=LinkParser.parse(PREFIX+body)
        val parts=url.path.trim('/').split('/')
        if(url.host.isEmpty() || parts.size<2) return false
        val q=url.query
        val obj=jsonObjectOf("type" to parts[0],"domain" to url.host,"name" to (url.fragment ?: ""))
        for(key in listOf("pubkey","cert","record-type","idle-timeout","keepalive","mkey")) if(q.has(key)) obj[key.replace('-','_')]=q.valueFully(key)
        if(q.has("dnstt-compat")) obj["dnstt_compat"]=q.value("dnstt-compat") in listOf("1","true")
        for(key in listOf("clientid-size","encryption")) if(q.has(key)) obj[key.replace('-','_')]=q.value(key).toIntOrNull() ?: return false
        val backend=jsonObjectOf("type" to parts[1])
        for(key in listOf("user","password","pk","method")) if(q.has(key)) backend[key]=q.valueFully(key)
        obj["backend"]=backend
        return parseFromJson(obj)
    }

    /** SlipNet's export: base64 of pipe-separated fields; new versions only append, so positions hold. */
    private fun parseSlipNet(body:String):Boolean {
        val f=String(Base64Strict.decode(body.substringBefore('#').trim()) ?: Base64Strict.decode(body.trim(),true) ?: return false,Charsets.UTF_8).split('|')
        if(f.size<11) return false
        val (kind,backendType)=SLIPNET_TYPES[f[1]] ?: return false
        fun field(i:Int)=f.getOrNull(i).orEmpty()
        fun b64(i:Int)=field(i).takeIf { it.isNotEmpty() }?.let { Base64Strict.decode(it)?.toString(Charsets.UTF_8) }.orEmpty()
        val resolvers=JsonArray()
        val authoritative=field(4).split(',').any { it.split(':').getOrNull(2)=="1" }
        when(field(22)) {
            "doh" -> field(21).takeIf { it.startsWith("https://") }?.let { resolvers.add(it) }
            else -> for(item in (field(4).ifEmpty { field(37) }).split(',')) {
                val parts=item.split(':');if(parts.size<2 || parts[0].isBlank()) continue
                val address=Hosts.displayAddress(parts[0],parts[1].toIntOrNull() ?: 53)
                resolvers.add(if(field(22)=="dot") "tls://$address" else address)
            }
        }
        val obj=jsonObjectOf("type" to kind,"name" to field(2),"domain" to field(3),"resolvers" to resolvers)
        if(kind!="slipstream") obj["pubkey"]=field(11)
        if(kind=="slipstream") {
            obj["authoritative"]=authoritative
            field(6).toIntOrNull()?.let { obj["keep_alive_interval"]=it }
            if(field(7) in listOf("bbr","dcubic")) obj["congestion_control"]=field(7)
        }
        if(kind=="vaydns") {
            obj["dnstt_compat"]=field(41)=="1"
            field(42).takeIf { it in RECORD_TYPES }?.let { obj["record_type"]=it }
            field(49).toIntOrNull()?.takeIf { it>0 }?.let { obj["clientid_size"]=it }
        }
        val backend=jsonObjectOf("type" to backendType)
        if(backendType=="ssh") {
            backend["user"]=field(15);backend["password"]=field(16)
            b64(24).takeIf { it.isNotBlank() }?.let { backend["pk"]=it }
        } else {
            if(field(12).isNotEmpty()) backend["user"]=field(12)
            if(field(13).isNotEmpty()) backend["password"]=field(13)
        }
        obj["backend"]=backend
        return parseFromJson(obj)
    }

    /** The whitedns.profile link of the Iranian DNS tunnel apps: base64 JSON whose scheme names the engine. */
    private fun parseWhiteDns(body:String,scheme:String):Boolean {
        val payload=body.substringBefore('#').substringBefore('?')
        val json=JsonInput.parseObject(String(Base64Strict.decode(payload.trimEnd('='),true) ?: Base64Strict.decode(payload) ?: return false,Charsets.UTF_8))
        return parseWhiteDnsProfile(json,scheme)
    }

    /** StormDNS speaks MasterDnsVPN's protocol and runs on its client; CottenDNS and Soar have their own. */
    fun parseWhiteDnsProfile(json:JsonObject,linkScheme:String=""):Boolean {
        if(json.string("schema")!="whitedns.profile") return false
        val scheme=linkScheme.lowercase().takeIf { it=="cottendns" || it=="soar" } ?: json.string("import_type").lowercase()
        val profile=json.obj("profile");val server=profile.obj("server")
        val domains=server.array("domains").strings().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(server.string("domain").trim()) }
        if(scheme=="soar") {
            val obj=jsonObjectOf("type" to "soar","name" to profile.string("name"),"domain" to domains.first(),
                "pubkey" to server.string("public_key"),"resolvers" to JsonArray.of(*profile.array("resolvers").strings().toTypedArray()))
            if(profile.obj("soarSettings").isNotEmpty()) obj["soar"]=profile.obj("soarSettings").copy()
            return parseFromJson(obj)
        }
        val key=server.string("encryption_key").ifEmpty { server.string("encryptionKey") }
        val method=when {
            server.contains("encryption_method") -> server.int("encryption_method")
            server.contains("encryptionMethod") -> server.int("encryptionMethod")
            else -> 1
        }
        return parseFromJson(jsonObjectOf("type" to (if(scheme=="cottendns") "cottendns" else "masterdns"),"name" to profile.string("name"),
            "domain" to domains.joinToString(","),"mkey" to key,"encryption" to method))
    }

    /** CottenDNS and Soar profiles travel as the links their own apps import. */
    private fun exportWhiteDns():String {
        val domains=data.string("domain").split(',').filter { it.isNotBlank() }
        val server=jsonObjectOf("domain" to domains.firstOrNull().orEmpty())
        val profile=jsonObjectOf("name" to name.ifEmpty { domains.firstOrNull().orEmpty() })
        if(type=="soar") {
            server["public_key"]=data.string("pubkey")
            if(data.obj("soar").isNotEmpty()) profile["soarSettings"]=data.obj("soar").copy()
            if(data.array("resolvers").isNotEmpty()) profile["resolvers"]=data.array("resolvers").copy()
        } else {
            server["domains"]=JsonArray.of(*domains.toTypedArray())
            server["encryption_key"]=data.string("mkey");server["encryption_method"]=data.int("encryption")
        }
        profile["server"]=server
        val json=jsonObjectOf("schema" to "whitedns.profile","version" to 1,"import_type" to type,"profile" to profile)
        return type+"://"+Base64Strict.encode(json.toCompact(),urlSafe=true,padding=false)
    }

    override fun exportToJson()=data.copy().also { it["type"]=type;if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=data.copy().also { it["type"]=type }

    /** dnst:// (§2): the readable form, or base64url JSON when a certificate or key would not fit in a URL. */
    override fun exportToLink():String {
        if(!valid()) return ""
        if(type=="cottendns" || type=="soar") return exportWhiteDns()
        val backend=data.obj("backend")
        if(data.string("cert").isNotEmpty() || backend.string("pk").isNotEmpty()) {
            val transport=JsonObject().also { t -> t["type"]=type;for((k,v) in data) if(k!="backend" && k!="resolvers") t[k]=v }
            val json=jsonObjectOf("transport" to transport,"backend" to if(type=="masterdns") jsonObjectOf("type" to "socks") else backend)
            if(name.isNotEmpty()) json["tag"]=name
            return PREFIX+Base64Strict.encode(json.toCompact(),urlSafe=true,padding=false)
        }
        val query=ArrayList<String>()
        fun add(key:String,value:String) { if(value.isNotEmpty()) query.add(key+"="+LinkCodec.percentEncodeAll(value)) }
        add("pubkey",data.string("pubkey"));add("record-type",data.string("record_type"))
        if(data.bool("dnstt_compat")) add("dnstt-compat","true")
        if(data.int("clientid_size")>0) add("clientid-size",data.int("clientid_size").toString())
        add("idle-timeout",data.string("idle_timeout"));add("keepalive",data.string("keepalive"))
        add("mkey",data.string("mkey"));if(type=="masterdns") add("encryption",data.int("encryption").toString())
        for(key in listOf("user","password","method")) add(key,backend.string(key))
        val path="/"+type+"/"+(if(type=="masterdns") "socks" else backend.string("type"))
        return PREFIX+data.string("domain")+path+(if(query.isEmpty()) "" else "?"+query.joinToString("&"))+
            (if(name.isEmpty()) "" else "#"+LinkCodec.encodeFragment(name))
    }

    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"${displayType()} requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=data.copy().also {
        require(valid())
        it["type"]=type
        if(type=="soar") it["server_public_key"]=data.string("pubkey")
        it["system_resolvers"]=JsonArray.of(*systemResolvers().toTypedArray())
        it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
