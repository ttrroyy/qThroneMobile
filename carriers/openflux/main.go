// Command qthrone-openflux runs one OpenFlux profile behind an authenticated
// loopback SOCKS5 listener for qThroneMobile.
//
// The profile is started through OpenFlux's own mobile package, the code the
// original Android app runs: Session links go to StartSessionProxy with the
// specs ShareSessionSpecs derives (so the encryption context is the one every
// OpenFlux client derives), classic links to StartProxy, phpbox links to
// StartStreamProxy. The listener qThrone dials opens only once the tunnel is
// up, so a profile is never reported ready before it can carry traffic.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/p1neappleXpress/OpenFlux/share"
	mobile "openflux-mobile"
)

type config struct {
	share.Config
	SOCKS     string `json:"socks"`
	SOCKSUser string `json:"socks_user"`
	SOCKSPass string `json:"socks_pass"`
	// Tests pass false: nobody is there to answer the provider's check.
	InteractiveCaptcha *bool `json:"interactive_captcha"`
	LatencyProbe       bool  `json:"latency_probe"`
}

// openfluxCaptcha is the mobile package's pending check: a real site's page the client itself
// opens. A script's own setup page and a check of the exit are not answered here.
type openfluxCaptcha struct{}

func (openfluxCaptcha) pendingCaptcha() string {
	if mobile.PendingCaptchaOwn() || mobile.PendingCaptchaProxy() != "" || mobile.PendingCaptchaHTML() != "" {
		return ""
	}
	return mobile.PendingCaptchaURL()
}

func (openfluxCaptcha) submitCaptcha(cookies string) error {
	if msg := mobile.SubmitCaptchaCookies(cookies); msg != "" {
		return fmt.Errorf("%s", msg)
	}
	return nil
}

func (openfluxCaptcha) cancelCaptcha() { mobile.CancelCaptcha() }

func readConfig(path string) (config, error) {
	var c config
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 65536 {
		return c, fmt.Errorf("cannot read private configuration")
	}
	if json.Unmarshal(data, &c) != nil {
		return c, fmt.Errorf("invalid configuration")
	}
	if err := c.Validate(); err != nil {
		return c, err
	}
	host, _, err := net.SplitHostPort(c.SOCKS)
	if err != nil || host != "127.0.0.1" {
		return c, fmt.Errorf("listener must be IPv4 loopback")
	}
	if len(c.SOCKSUser) < 1 || len(c.SOCKSUser) > 255 || len(c.SOCKSPass) < 1 || len(c.SOCKSPass) > 255 {
		return c, fmt.Errorf("authenticated bridge required")
	}
	return c, nil
}

// start runs the profile on listen and returns "" or a readable error.
func start(c config, listen string) string {
	switch {
	case c.Mode == share.ModeStream:
		t := c.Transports[0]
		return mobile.StartStreamProxy(t.Type, t.URL, listen, c.SOCKSUser, c.SOCKSPass, "")
	case c.Negotiate:
		link, err := share.Encode(c.Config)
		if err != nil {
			return err.Error()
		}
		specs, err := mobile.ShareSessionSpecs(link)
		if err != nil {
			return err.Error()
		}
		return mobile.StartSessionProxy(specs, c.Secret, listen, c.SOCKSUser, c.SOCKSPass, "")
	default:
		t := c.Transports[0]
		return mobile.StartProxy(t.Type, t.URL, c.Secret, c.Codec, "", "", listen, c.SOCKSUser, c.SOCKSPass, "")
	}
}

func freeLoopbackAddress() (string, error) {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer l.Close()
	return l.Addr().String(), nil
}

// forward relays the authenticated listener to the core's own one. The core
// checks the same credentials, so nothing reaches it unauthenticated.
func forward(listener net.Listener, target string) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			upstream, err := net.DialTimeout("tcp4", target, 5*time.Second)
			if err != nil {
				return
			}
			defer upstream.Close()
			var wg sync.WaitGroup
			wg.Add(1)
			go func() {
				defer wg.Done()
				_, _ = io.Copy(upstream, conn)
				if tcp, ok := upstream.(*net.TCPConn); ok {
					_ = tcp.CloseWrite()
				}
			}()
			_, _ = io.Copy(conn, upstream)
			if tcp, ok := conn.(*net.TCPConn); ok {
				_ = tcp.CloseWrite()
			}
			wg.Wait()
		}()
	}
}

func printLogs(lastCaptcha *string) {
	if logs := mobile.ReadLogs(); logs != "" {
		for _, line := range strings.Split(logs, "\n") {
			if line = strings.TrimSpace(line); line != "" {
				fmt.Println(line)
			}
		}
	}
	if pending := mobile.PendingCaptchaTransport(); pending != *lastCaptcha {
		*lastCaptcha = pending
		// Checks this client answers are reported by watchCaptcha; the rest only here.
		if pending != "" && (openfluxCaptcha{}).pendingCaptcha() == "" {
			fmt.Printf("[OpenFlux] %s: сервис запросил проверку, которую этот клиент не показывает\n", pending)
		}
	}
}

func run() int {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		fmt.Fprintln(os.Stderr, "OpenFlux: expected private configuration file")
		return 2
	}
	c, err := readConfig(os.Args[2])
	if err != nil {
		fmt.Fprintf(os.Stderr, "OpenFlux: %v\n", err)
		return 2
	}
	// Operational logs name documents and rooms and cost time per packet; the
	// app log keeps the mobile package's own status lines.
	mobile.SetDebugLevel(0)
	if cache := os.Getenv("XDG_CACHE_HOME"); cache != "" {
		folder := filepath.Join(cache, "openflux")
		if os.MkdirAll(folder, 0o700) == nil {
			_ = mobile.SetCookieStorePath(filepath.Join(folder, "cookies.json"))
		}
	}

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
	go watchCaptcha(stop, openfluxCaptcha{}, captchaInteractive(interactive), solveCaptcha)

	inner, err := freeLoopbackAddress()
	if err != nil {
		fmt.Fprintln(os.Stderr, "OpenFlux: no loopback port available")
		return 1
	}
	if msg := start(c, inner); msg != "" {
		fmt.Fprintf(os.Stderr, "OpenFlux: %s\n", msg)
		return 1
	}
	// Carriers leave their rooms on Stop; do not let a dead network hold the exit.
	defer func() {
		done := make(chan struct{})
		go func() { mobile.StopProxy(); close(done) }()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}()

	var captcha string
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for !mobile.ProxyIsConnected() {
		select {
		case <-stop:
			return 0
		case <-tick.C:
			printLogs(&captcha)
		}
	}
	listener, err := net.Listen("tcp4", c.SOCKS)
	if err != nil {
		fmt.Fprintln(os.Stderr, "OpenFlux: cannot open the local listener")
		return 1
	}
	defer listener.Close()
	go forward(listener, inner)
	fmt.Println("[OpenFlux] READY")

	logs := time.NewTicker(time.Second)
	defer logs.Stop()
	for {
		select {
		case <-stop:
			return 0
		case <-logs.C:
			printLogs(&captcha)
		}
	}
}

func main() { os.Exit(run()) }
