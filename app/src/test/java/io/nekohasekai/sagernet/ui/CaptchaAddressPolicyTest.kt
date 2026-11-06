package app.rawline.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class CaptchaAddressPolicyTest {
    @Test fun acceptsProviderHttpsAddresses() {
        for(address in listOf("https://vk.com/captcha?session=fixture","https://id.vk.ru/captcha","https://ok.ru/captcha","https://captcha.okcdn.ru:443/check")) assertTrue(address,CaptchaAddressPolicy.allowed(address))
    }
    @Test fun rejectsOtherOriginsAndLocalResources() {
        for(address in listOf("about:blank","file:///data/private","http://vk.com/captcha","https://vk.com.evil.test/","https://evilvk.com/","https://vk.com@evil.test/","https://user@vk.com/","https://vk.com:8080/","https://127.0.0.1/","javascript:alert(1)","https://vk%2ecom/","not a URL")) assertFalse(address,CaptchaAddressPolicy.allowed(address))
    }
}
