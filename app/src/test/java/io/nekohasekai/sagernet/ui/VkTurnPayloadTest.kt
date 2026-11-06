package app.rawline.mobile.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VkTurnPayloadTest {
    @Test fun payloadMatchesQwdttStdinFieldNames() {
        val hash="abcdefghijklmnop"
        val wire=VkTurnPayload.encode(hash,"""{"username":"turn-user","credential":"turn-pass","urls":["turn:relay.example:3478?transport=udp"]}""")!!
        assertEquals(hash,wire.substringBefore('|'))
        val json=JSONObject(String(java.util.Base64.getDecoder().decode(wire.substringAfter('|')),Charsets.UTF_8))
        assertEquals("turn-user",json.getString("u"))
        assertEquals("turn-pass",json.getString("p"))
        assertEquals(1,json.getJSONArray("urls").length())
        assertFalse(wire.contains("turn-pass"))
    }
    @Test fun missingCredentialsAndNonTurnUrlsAreRejected() {
        assertNull(VkTurnPayload.encode("abcdefghijklmnop","""{"username":"u","credential":"","urls":["turn:relay:3478"]}"""))
        assertNull(VkTurnPayload.encode("abcdefghijklmnop","""{"username":"u","credential":"p","urls":["https://evil.example"]}"""))
    }
}
