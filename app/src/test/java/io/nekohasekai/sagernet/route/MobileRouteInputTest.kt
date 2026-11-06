package app.rawline.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileRouteInputTest {
    @Test fun domainNormalization() {
        assertEquals(listOf("example.com", "xn--e1afmkfd.xn--p1ai"), MobileRouteInput.domains("*.Example.COM. example.com пример.рф"))
    }
    @Test fun domainsRejectUrlsAndEmptyLabels() {
        for (value in listOf("https://example.com", "a..com", "-example.com", ""))
            assertThrows(IllegalArgumentException::class.java) { MobileRouteInput.domains(value) }
    }
    @Test fun networksValidateBothFamilies() {
        assertEquals(listOf("192.168.1.0/24", "2001:db8::/32", "127.0.0.1/32", "::1/128"), MobileRouteInput.networks("192.168.1.0/24,2001:db8::/32;127.0.0.1 ::1"))
        for (value in listOf("example.com", "192.168.0.1/33", "256.0.0.1", "::/129", "::/", "1.2.3.4/8/9"))
            assertThrows(IllegalArgumentException::class.java) { MobileRouteInput.networks(value) }
    }
}
