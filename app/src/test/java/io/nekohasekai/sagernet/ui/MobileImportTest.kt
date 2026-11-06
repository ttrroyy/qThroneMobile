package app.rawline.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class MobileImportTest {
    @Test fun recognisesHttpProfilesWithAndWithoutCredentials() {
        listOf("http://user:pass@proxy.example:8080", "https://:token@proxy.example",
            "http://proxy.example:3128/", "http://[2001:db8::1]:8080", "HTTP://proxy.example:8080")
            .forEach { assertTrue(it, SubscribeFlows.isHttpProxy(it)) }
    }

    @Test fun subscriptionPathsAndDocumentsAreNotHttpProfiles() {
        listOf("https://sub.example/sub/token", "https://sub.example:8443/sub/token",
            "https://sub.example?token=secret", "https://sub.example", "http://proxy.example:bad",
            "socks://proxy.example:1080", "http://proxy.example:8080\nsocks://other.example:1080")
            .forEach { assertFalse(it, SubscribeFlows.isHttpProxy(it)) }
    }
}
