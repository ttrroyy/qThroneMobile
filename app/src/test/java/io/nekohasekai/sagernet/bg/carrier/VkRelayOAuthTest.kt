package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class VkRelayOAuthTest {
    @Test fun tokenLookupUsesTheCacheOrTheOriginalHttpThenWebViewSequence() {
        assertEquals("cached",VkRelayOAuth.obtainToken("cached",120_000,0,{ error("Cache must be reused") },{ error("Unexpected WebView") }))
        var httpCalls=0;var webCalls=0
        assertEquals("http-token",VkRelayOAuth.obtainToken("",Long.MAX_VALUE,0,{ httpCalls++;"http-token" },{ error("HTTP succeeded") }))
        assertEquals(1,httpCalls)
        assertEquals("web-token",VkRelayOAuth.obtainToken("expired",0,0,{ throw java.io.IOException("offline") },{ webCalls++;"web-token" }))
        assertEquals(1,webCalls)
        assertNull(VkRelayOAuth.obtainToken("",0,0,{ null },{ null }))
        assertEquals("web-token",VkRelayOAuth.obtainToken("",0,0,{ "bad\ntoken" },{ "web-token" }))
    }
    @Test fun queryCallbacksStillRequireTheMatchingStateAndAnUnambiguousToken() {
        assertEquals("query-token" to 60L,VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html?access_token=query-token&expires_in=60&state=expected","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html?state=wrong#access_token=token&state=expected","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=bad%0Atoken&state=expected","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=bad%00token&state=expected","expected"))
    }

    @Test fun acceptsOnlyMatchingVkCallback() {
        assertEquals("test-token" to 3600L,VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=test-token&expires_in=3600&state=expected","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=test-token&state=wrong","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://example.com/blank.html#access_token=test-token&state=expected","expected"))
    }
    @Test fun rejectsLookalikesAndInsecureRedirects() {
        assertTrue(VkRelayOAuth.trustedHttps(URI("https://login.vk.com/?act=grant_access")))
        for(url in listOf("http://vk.com/","https://vk.com.example.com/","https://vk.com@evil.test/","https://vk.com:8443/")) {
            assertFalse(VkRelayOAuth.trustedHttps(URI(url)))
        }
    }
    @Test fun extractsHtmlAuthorizationRedirect() {
        assertEquals("https://login.vk.com/?act=grant_access&x=1",VkRelayOAuth.nextAuthorizeUrl("<a href=\"https://login.vk.com/?act=grant_access&amp;x=1\">"))
    }
}
