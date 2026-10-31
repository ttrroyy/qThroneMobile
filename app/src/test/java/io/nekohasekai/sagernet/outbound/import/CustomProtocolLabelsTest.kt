package app.qthrone.mobile.outbound.import

import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.types.Custom
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomProtocolLabelsTest {
    @Test fun jsonLabelsMatchOrdinaryProfilesWithoutChangingConfig() {
        val types=listOf("socks","http","shadowsocks","vmess","trojan","vless","hysteria",
            "hysteria2","tuic","juicity","trusttunnel","anytls","mieru","snell","shadowtls",
            "wireguard","masque","openvpn","openconnect","ssh","naive")
        for(type in types) {
            for(xray in listOf(false,true)) {
                for(full in listOf(false,true)) {
                    val outbound="""{"${if(xray) "protocol" else "type"}":"$type","tag":"proxy"}"""
                    val config=if(full) """{"outbounds":[$outbound]}""" else outbound
                    val profile=Custom().apply {
                        this.config=config
                        subtype=if(xray) {
                            if(full) Custom.CUSTOM_XRAY_FULL_CONFIG else Custom.CUSTOM_XRAY_OUTBOUND
                        } else if(full) Custom.CUSTOM_FULL_CONFIG else Custom.CUSTOM_OUTBOUND
                    }
                    // Xray's hysteria and sing-box's hysteria2 are both version 2.
                    val expected=if(type=="hysteria2" || xray && type=="hysteria") "Hysteria 2"
                        else OutboundFactory.newByType(if(xray && type=="vless") "xrayvless" else type).displayType()
                    assertEquals("$type xray=$xray full=$full",expected,profile.displayType())
                    assertEquals(config,profile.config)
                }
            }
        }
    }
    @Test fun unknownProtocolKeepsCustomLabel() {
        val profile=Custom().apply { subtype=Custom.CUSTOM_XRAY_FULL_CONFIG;config="""{"outbounds":[{"protocol":"unknown"}]}""" }
        assertEquals("Custom Xray Config",profile.displayType())
    }
}
