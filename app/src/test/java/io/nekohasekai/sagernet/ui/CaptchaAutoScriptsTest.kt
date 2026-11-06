package app.rawline.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class CaptchaAutoScriptsTest {
    @Test fun desktopViewportUsesTheInstalledWebViewEngineVersion() {
        val agent=CaptchaAutoScripts.desktopUserAgent("Mozilla/5.0 (Linux; Android 14) Chrome/146.0.12.3 Mobile Safari/537.36")
        assertTrue(agent.contains("Chrome/146.0.12.3"))
        assertFalse(agent.contains("Android"))
        assertFalse(agent.contains("Mobile"))
    }
    @Test fun hiddenAttemptKeepsTheOriginalPacingAndSliderSignals() {
        repeat(200) { assertTrue(CaptchaAutoScripts.clickDelayMs() in 1_070L..1_879L) }
        assertTrue(CaptchaAutoScripts.intercept.contains("show_captcha_type==='slider'"))
        assertTrue(CaptchaAutoScripts.sliderAfterClick.contains("SwipeButton-module__track"))
        assertTrue(CaptchaAutoScripts.intercept.contains("ModalCardBase-module__dismiss"))
    }
}
