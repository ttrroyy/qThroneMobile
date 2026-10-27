// Command qthrone-openfluxkey runs one profile of the wlruscfd/openflux-server fork for qThrone.
//
// The fork's exit nodes speak their own framing (batched Boards, nodetunnel), so its profiles,
// the openflux://import links its control plane issues, run through the fork's own bridge and
// not the original OpenFlux client. The SOCKS5 listener qThrone dials is on loopback with the
// per-launch credentials and opens only once the transport is up.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	mobile "github.com/p1neappleXpress/OpenFlux/bridge"
)

type config struct {
	Transport string   `json:"transport"`
	DocURL    string   `json:"doc_url"`
	DocURLs   []string `json:"doc_urls,omitempty"`
	KeyToken  string   `json:"key_token,omitempty"`
	E2E       bool     `json:"e2e_encryption,omitempty"`
	MTU       int      `json:"mtu,omitempty"`
	SOCKS     string   `json:"socks"`
	SOCKSUser string   `json:"socks_user"`
	SOCKSPass string   `json:"socks_pass"`
	// Tests pass false: nobody is there to answer the provider's check.
	InteractiveCaptcha *bool `json:"interactive_captcha"`
	LatencyProbe       bool  `json:"latency_probe"`
}

// sessionCaptcha is the fork transport's pending check.
type sessionCaptcha struct{ session *mobile.QThroneSession }

func (c sessionCaptcha) pendingCaptcha() string             { return c.session.PendingCaptcha() }
func (c sessionCaptcha) submitCaptcha(cookies string) error { return c.session.SubmitCookies(cookies) }

// cancelCaptcha: the transport keeps retrying on its own cooldown; watchCaptcha stays quiet meanwhile.
func (sessionCaptcha) cancelCaptcha() {}

func run() int {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		fmt.Fprintln(os.Stderr, "OpenFlux: expected private configuration file")
		return 2
	}
	data, err := os.ReadFile(os.Args[2])
	var c config
	if err != nil || len(data) > 65536 || json.Unmarshal(data, &c) != nil {
		fmt.Fprintln(os.Stderr, "OpenFlux: invalid configuration")
		return 2
	}
	if host, _, err := net.SplitHostPort(c.SOCKS); err != nil || host != "127.0.0.1" {
		fmt.Fprintln(os.Stderr, "OpenFlux: listener must be IPv4 loopback")
		return 2
	}
	if len(c.SOCKSUser) < 1 || len(c.SOCKSUser) > 255 || len(c.SOCKSPass) < 1 || len(c.SOCKSPass) > 255 {
		fmt.Fprintln(os.Stderr, "OpenFlux: authenticated bridge required")
		return 2
	}
	if c.DocURL == "" && len(c.DocURLs) == 0 {
		fmt.Fprintln(os.Stderr, "OpenFlux: document link required")
		return 2
	}
	engine, _ := json.Marshal(map[string]any{"mode": "manual", "transport": c.Transport, "doc_url": c.DocURL, "doc_urls": c.DocURLs,
		"key_token": c.KeyToken, "e2e_encryption": c.E2E && c.KeyToken != "", "mtu": c.MTU})
	session, err := mobile.StartQThrone(string(engine))
	if err != nil {
		fmt.Fprintf(os.Stderr, "OpenFlux: %v\n", err)
		return 1
	}
	defer func() {
		done := make(chan struct{})
		go func() { session.Stop(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}()

	stop := make(chan struct{})
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 1<<20))
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == "STOP" {
				break
			}
			if value, ok := strings.CutPrefix(line, "CAPTCHA_RESULT|"); ok {
				deliverCaptcha(value)
			}
		}
		close(stop)
	}()
	interactive := (c.InteractiveCaptcha == nil || *c.InteractiveCaptcha) && !c.LatencyProbe
	go watchCaptcha(stop, sessionCaptcha{session}, captchaInteractive(interactive), solveCaptcha)

	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for !session.Connected() {
		select {
		case <-stop:
			return 0
		case <-tick.C:
		}
	}
	if err := session.Listen(c.SOCKS, c.SOCKSUser, c.SOCKSPass); err != nil {
		fmt.Fprintln(os.Stderr, "OpenFlux: cannot open the local listener")
		return 1
	}
	fmt.Println("[OpenFlux] READY")
	<-stop
	return 0
}

func main() { os.Exit(run()) }
