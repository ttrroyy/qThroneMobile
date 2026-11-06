package app.rawline.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeCaptchaPolicyTest {
    @Test fun probesAllowAutomaticCaptchaAndNeverOpenManualRequests() {
        assertTrue(NativeCaptchaPolicy.automatic("CAPTCHA_SOLVE|auto|https://vk.ru/challenge|fixture"))
        for(line in listOf("CAPTCHA_SOLVE|manual|https://vk.ru/challenge|fixture",
            "CAPTCHA_SOLVE|selected|https://vk.ru/challenge|fixture", "VK_AUTH_REQUIRED|fixture", "CAPTCHA_SOLVE|auto"))
            assertFalse(NativeCaptchaPolicy.automatic(line))
        assertTrue(NativeCaptchaPolicy.permitted(false,false,true))
        assertFalse(NativeCaptchaPolicy.permitted(false,false,false))
        assertTrue(NativeCaptchaPolicy.permitted(true,false,false))
        assertFalse(NativeCaptchaPolicy.permitted(true,true,true))
        assertFalse(NativeCaptchaPolicy.permitted(false,true,true))
        assertEquals("error:timeout",NativeCaptchaPolicy.dismissal(true))
        assertEquals("error:cancelled",NativeCaptchaPolicy.dismissal(false))
        assertEquals(10_000L,NativeCaptchaPolicy.AUTOMATIC_TIMEOUT_MS)
        assertEquals(60_000L,NativeCaptchaPolicy.VISIBLE_TIMEOUT_MS)
    }
}
