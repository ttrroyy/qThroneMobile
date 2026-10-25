package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.link.Base64Strict
import org.junit.Assert.assertEquals
import org.junit.Test

class UserInfoPairTest {
    @Test fun encodedSeparatorSplitsTuicAndJuicity() {
        val tuic = OutboundFactory.parseLink("tuic://id%3Asecret@example.org:443?sni=example.org") as Tuic
        assertEquals("id", tuic.uuid)
        assertEquals("secret", tuic.password)
        val literal = OutboundFactory.parseLink("tuic://id:sec%3Aret@example.org:443?sni=example.org") as Tuic
        assertEquals("id", literal.uuid)
        assertEquals("sec:ret", literal.password)
        val juicity = OutboundFactory.parseLink("juicity://id%3Ap%40ss@example.org:443?sni=example.org") as Juicity
        assertEquals("id", juicity.uuid)
        assertEquals("p@ss", juicity.password)
    }

    @Test fun shadowsocksKeepsBothForms() {
        val plain = OutboundFactory.parseLink("ss://aes-256-gcm%3Ap%2Bss@example.org:8388") as Shadowsocks
        assertEquals("aes-256-gcm", plain.method)
        assertEquals("p+ss", plain.password)
        val encoded = Base64Strict.encode("aes-128-gcm:secret").trimEnd('=')
        val legacy = OutboundFactory.parseLink("ss://$encoded@example.org:8388") as Shadowsocks
        assertEquals("aes-128-gcm", legacy.method)
        assertEquals("secret", legacy.password)
    }
}
