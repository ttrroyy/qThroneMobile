package app.rawline.mobile.outbound.`import`

import app.rawline.mobile.outbound.types.Csqtt
import app.rawline.mobile.outbound.types.Custom
import app.rawline.mobile.outbound.types.Qwdtt
import org.junit.Assert.*
import org.junit.Test

/** Import rules shared with the desktop parser (SubscriptionParser.cpp). */
class DesktopParityImportTest {
    @Test fun qwdttAndCsqttSchemesInAnyCaseAndBacktickedBotLinks() {
        val qwdtt = ProfileImport.parse("QWDTT://config?peer=192.0.2.1%3A56000&pass=secret&hashes=exampleHash123456789")
        assertTrue(qwdtt.messages.toString(), qwdtt.outbounds.single() is Qwdtt)
        val csqtt = ProfileImport.parse("Csqtt://connect?v=2&host=192.0.2.2&peer=46000&password=secret")
        assertTrue(csqtt.messages.toString(), csqtt.outbounds.single() is Csqtt)
        val bot = ProfileImport.parse("`qwdtt://config?peer=192.0.2.1%3A56000&pass=secret&hashes=exampleHash123456789`")
        assertEquals("192.0.2.1", (bot.outbounds.single() as Qwdtt).server)
    }

    @Test fun xrayOutboundListSplitsAndRoutedConfigStaysWhole() {
        val bare = """{"outbounds":[{"protocol":"trojan","tag":"a","settings":{"servers":[{"address":"a.example","port":443,"password":"p"}]}},{"protocol":"trojan","tag":"b","settings":{"servers":[{"address":"b.example","port":443,"password":"p"}]}},{"protocol":"freedom","tag":"direct"}],"inbounds":[{"port":1080,"protocol":"socks"}]}"""
        val split = ProfileImport.parse(bare)
        assertEquals(split.messages.toString(), 2, split.outbounds.size)
        val routed = ProfileImport.parse(bare.dropLast(1) + ""","routing":{"rules":[]}}""")
        assertEquals(Custom.CUSTOM_XRAY_FULL_CONFIG, (routed.outbounds.single() as Custom).subtype)
    }

    @Test fun singBoxConfigWithRoutingSplitsIntoServers() {
        val body = """{"inbounds":[{"type":"mixed","listen_port":2080}],"outbounds":[{"type":"selector","tag":"select","outbounds":["v","h"]},{"type":"vless","tag":"v","server":"192.0.2.1","server_port":443,"uuid":"bf000d23-0752-40b4-affe-68f7707a9661"},{"type":"hysteria2","tag":"h","server":"192.0.2.2","server_port":443,"password":"p"},{"type":"direct","tag":"direct"}],"route":{"final":"select"}}"""
        val result = ProfileImport.parse(body)
        assertEquals(result.messages.toString(), listOf("vless", "hysteria"), result.outbounds.map { it.type })
    }
}
