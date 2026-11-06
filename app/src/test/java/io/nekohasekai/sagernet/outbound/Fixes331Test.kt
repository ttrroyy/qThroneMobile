package app.qthrone.mobile.outbound

import app.qthrone.mobile.bg.carrier.NativeHashPolicy
import app.qthrone.mobile.outbound.common.Transport
import app.qthrone.mobile.outbound.config.flagCountry
import app.qthrone.mobile.outbound.json.JsonArray
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.types.Qwdtt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Fixes331Test {
    private fun tls(type: String, alpn: JsonArray?) = Transport().apply { this.type = type }.tlsFor(JsonObject().also { json ->
        json["enabled"] = true
        if (alpn != null) json["alpn"] = alpn
    })

    @Test fun alpnFollowsTheTransportOnlyWhereTheLinkNamedOne() {
        assertEquals(listOf("http/1.1"), tls("ws", JsonArray.of("h2", "http/1.1")).array("alpn").strings())
        assertEquals(listOf("http/1.1"), tls("httpupgrade", JsonArray.of("h2")).array("alpn").strings())
        assertEquals(listOf("h2"), tls("grpc", JsonArray.of("http/1.1")).array("alpn").strings())
        assertEquals(listOf("h2", "http/1.1"), tls("tcp", JsonArray.of("h2", "http/1.1")).array("alpn").strings())
        assertFalse(tls("ws", null).contains("alpn"))
    }

    @Test fun flagInTheNameNamesTheCountry() {
        assertEquals("NL", flagCountry("🇳🇱 Нидерланды"))
        assertEquals("FI", flagCountry("[VLESS] 🇫🇮 ws"))
        assertEquals("", flagCountry("Без флага"))
        assertEquals("", flagCountry("🏳️ Белый"))
    }

    @Test fun pickedHashesReplaceTheFirstOnesAndLimitWorkers() {
        val saved = Qwdtt().apply { server = "192.0.2.1"; password = "fixture"; hashSource = "auto"; workers = 108 }
        val picked = listOf("a".repeat(16), "b".repeat(16))
        val runtime = NativeHashPolicy.runtimeProfile(saved, picked, { error("Chosen hashes need no new ones") }, { "" })
        assertEquals(picked, runtime.hashes)
        assertEquals(54, runtime.workers)
        assertEquals("manual", runtime.hashSource)
    }
}
