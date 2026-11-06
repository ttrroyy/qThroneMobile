package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class CarrierLogRedactorTest {
    private val redactor = CarrierLogRedactor(
        """{"password":"private-pass","hashes":["private-hash"],"username":"ab"}""",
        CarrierBridgeCredentials("bridge-user", "bridge-pass"))

    @Test fun removesUnlabelledSecretsAndKeepsDiagnosticContext() {
        val line = redactor.redact("Connected bridge-user bridge-pass private-pass private-hash ab; stable transport")!!
        for (secret in listOf("bridge-user", "bridge-pass", "private-pass", "private-hash")) assertFalse(line.contains(secret))
        assertFalse(line.contains(" ab;"))
        assertTrue(line.contains("stable transport"))
    }
    @Test fun neverPersistsCaptchaExchange() {
        assertNull(redactor.redact("CAPTCHA_SOLVE|1|https://vk.com/private-token"))
        assertNull(redactor.redact("CAPTCHA_RESULT|private-token"))
    }
    @Test fun removesHeadersAndPrivateUrlPayloads() {
        assertFalse(redactor.redact("Cookie: session=private-value")!!.contains("private-value"))
        assertFalse(redactor.redact("https://vk.com/captcha?token=private-value")!!.contains("private-value"))
    }
}
