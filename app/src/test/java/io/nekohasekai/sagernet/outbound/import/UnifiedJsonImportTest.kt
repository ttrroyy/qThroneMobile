package app.qthrone.mobile.outbound.import

import app.qthrone.mobile.outbound.link.Base64Strict
import org.junit.Assert.*
import org.junit.Test

class UnifiedJsonImportTest {
    private fun snapshot(profile: app.qthrone.mobile.outbound.Outbound): String =
        if(profile is app.qthrone.mobile.outbound.types.Custom) "${profile.subtype}:${profile.configObject().toCompact()}"
        else profile.exportToJson().toCompact()
    private fun wrapped(body:String)=listOf(body,"[$body]",
        "json://#"+Base64Strict.encode(body,urlSafe=true,padding=false),
        "throne://add/"+Base64Strict.encode(body,urlSafe=true,padding=false))

    @Test fun exportedProfilesStayEditableModels() {
        val profiles=listOf(app.qthrone.mobile.outbound.types.Socks().apply {
            server="192.0.2.1";serverPort=1080;username="test";password="secret"
        },app.qthrone.mobile.outbound.types.Vmess().apply {
            server="192.0.2.2";serverPort=443;uuid="00000000-0000-0000-0000-000000000001"
        })
        for(profile in profiles) for(input in wrapped(profile.exportToJson().toCompact())) {
            val result=ProfileImport.parse(input)
            assertEquals(result.messages.toString(),1,result.outbounds.size)
            assertEquals(profile.type,result.outbounds.single().type)
            assertEquals(profile.exportToJson().toCompact(),result.outbounds.single().exportToJson().toCompact())
        }
    }

    @Test fun ordinaryProtocolsKeepEveryFieldThroughAllJsonEntryPoints() {
        for(type in listOf("socks","http","shadowsocks","vmess","trojan","vless","hysteria","hysteria2",
            "tuic","juicity","trusttunnel","anytls","mieru","snell","shadowtls","wireguard","masque",
            "openvpn","openconnect","ssh","naive")) {
            val body="""{"type":"$type","tag":"Example","server":"example.org","server_port":443,"test_extension":"keep-me"}"""
            val original=ProfileImport.parse(body)
            assertEquals("$type: ${original.messages}",1,original.outbounds.size)
            val expected=snapshot(original.outbounds.single())
            for(input in wrapped(body)) {
                val result=ProfileImport.parse(input)
                assertEquals("$type: ${result.messages}",1,result.outbounds.size)
                assertEquals(expected,snapshot(result.outbounds.single()))
            }
        }
    }

    @Test fun nativeTransportsDoNotHideOtherProfilesInMixedArrays() {
        val native=listOf(
            """{"type":"qwdtt","server":"192.0.2.1","password":"test","hashes":["test_hash_123456789"],"device_id":"phone"}""",
            """{"type":"csqtt","server":"192.0.2.2","password":"test","hashes":["test_hash_123456789"],"device_id":"phone"}""",
            """{"type":"openflux","secret":"01234567890123456789","transports":[{"type":"direct","dial":"192.0.2.3:443"}]}""",
            """{"type":"olcrtc","room":"https://meet.example.org/room","key":"${"a".repeat(64)}","device_id":"phone"}"""
        )
        val all=native+"""{"type":"vmess","server":"example.org","server_port":443}"""+
            """{"protocol":"hysteria","settings":{"address":"example.org","port":443,"version":2}}"""
        val body="[${all.joinToString(",")}]"
        val expected=ProfileImport.parse(body)
        assertEquals(expected.messages.toString(),6,expected.outbounds.size)
        for(input in listOf(body,"""{"version":2,"servers":$body}""","""{"profiles":$body}""","json://#"+Base64Strict.encode(body,urlSafe=true,padding=false),
            "throne://add/"+Base64Strict.encode(body,urlSafe=true,padding=false))) {
            val result=ProfileImport.parse(input)
            assertEquals(expected.outbounds.map { it.exportToJson().toCompact() },result.outbounds.map { it.exportToJson().toCompact() })
        }
    }

    @Test fun fullXrayConfigIsNeverSplitOrStrippedOfRoutingByLinks() {
        val body="""{"outbounds":[{"protocol":"hysteria","tag":"hy","settings":{"version":2,"address":"example.org","port":443}}],"routing":{"balancers":[{"tag":"pool","selector":["hy"]}],"rules":[{"type":"field","balancerTag":"pool"}]}}"""
        for(input in wrapped(body)) {
            val result=ProfileImport.parse(input)
            assertEquals(result.messages.toString(),1,result.outbounds.size)
            assertEquals("Hysteria 2",result.outbounds.single().displayType())
            val custom=result.outbounds.single() as app.qthrone.mobile.outbound.types.Custom
            assertTrue(custom.configObject().obj("routing").contains("balancers"))
            assertEquals(2,(custom.configObject().array("outbounds")[0] as app.qthrone.mobile.outbound.json.JsonObject).obj("settings").int("version"))
        }
    }
}
