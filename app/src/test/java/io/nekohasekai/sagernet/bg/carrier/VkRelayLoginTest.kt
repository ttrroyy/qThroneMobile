package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class VkRelayLoginTest {
    @Test fun cookieDoesNotInterruptTheVkIdLoginFlow() {
        assertTrue(VkRelayLogin.awaitingLogin("https://id.vk.ru/authorize"))
        assertTrue(VkRelayLogin.awaitingLogin("https://id.vk.com/auth"))
        assertFalse(VkRelayLogin.awaitingLogin("https://m.vk.ru/"))
    }
    @Test fun retriesMobileThenDesktopWithMatchingUserAgent() {
        assertEquals("https://m.vk.ru/login",VkRelayLogin.startUrl(0))
        assertEquals("https://m.vk.ru/",VkRelayLogin.startUrl(1))
        assertEquals("https://vk.ru/login",VkRelayLogin.startUrl(2))
        assertEquals("default",VkRelayLogin.userAgent("default",1))
        assertTrue(VkRelayLogin.userAgent("default",2).contains("Windows NT"))
    }
}
