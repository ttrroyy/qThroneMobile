package app.rawline.mobile.group

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class ProviderWebsiteLinkTest {
    private fun wrap(value: String) = "https://ssconnect.app/?q=" + URLEncoder.encode(value,"UTF-8")

    @Test fun unwrapsHappWebsiteLinksWithoutFetchingTheLandingPage() {
        val target="https://example.test/sub/token?key=one%2Btwo"
        val input=wrap("happ://add/$target")
        assertTrue(ProviderLinks.isProviderLink(input))
        assertFalse(ProviderLinks.isEncrypted(input))
        val decoded=ProviderLinks.subscription(input)!!
        assertEquals("Happ",decoded.provider)
        assertEquals(target,decoded.data)
        assertEquals(target,ProviderLinks.subscription(wrap(input))!!.data)
    }

    @Test fun keepsEncryptedClassificationAcrossTheWebsiteWrapper() {
        val input=wrap("happ://crypt5/invalid")
        assertTrue(ProviderLinks.isEncrypted(input))
        assertNull(ProviderLinks.decode(input))
    }

    @Test fun rejectsLookalikeHostsDuplicatePayloadsAndExcessiveNesting() {
        val input=wrap("happ://add/https://example.test/sub")
        assertFalse(ProviderLinks.isProviderLink(input.replace("ssconnect.app","ssconnect.app.evil.test")))
        assertNull(ProviderLinks.decode(input+"&q=another"))
        var nested=input
        repeat(9) { nested=wrap(nested) }
        assertNull(ProviderLinks.decode(nested))
        assertNull(ProviderLinks.subscription(wrap("happ://add/file:///data/private")))
    }
}
