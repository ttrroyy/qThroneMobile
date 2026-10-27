package main

// The provider's check (Yandex SmartCaptcha) as the original OpenFlux apps answer it: the page
// opens in a browser, and the cookies it leaves are handed to the transport, which reconnects.
// The answer comes from the app on Android (captcha_android.go) and from a private browser
// window on desktops (captcha_desktop.go).

import (
	"fmt"
	"net/url"
	"strings"
	"time"
)

// captchaEngine is what the running client exposes of its pending check.
type captchaEngine interface {
	pendingCaptcha() string
	submitCaptcha(cookies string) error
	cancelCaptcha()
}

// captchaSnooze is how long a declined check stays quiet before it is asked again.
const captchaSnooze = 2 * time.Minute

// captchaAddress keeps only a real HTTPS page of the provider.
func captchaAddress(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || (u.Port() != "" && u.Port() != "443") {
		return false
	}
	host := strings.ToLower(u.Hostname())
	for _, domain := range []string{"yandex.ru", "yandex.com", "yandex.net", "ya.ru", "mail.ru"} {
		if host == domain || strings.HasSuffix(host, "."+domain) {
			return true
		}
	}
	return false
}

// captchaCheckpoint is a page that is still the check or a sign-in, not the document behind it.
func captchaCheckpoint(raw string) bool {
	return strings.Contains(raw, "showcaptcha") || strings.Contains(raw, "passport.yandex") || strings.Contains(raw, "/captcha")
}

// watchCaptcha answers one check at a time until stop closes. Without [interactive] nobody is
// there to pass it (a background test): the check is reported once and the client keeps retrying.
func watchCaptcha(stop <-chan struct{}, engine captchaEngine, interactive bool, solve func(string) (string, error)) {
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	var asked string
	var quietUntil time.Time
	for {
		select {
		case <-stop:
			return
		case <-tick.C:
		}
		page := engine.pendingCaptcha()
		if page == "" {
			asked = ""
			continue
		}
		if page == asked || time.Now().Before(quietUntil) {
			continue
		}
		asked = page
		if !captchaAddress(page) {
			fmt.Println("[OpenFlux] сервис запросил проверку на странице, которую клиент не открывает")
			continue
		}
		if !interactive {
			fmt.Println("[OpenFlux] сервис запросил проверку «я не робот»; подключите профиль, чтобы пройти её")
			continue
		}
		fmt.Println("[OpenFlux] сервис запросил проверку «я не робот»")
		cookies, err := solve(page)
		select {
		case <-stop:
			return
		default:
		}
		if err != nil || cookies == "" {
			engine.cancelCaptcha()
			quietUntil = time.Now().Add(captchaSnooze)
			fmt.Println("[OpenFlux] проверка не пройдена, следующая попытка позже")
			continue
		}
		if err := engine.submitCaptcha(cookies); err != nil {
			fmt.Printf("[OpenFlux] не удалось передать результат проверки: %v\n", err)
			continue
		}
		// A later check of the same page is a new one.
		asked = ""
		fmt.Println("[OpenFlux] проверка пройдена, переподключаюсь")
	}
}
