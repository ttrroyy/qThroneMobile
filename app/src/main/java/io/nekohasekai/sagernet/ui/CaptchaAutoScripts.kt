package app.rawline.mobile.ui

/** Same checkbox auto-pass as the original Android client; a slider returns to the native fallback chain. */
internal object CaptchaAutoScripts {
    fun desktopUserAgent(engineUserAgent: String): String {
        val chrome = Regex("Chrome/[0-9.]+").find(engineUserAgent)?.value ?: "Chrome/146.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
    /** The original's pauses: page settle 650-1200 ms, then 420-680 ms before the touch. */
    fun clickDelayMs(random: kotlin.random.Random = kotlin.random.Random): Long =
        650L + random.nextLong(0, 550) + 420L + random.nextLong(0, 260)
    /** "manual" for a slider or kaleidoscope, else the checkbox label as [left, top, width, height, innerWidth]. */
    val checkbox = """(() => {
        if(document.querySelector('[class*="SliderCaptcha"], [class*="Kaleidoscope"]')) return 'manual';
        const el=document.querySelector('label.vkc__Checkbox-module__Checkbox') || document.querySelector('label[for="not-robot-captcha-checkbox"]') || document.getElementById('not-robot-captcha-checkbox');
        if(!el) return null;
        const r=el.getBoundingClientRect(), s=getComputedStyle(el);
        if(r.width<5 || r.height<5 || s.display==='none' || s.visibility==='hidden') return null;
        return [r.left,r.top,r.width,r.height,window.innerWidth];
    })();"""
    /** Watched for a while after the click, as the original does: a slider means the hidden attempt failed. */
    val sliderAfterClick = """(() => !!document.querySelector('[class*="SliderCaptcha"], [class*="Kaleidoscope"], .vkc__SwipeButton-module__track'))();"""
    val intercept = """(() => {
        if(window.__qthroneCaptchaHook) return; window.__qthroneCaptchaHook=true;
        const receive=text=>{try {
            const data=JSON.parse(text);
            const token=data.response?.success_token;
            if(typeof token==='string' && token.length>0) {
                window.__qthroneCaptchaToken=token;
                if(window.QThroneCaptcha) window.QThroneCaptcha.postMessage(token);
            } else {
                if(data.response?.status) window.__qthroneCaptchaStatus=String(data.response.status);
                if(data.response?.show_captcha_type==='slider') window.__qthroneCaptchaFailure='slider';
                else if(data.error) window.__qthroneCaptchaFailure='error';
            }
        }catch(e){}};
        const fetchOriginal=window.fetch;
        const isCheck=u=>String(u||'').includes('captchaNotRobot.check');
        window.fetch=async function(...args){const input=args[0];if(isCheck(typeof input==='string'?input:(input&&input.url)))window.__qthroneCheckSent=true;const response=await fetchOriginal.apply(this,args);if(response.url.includes('captchaNotRobot.check')) response.clone().text().then(receive);return response;};
        const sendOriginal=XMLHttpRequest.prototype.send;
        const openOriginal=XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open=function(method,url,...rest){this.__qthroneUrl=String(url||'');return openOriginal.call(this,method,url,...rest);};
        XMLHttpRequest.prototype.send=function(...args){if(isCheck(this.__qthroneUrl))window.__qthroneCheckSent=true;this.addEventListener('load',()=>{if(this.responseURL.includes('captchaNotRobot.check') && (!this.responseType || this.responseType==='text')) receive(this.responseText);});return sendOriginal.apply(this,args);};
        // VK's own close cross ends the request, like the original manual window.
        document.addEventListener('click',e=>{if(e.target.closest && e.target.closest('.vkc__ModalCardBase-module__dismiss')) window.__qthroneCaptchaDismissed=true;},true);
    })();"""
    val state = "JSON.stringify({token:window.__qthroneCaptchaToken||null,failure:window.__qthroneCaptchaFailure||null,dismissed:!!window.__qthroneCaptchaDismissed,checkSent:!!window.__qthroneCheckSent,status:window.__qthroneCaptchaStatus||''})"

    /** WDTT Plus's pauses: page settle 900-1700 ms, then 600-1300 ms before the touch. */
    fun wdttPlusSettleMs(random: kotlin.random.Random = kotlin.random.Random) = 900L + random.nextLong(0, 800)
    fun wdttPlusThinkMs(random: kotlin.random.Random = kotlin.random.Random) = 600L + random.nextLong(0, 700)

    /** WDTT Plus aims at the checkbox square first: [kind, left, top, width, height, innerWidth], or "manual". */
    val wdttPlusCheckbox = """(() => {
        if(document.querySelector('[class*="SliderCaptcha"], [class*="Kaleidoscope"], .vkc__SwipeButton-module__track, [class*="SwipeButton"]')) return 'manual';
        const visible=n=>{if(!n) return null;const r=n.getBoundingClientRect(),s=getComputedStyle(n);return (r.width<5||r.height<5||s.display==='none'||s.visibility==='hidden')?null:r;};
        const el=document.querySelector('label.vkc__Checkbox-module__Checkbox') || document.querySelector('label[for="not-robot-captcha-checkbox"]') || document.getElementById('not-robot-captcha-checkbox');
        if(!el) return null;
        const icon=visible(el.querySelector && el.querySelector('[class*="Checkbox__iconBlock"], [class*="iconBlock"], [class*="IconBlock"]'));
        if(icon) return ['icon',icon.left,icon.top,icon.width,icon.height,window.innerWidth];
        const r=visible(el);
        return r?['label',r.left,r.top,r.width,r.height,window.innerWidth]:null;
    })();"""
    val wdttPlusSliderAfterClick = """(() => !!document.querySelector('[class*="SliderCaptcha"], [class*="Kaleidoscope"], .vkc__SliderCaptcha-module__description, .vkc__KaleidoscopeScreen-module__captchaId, .vkc__SwipeButton-module__track'))();"""
}
