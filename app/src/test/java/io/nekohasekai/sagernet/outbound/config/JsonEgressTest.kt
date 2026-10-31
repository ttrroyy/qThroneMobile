package app.qthrone.mobile.outbound.config

import app.qthrone.mobile.outbound.import.ProfileImport
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.types.Custom
import org.junit.Assert.*
import org.junit.Test

class JsonEgressTest {
    @Test fun singBoxSelectorUsesItsDefaultAndKeepsRoutingWhenAddingAndroidTun() {
        val body="""{"outbounds":[{"type":"selector","tag":"select","outbounds":["v","h"],"default":"h"},{"type":"vless","tag":"v","server":"192.0.2.1","server_port":443},{"type":"hysteria2","tag":"h","server":"192.0.2.2","server_port":443}],"route":{"final":"select","rules":[{"domain":["example.org"],"outbound":"v"}]}}"""
        // Added by hand as a custom config: imports split such configs like the desktop.
        val imported=Custom().apply { subtype=Custom.CUSTOM_FULL_CONFIG;config=body }
        assertEquals("Hysteria 2",imported.displayType())
        val result=ConfigGenerator(ProfileProvider { imported },GeneratorSettings(vpnMode=true)).build(1)
        assertNull(result.error)
        val config=JsonInput.parseObject(result.coreConfig)
        assertEquals("select",config.obj("route").string("final"))
        assertEquals(1,config.obj("route").array("rules").size)
        assertTrue(config.array("inbounds").filterIsInstance<JsonObject>().any { it.string("type")=="tun" })
        assertEquals(body,imported.config)
    }

    @Test fun mixedDynamicPoolDoesNotInventOneProtocol() {
        val sing=Custom().apply {
            subtype=Custom.CUSTOM_FULL_CONFIG
            config="""{"outbounds":[{"type":"urltest","tag":"pool","outbounds":["a","b"]},{"type":"vless","tag":"a"},{"type":"hysteria2","tag":"b"}],"route":{"final":"pool"}}"""
        }
        assertEquals("Custom Config",sing.displayType())
        val xray=Custom().apply {
            subtype=Custom.CUSTOM_XRAY_FULL_CONFIG
            config="""{"outbounds":[{"protocol":"vless","tag":"node-a"},{"protocol":"hysteria","tag":"node-b"}],"routing":{"balancers":[{"tag":"pool","selector":["node-"]}],"rules":[{"type":"field","balancerTag":"pool"}]}}"""
        }
        assertEquals("Custom Xray Config",xray.displayType())
    }

    @Test fun cyclicSelectorDoesNotRecurseForever() {
        val custom=Custom().apply { subtype=Custom.CUSTOM_FULL_CONFIG
            config="""{"outbounds":[{"type":"selector","tag":"loop","outbounds":["loop"]}]}""" }
        assertEquals("Custom Config",custom.displayType())
    }
}
