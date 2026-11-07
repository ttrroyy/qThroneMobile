package app.rawline.mobile.route

import app.rawline.mobile.outbound.OutboundFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OldSchemeLinkTest {
    @Test fun oldSchemeBecomesTheCurrentOne() {
        assertEquals("rawline://route/abc", UrlText.currentScheme("throne://route/abc"))
        assertEquals("rawline://route/abc", UrlText.currentScheme("THRONE://route/abc"))
        assertEquals("vless://x", UrlText.currentScheme("vless://x"))
    }

    @Test fun addLinksAreRecognisedUnderBothSchemes() {
        assertTrue(UrlText.isAddLink("throne://add/abc"))
        assertTrue(UrlText.isAddLink("rawline://add/abc"))
        assertFalse(UrlText.isAddLink("rawline://route/abc"))
    }

    @Test fun oldAddLinkStillDecodes() {
        val body = "{\"type\":\"socks\"}"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        assertEquals(body, OutboundFactory.decodeJsonLink("throne://add/$encoded", true))
        assertEquals(body, OutboundFactory.decodeJsonLink("rawline://add/$encoded", true))
    }
}
