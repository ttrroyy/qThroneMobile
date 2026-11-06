package app.rawline.mobile.outbound.link

import app.rawline.mobile.outbound.link.encryptCsqttPayload
import app.rawline.mobile.outbound.link.formatCsqttLink
import app.rawline.mobile.outbound.link.parseCsqttLink
import app.rawline.mobile.outbound.link.peerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttLinkTest {
    private fun enc(plain: String): String = "csqtt://" + encryptCsqttPayload(plain)

    @Test
    fun parsesV2WithoutHashes() {
        val link = parseCsqttLink(enc("connect?v=2&host=203.0.113.7&peer=46000&password=p%40ss"))
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("p@ss", link.password)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun parsesV2ConcatenatedParameters() {
        val link = parseCsqttLink(enc("connect?v=2host=203.0.113.88peer=46000password=dummy_secret_pass"))
        requireNotNull(link)
        assertEquals("203.0.113.88", link.host)
        assertEquals(46000, link.port)
        assertEquals("dummy_secret_pass", link.password)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun parsesV2WithSixPlusSeparatedHashes() {
        val hashes = (1..6).map { "abcdefghijklmnop$it" }
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&hashes=${hashes.joinToString("+")}"),
        )
        requireNotNull(link)
        assertEquals(hashes, link.hashes)
    }

    @Test
    fun parsesV2WithVkToken() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc-_%2B9"),
        )
        requireNotNull(link)
        assertEquals("vk1.aBc-_+9", link.vkAccessToken)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun preservesEncodedPlusInsideHash() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&hashes=abcdefghijklmno%2Bp"),
        )
        requireNotNull(link)
        assertEquals(listOf("abcdefghijklmno+p"), link.hashes)
    }

    @Test
    fun formatsAndParsesCleanV2LinkWithPassword() {
        val formatted = formatCsqttLink("203.0.113.7", 46000, "SecretPass123456", listOf("abcdefghijklmnop1"))
        val link = parseCsqttLink(formatted)
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("SecretPass123456", link.password)
        assertEquals(listOf("abcdefghijklmnop1"), link.hashes)
    }

    @Test
    fun rejectsLegacyUnencryptedLinks() {
        assertNull(parseCsqttLink("csqtt://secret@203.0.113.7:46000"))
        assertNull(parseCsqttLink("csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=secret"))
        val rawPayload = "connect?v=2&host=203.0.113.7&peer=46000&password=secret"
        val base64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rawPayload.toByteArray(Charsets.UTF_8))
        assertNull(parseCsqttLink("csqtt://$base64"))
    }

    @Test
    fun formatsIpv6PeerWithBrackets() {
        val link = parseCsqttLink(enc("connect?v=2&host=2001%3Adb8%3A%3A1&peer=46000&password=secret"))
        requireNotNull(link)
        assertEquals("[2001:db8::1]:46000", link.peerAddress())
    }

    @Test
    fun rejectsMalformedV2Links() {
        assertNull(parseCsqttLink(enc("connect?v=3&host=1.2.3.4&peer=46000&password=secret")))
        assertNull(parseCsqttLink(enc("connect?v=2&host=1.2.3.4&peer=0&password=secret")))
        assertNull(parseCsqttLink(enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=")))
        assertNull(parseCsqttLink(enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=short")))
        assertNull(parseCsqttLink(enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&token=bad%20token")))
        assertNull(parseCsqttLink(enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&token=one&token=two")))
        assertNull(
            parseCsqttLink(
                enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=abcdefghijklmnop&token=token"),
            ),
        )
        assertNull(
            parseCsqttLink(
                enc("connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=" +
                    (1..7).joinToString("+") { "abcdefghijklmnop$it" }),
            ),
        )
    }

    @Test
    fun ignoresWorkersInOldLinks() {
        val link = parseCsqttLink(enc("connect?v=2&host=203.0.113.7&peer=46000&workers=36"))
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)

        val malformed = parseCsqttLink(enc("connect?v=2&host=203.0.113.7&peer=46000&workers=not-a-number"))
        requireNotNull(malformed)
        assertEquals("203.0.113.7", malformed.host)
    }

    @Test
    fun parsesEncryptedLink() {
        val rawPayload = "connect?v=2&host=203.0.113.7&peer=46000&workers=36"
        val encryptedBase64 = encryptCsqttPayload(rawPayload)
        val encryptedLink = "csqtt://$encryptedBase64"
        val link = parseCsqttLink(encryptedLink)
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
    }

    @Test
    fun parsesRustEncryptedLink() {
        val rustLink = "csqtt://GNANVH0fhD6MisDFiWP_P0Bm_FHr-frawgfwur3Zx4ipjJx8gk-M6ZZbnDr_2clwPmzO-QS8AkiHLaMXyYO1RHyPeehXPqTc6jJi_iw72fRQJ9AR918ne5XsBRg"
        val link = parseCsqttLink(rustLink)
        requireNotNull(link)
        assertEquals("1.2.3.4", link.host)
        assertEquals(46000, link.port)
    }

    @Test
    fun deterministicLinkEncryption() {
        val rawPayload = "connect?v=2&host=203.0.113.7&peer=46000"
        val enc1 = encryptCsqttPayload(rawPayload)
        val enc2 = encryptCsqttPayload(rawPayload)
        assertEquals(enc1, enc2)
    }

    @Test
    fun parsesV2OwnerLinkWithoutPassword() {
        val link = parseCsqttLink(enc("connect?v=2&host=203.0.113.7&peer=46000"))
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("", link.password)
    }

    @Test
    fun parsesV2WithHashesWithoutPassword() {
        val link = parseCsqttLink(enc("connect?v=2&host=203.0.113.7&peer=46000&hashes=abcdefghijklmnop1"))
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("", link.password)
        assertEquals(listOf("abcdefghijklmnop1"), link.hashes)
    }

    @Test
    fun tamperedEncryptedLinkFailsToDecrypt() {
        val validEncrypted = encryptCsqttPayload("connect?v=2&host=203.0.113.7&peer=46000")
        val decoded = java.util.Base64.getUrlDecoder().decode(validEncrypted)
        decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 1).toByte()
        val tamperedBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
        assertNull(parseCsqttLink("csqtt://$tamperedBase64"))
    }

    @Test
    fun truncatedEncryptedLinkFailsToDecrypt() {
        assertNull(parseCsqttLink("csqtt://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))))
    }
}
