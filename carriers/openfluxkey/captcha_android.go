//go:build android

package main

import (
	"errors"
	"fmt"
	"strings"
)

// captchaResults carries the app's CAPTCHA_RESULT lines read from stdin.
var captchaResults = make(chan string, 1)

// deliverCaptcha passes one CAPTCHA_RESULT value on; a value nobody waits for is dropped.
func deliverCaptcha(value string) {
	select {
	case captchaResults <- value:
	default:
	}
}

// solveCaptcha asks the app, which shows the page in its own window and answers with the
// page's cookies or error:<reason>. The app answers every request, also when it cannot show one.
func solveCaptcha(page string) (string, error) {
	select {
	case <-captchaResults:
	default:
	}
	fmt.Println("CAPTCHA_SOLVE|cookies|" + page + "|")
	result := <-captchaResults
	if strings.HasPrefix(result, "error:") {
		return "", errors.New(strings.TrimPrefix(result, "error:"))
	}
	return result, nil
}

// captchaInteractive: the app decides whether a window may be shown.
func captchaInteractive(bool) bool { return true }
