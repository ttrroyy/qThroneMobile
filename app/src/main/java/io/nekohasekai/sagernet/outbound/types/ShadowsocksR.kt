package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.*
import app.qthrone.mobile.outbound.import.ClashProxy
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.outbound.link.LinkParser

class ShadowsocksR : Outbound("shadowsocksr") {
    @JvmField var method="aes-256-cfb"
    @JvmField var password=""
    @JvmField var protocol="origin"
    @JvmField var protocolParam=""
    @JvmField var obfs="plain"
    @JvmField var obfsParam=""

    private fun valid()=server.isNotBlank() && server.none { it.isWhitespace() || it == '/' } && (method == "none" || password.isNotEmpty()) && serverPort in 1..65535 && method in METHODS && protocol in PROTOCOLS && obfs in OBFS

    override fun parseFromLink(link:String):Boolean {
        if(!link.startsWith("ssr://",true)) return false
        val decoded=decode(link.substring(6).trim()) ?: return false
        val parts=decoded.substringBefore("/?").trimEnd('/').split(':')
        if(parts.size<6) return false
        val tail=parts.takeLast(5)
        server=parts.dropLast(5).joinToString(":").removeSurrounding("[","]")
        serverPort=tail[0].toIntOrNull() ?: return false
        protocol=tail[1].lowercase();method=tail[2].lowercase();obfs=tail[3].lowercase()
        password=decode(tail[4]) ?: return false
        val query=LinkParser.parseQuery(decoded.substringAfter("/?",""))
        fun parameter(key:String):String?=if(query.has(key)) decode(query.valueFully(key)) else ""
        protocolParam=parameter("protoparam") ?: return false
        obfsParam=parameter("obfsparam") ?: return false
        name=parameter("remarks") ?: return false
        method=method.trim().lowercase();protocol=protocol.trim().lowercase();obfs=obfs.trim().lowercase()
        return valid()
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type") !in listOf("ssr","shadowsocksr")) return false
        super.parseFromJson(obj)
        name=obj.string("name").ifEmpty { obj.string("tag") }.ifEmpty { obj.string("remarks") }
        if(!obj.contains("server_port") && obj.contains("port")) serverPort=obj.int("port")
        method=obj.string("method").ifEmpty { obj.string("cipher") }.ifEmpty { "aes-256-cfb" }
        password=obj.string("password")
        protocol=obj.string("protocol").ifEmpty { "origin" }
        protocolParam=obj.string("protocol_param").ifEmpty { obj.string("protocolparam") }.ifEmpty { obj.string("protocol-param") }
        obfs=obj.string("obfs").ifEmpty { "plain" }
        obfsParam=obj.string("obfs_param").ifEmpty { obj.string("obfsparam") }.ifEmpty { obj.string("obfs-param") }
        method=method.trim().lowercase();protocol=protocol.trim().lowercase();obfs=obfs.trim().lowercase()
        return valid()
    }

    override fun parseFromClash(node:JsonObject):Boolean {
        val proxy=ClashProxy(node)
        if(proxy.type!="ssr") return false
        baseParseFromClash(proxy)
        method=proxy.string("cipher");password=proxy.string("password")
        protocol=proxy.string("protocol").ifEmpty { "origin" }
        protocolParam=proxy.string("protocol-param")
        obfs=proxy.string("obfs").ifEmpty { "plain" }
        obfsParam=proxy.string("obfs-param")
        method=method.trim().lowercase();protocol=protocol.trim().lowercase();obfs=obfs.trim().lowercase()
        return valid()
    }

    override fun exportToLink():String {
        if(!valid()) return ""
        val host=if(server.contains(':')) "[$server]" else server
        val query=listOf("protoparam" to protocolParam,"obfsparam" to obfsParam,"remarks" to name)
            .filter { it.second.isNotEmpty() }.joinToString("&") { it.first+"="+encode(it.second) }
        return "ssr://"+encode("$host:$serverPort:$protocol:$method:$obfs:${encode(password)}/?"+query)
    }

    override fun exportToJson()=JsonObject().apply {
        this["type"]="shadowsocksr";merge(baseExportToJson())
        this["method"]=method;this["password"]=password;this["protocol"]=protocol;this["obfs"]=obfs
        if(protocolParam.isNotEmpty()) this["protocol_param"]=protocolParam
        if(obfsParam.isNotEmpty()) this["obfs_param"]=obfsParam
    }

    override fun build(ctx:BuildContext):BuildResult {
        if(!valid()) return BuildResult(JsonObject(),"Invalid ShadowsocksR settings")
        return BuildResult(exportToJson().apply { remove("name");remove("tag");merge(baseBuild(ctx)) })
    }
    override fun displayType()="ShadowsocksR"
    override fun security()=SecurityInfo(if(method=="none") "Raw" else "Encrypted","",if(method=="none") SecurityLevel.None else SecurityLevel.Weak)

    companion object {
        val METHODS=listOf("none","rc4-md5","aes-128-ctr","aes-192-ctr","aes-256-ctr","aes-128-cfb","aes-192-cfb","aes-256-cfb","chacha20","chacha20-ietf","xchacha20")
        val PROTOCOLS=listOf("origin","auth_sha1_v4","auth_aes128_md5","auth_aes128_sha1","auth_chain_a","auth_chain_b")
        val OBFS=listOf("plain","http_simple","http_post","random_head","tls1.2_ticket_auth","tls1.2_ticket_fastauth")
        private fun decode(text:String):String?=(Base64Strict.decode(text,true) ?: Base64Strict.decode(text))?.toString(Charsets.UTF_8)
        private fun encode(text:String)=Base64Strict.encode(text,urlSafe=true,padding=false)
    }
}
