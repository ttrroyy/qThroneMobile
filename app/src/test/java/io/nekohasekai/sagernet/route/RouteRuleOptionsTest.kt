package app.qthrone.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRuleOptionsTest {
    private fun reject(method: String, noDrop: Boolean) = RouteRule().apply {
        domain_suffix.add("example.org")
        action = "reject"
        reject_method = method
        no_drop = noDrop
    }

    @Test fun rejectMethodUsesTheCoreKey() {
        val json = reject("Reply", true).toRuleJson(false, "proxy")
        assertEquals("reply", json["method"])
        assertNull(json["reject_method"])
        assertEquals(true, json["no_drop"])
        // drop with no_drop is refused by the core; an unknown method too
        assertNull(reject("drop", true).toRuleJson(false, "proxy")["no_drop"])
        assertNull(reject("bogus", false).toRuleJson(false, "proxy")["method"])
    }

    @Test fun fragmentOptionsAreExclusive() {
        val rule = RouteRule().apply {
            domain_suffix.add("example.org")
            action = "route-options"
            tls_fragment = true
            tls_fragment_fallback_delay = "500ms"
            tls_record_fragment = true
        }
        val json = rule.toRuleJson(false, null)
        assertEquals(true, json["tls_fragment"])
        assertEquals("500ms", json["tls_fragment_fallback_delay"])
        assertNull(json["tls_record_fragment"])
        rule.tls_fragment = false
        val record = rule.toRuleJson(false, null)
        assertEquals(true, record["tls_record_fragment"])
        assertNull(record["tls_fragment_fallback_delay"])
    }

    @Test fun disabledRulesSurviveShareAndCopy() {
        val profile = RouteProfile().apply {
            name = "p"
            rules.add(reject("default", false).apply { name = "r"; disabled = true })
            custom_dns = true
            dns = RouteDns(remoteDns = "tls://1.1.1.1", directDns = "77.88.8.8")
        }
        assertTrue(profile.copy().rules.single().disabled)
        val imported = RouteShare.fromShareInput(RouteShare.toShareLink(profile) { null }) { null }.profile!!
        assertTrue(imported.rules.single().disabled)
        assertEquals("tls://1.1.1.1", imported.activeDns()?.remoteDns)
        profile.custom_dns = false
        assertFalse(RouteShare.fromShareInput(RouteShare.toShareLink(profile) { null }) { null }.profile!!.custom_dns)
    }
}
