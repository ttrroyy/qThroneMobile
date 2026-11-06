package app.rawline.mobile.group

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SubscriptionCryptoTest {
    private val fixtures by lazy {
        javaClass.getResourceAsStream("/subscription_crypto/vectors.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
    }

    @Test fun decodesAllPublishedHappKeyMarkersAndBothCrypt5Layouts() {
        var checked = 0
        for (item in fixtures.getAsJsonArray("links")) {
            val vector = item.asJsonObject
            if (vector.has("invalid")) continue
            val link = ProviderLinks.decodeChecked(vector.get("link").asString)!!
            assertEquals(vector.get("name").asString, vector.get("text").asString, link.data)
            assertTrue(link.encrypted)
            assertNotNull(ProviderLinks.subscription(vector.get("link").asString))
            if (vector.has("providerName")) assertEquals(vector.get("providerName").asString, link.name)
            checked++
        }
        // Includes independent pinned official Incy and public salted-Happ vectors, plus block-spanning legacy RSA.
        assertEquals(80, checked)
    }

    @Test fun decryptsEverySupportedEncryptTagResponseKey() {
        for (item in fixtures.getAsJsonArray("responses")) {
            val vector = item.asJsonObject
            assertEquals(vector.get("key").asString, vector.get("text").asString,
                SubscriptionCrypto.decryptResponse("https://sub.example.test/?key=" + vector.get("key").asString,
                    vector.get("body").asString, vector.get("tag").asString))
        }
        assertEquals(90, fixtures.getAsJsonArray("responses").size())
        val first = fixtures.getAsJsonArray("responses")[0].asJsonObject
        assertEquals(first.get("text").asString, SubscriptionCrypto.decryptResponse("https://cdn.example.test/sub",
            first.get("body").asString, first.get("tag").asString, "https://example.test/sub?key=key01"))
    }

    @Test fun rejectsInvalidSchemaLocalFileUrlsFutureFormatsAndOversizedInput() {
        for (item in fixtures.getAsJsonArray("links")) {
            val vector = item.asJsonObject
            if (vector.has("invalid")) assertNull(ProviderLinks.decode(vector.get("link").asString))
        }
        assertNull(ProviderLinks.decode("happ://crypt6/unknown"))
        assertNull(ProviderLinks.decode("incy://crypt1/AA"))
        assertNull(ProviderLinks.decode("happ://crypt4/" + "A".repeat(SubscriptionCrypto.MAX_LINK_CHARS)))
        assertNull(ProviderLinks.subscription("happ://add/file:///data/data/secret"))
        assertNull(ProviderLinks.subscription("happ://add/https://user:pass@example.test/"))
    }

    @Test fun failsClosedOnAuthenticationErrorsWithoutExposingLinks() {
        val vector = fixtures.getAsJsonArray("links").first { it.asJsonObject.get("name").asString == "official-incy-vector" }.asJsonObject
        val input = vector.get("link").asString
        val damaged = input.substring(0, 45) + (if (input[45] == 'A') "B" else "A") + input.substring(46)
        assertNull(ProviderLinks.decode(damaged))
        val error = runCatching { ProviderLinks.decodeChecked(damaged) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("https://"))
        assertFalse(error.message.orEmpty().contains(damaged))
        val body = fixtures.getAsJsonArray("responses")[0].asJsonObject
        assertTrue(runCatching { SubscriptionCrypto.decryptResponse("https://example.test/?key=key01",
            body.get("body").asString, "AAAAAAAAAAAAAAAAAAAAAA==") }.isFailure)
        assertTrue(runCatching { SubscriptionCrypto.decryptResponse("https://example.test/?key=unknown",
            body.get("body").asString, body.get("tag").asString) }.isFailure)
        assertTrue(runCatching { SubscriptionCrypto.decryptResponse("https://example.test/?key=key01&key=key02",
            body.get("body").asString, body.get("tag").asString) }.isFailure)
    }

    @Test fun preservesPlainSubscriptionsAndUnwrapsEscapedEncryptedLinks() {
        val plain = "socks://user:pass@example.test:1080#Example"
        assertEquals(plain, ProviderLinks.decodeChecked("happ://add/" + plain)!!.data)
        assertFalse(ProviderLinks.decodeChecked("incy://import/https%3A%2F%2Fexample.test%2F%3Fa%3Db%2Bc")!!.encrypted)
        assertEquals("https://example.test/?a=b+c", ProviderLinks.decodeChecked("incy://import/https%3A%2F%2Fexample.test%2F%3Fa%3Db%2Bc")!!.data)
        val input = fixtures.getAsJsonArray("links").first { it.asJsonObject.get("name").asString == "official-incy-vector" }.asJsonObject.get("link").asString
        val wrapped = "happ://add/" + java.net.URLEncoder.encode(input, "UTF-8")
        assertTrue(ProviderLinks.isEncrypted(wrapped))
        assertEquals("https://sub.example.com/test-vector", ProviderLinks.subscription(wrapped)!!.data)
        assertEquals(plain, SubscriptionCrypto.decryptResponse("https://example.test/?key=key01", plain, ""))
        assertNull(ProviderLinks.decode("https://example.test/sub"))
    }
}
