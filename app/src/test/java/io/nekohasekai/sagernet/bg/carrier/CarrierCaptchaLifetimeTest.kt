package app.rawline.mobile.bg.carrier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class CarrierCaptchaLifetimeTest {
    private fun fake(output: ByteArrayOutputStream)=object: Process() {
        override fun getOutputStream()=output
        override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
        override fun waitFor()=0
        override fun exitValue()=0
        override fun destroy() { }
    }
    @Test fun backgroundProbeRejectsCaptchaWithoutOpeningUi() {
        val output=ByteArrayOutputStream()
        val bridge=CarrierCaptchaBridge(fake(output),allowUi=false)
        bridge.accept("CAPTCHA_SOLVE|wv|https://vk.ru/captcha|secret")
        assertTrue(bridge.interactionRequired)
        assertFalse(bridge.waiting)
        assertEquals("CAPTCHA_RESULT|error:cancelled\n",output.toString("UTF-8"))
        bridge.close()
    }
    @Test fun closedBridgeIgnoresQueuedCaptchaWithoutReopeningUi() {
        val output=ByteArrayOutputStream()
        val process=object: Process() {
            override fun getOutputStream()=output
            override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
            override fun waitFor()=0
            override fun exitValue()=0
            override fun destroy() { }
        }
        val bridge=CarrierCaptchaBridge(process)
        bridge.close()
        bridge.accept("CAPTCHA_SOLVE|wv|https://vk.ru/captcha|secret")
        bridge.accept("VK_AUTH_REQUIRED|manual_hash_123456789")
        assertFalse(bridge.waiting)
        assertEquals(0,output.size())
    }
}
