package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class VkRelayRequestsTest {
    @Test fun callsStartMatchesOriginalQwdtt() {
        val request=VkRelayRequests.startCall("token+/=value")
        assertEquals("GET",request.method)
        assertEquals("api.vk.ru",request.url.host)
        assertEquals("/method/calls.start",request.url.encodedPath)
        assertEquals("5.199",request.url.queryParameter("v"))
        assertEquals("token+/=value",request.url.queryParameter("access_token"))
        assertNull(request.body)
    }
    @Test fun cookieHeaderMergesOriginalDomainsWithoutDuplicateCookies() {
        assertEquals("remixsid=secret; remixlang=0; extra=1",VkRelayRequests.cookieHeader(mapOf(
            "https://vk.com" to "remixsid=secret; remixlang=0",
            "https://m.vk.com" to " remixsid=secret; extra=1",
            "https://evil.example" to "do-not-send=secret"
        )))
    }
}
