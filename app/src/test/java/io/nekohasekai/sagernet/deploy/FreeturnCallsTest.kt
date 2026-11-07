package app.rawline.mobile.deploy

import app.rawline.mobile.outbound.types.Freeturn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeturnCallsTest {
    @Test fun anyFormOfACallBecomesTheOneTheClientReads() {
        assertEquals(listOf("https://vk.com/call/join/abc", "https://vk.com/call/join/def"),
            Freeturn.callLinks("https://vk.ru/call/join/abc?x=1\nabc\n, vk.com/call/join/def/"))
        assertEquals(emptyList<String>(), Freeturn.callLinks(" \n,"))
    }

    @Test fun theLinkCarriesTheCallsInItsVkField() {
        val payload = JSONObject().put("v", 1).put("provider", "vk").put("peer", "1.2.3.4:56000").put("cid", "c1")
        val link = "freeturn://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        val full = FreeturnRpc.withCalls(link, "https://vk.com/call/join/abc")
        val decoded = JSONObject(String(java.util.Base64.getUrlDecoder().decode(full.removePrefix("freeturn://"))))
        assertEquals("https://vk.com/call/join/abc", decoded.getString("vk"))
        assertEquals("c1", decoded.getString("cid"))
        assertEquals(link, FreeturnRpc.withCalls(link, ""))
    }
}
