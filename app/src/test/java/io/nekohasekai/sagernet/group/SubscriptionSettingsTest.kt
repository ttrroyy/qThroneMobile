package app.rawline.mobile.group

import app.rawline.mobile.database.ServerSortRule
import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.database.SubscriptionOptions
import org.junit.Assert.*
import org.junit.Test

class SubscriptionSettingsTest {
    @Test fun allNewOptionsSurvivePersistence() {
        val original = SubscriptionOptions(
            serverSortRule = ServerSortRule.NAME,
            requestTimeoutSeconds = 90,
            sendHwidInCookie = true,
            allowInsecureRequests = true,
            ignoreNameFilters = true,
            allowCollapse = false,
            hideServerSettings = true,
        )
        assertEquals(original, SubscriptionOptions.parse(original.toJsonString()))
        assertEquals("{}", SubscriptionOptions().toJsonString())
    }

    @Test fun legacyPingChoicesMigrateWithoutLosingTheRule() {
        assertEquals(ServerSortRule.PING, SubscriptionOptions.parse("{\"auto_sort_by_ping\":true}").effectiveSortRule)
        assertEquals(ServerSortRule.PING, SubscriptionOptions.parse("{\"sort_by_latency\":true}").effectiveSortRule)
        assertEquals(ServerSortRule.NONE, SubscriptionOptions().effectiveSortRule)
        assertEquals(ServerSortRule.NAME,
            SubscriptionOptions(autoSortByPing = true, serverSortRule = ServerSortRule.NAME).effectiveSortRule)
    }

    @Test fun malformedTimeoutsUseTheGlobalDefault() {
        for (seconds in listOf(-1, 1, 4, 301, Int.MAX_VALUE)) {
            assertEquals(0, SubscriptionOptions.parse("{\"request_timeout_seconds\":$seconds}").requestTimeoutSeconds)
            assertFalse(SettingsRegistry.SUB_REQUEST_TIMEOUT.accepts(seconds.toString()))
        }
        assertTrue(SettingsRegistry.SUB_REQUEST_TIMEOUT.accepts("5"))
        assertTrue(SettingsRegistry.SUB_REQUEST_TIMEOUT.accepts("300"))
        assertEquals(10, SettingsRegistry.SUB_REQUEST_TIMEOUT.default)
    }

    @Test fun cookieIsOptionalEncodedAndFollowsSendHwid() {
        val device = DeviceDetails("abc; other=value", "Android", "16", "phone")
        assertFalse(RequestIdentity("ua", true, device).headers().any { it.first == "Cookie" })
        val headers = RequestIdentity("ua", true, device, sendHwidInCookie = true).headers().toMap()
        assertEquals("hwid=abc%3B%20other%3Dvalue", headers["Cookie"])
        assertEquals(device.hwid, headers["x-hwid"])
        assertTrue(RequestIdentity("ua", false, device, sendHwidInCookie = true).headers().isEmpty())
    }

    @Test fun headerInjectionCannotCreateACookie() {
        val device = DeviceDetails("abc\r\nCookie: bad", "Android", "16", "phone")
        val headers = RequestIdentity("ua", true, device, sendHwidInCookie = true).headers().toMap()
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("x-hwid"))
    }
}
