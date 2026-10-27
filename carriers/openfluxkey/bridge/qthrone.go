package mobile

// Added by qThrone to the fork's bridge package: the SOCKS5 mode StartSocks5Proxy runs, with the
// transport this package builds for the fork's exit nodes, but behind username/password auth on a
// loopback listener the caller opens once the transport is up, and with the provider's check
// (EventCaptchaRequired) handed to the caller, which answers with the browser's cookies.

import (
	"encoding/json"
	"fmt"
	"sync"

	"github.com/p1neappleXpress/OpenFlux/nodetunnel"
	"github.com/p1neappleXpress/OpenFlux/socks5"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/yandex"
)

// QThroneSession is one running profile.
type QThroneSession struct {
	raw     transport.Transport
	trans   transport.Transport
	tun     *nodetunnel.TCPTunnel
	server  *socks5.SOCKS5Server
	mu      sync.Mutex
	captcha string
}

// StartQThrone starts the transport; Connected reports when it is up.
func StartQThrone(configJSON string) (*QThroneSession, error) {
	var cfg Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	cfg.Mode = "manual"
	// The Yandex carriers are built here as buildTransport builds them, keeping the raw transport:
	// the wrappers pass neither its events nor its cookies on.
	tc := transport.DefaultConfig()
	var raw, trans transport.Transport
	switch cfg.Transport {
	case "boards":
		if cfg.DocURL == "" {
			return nil, fmt.Errorf("doc_url is required")
		}
		boards := yandex.NewBoardsTransport(cfg.DocURL, tc)
		boards.SetCaptchaSolveMode(yandex.CaptchaSolveModeExternal)
		raw, trans = boards, wrapBoardsBatched(boards, cfg)
	case "", "yandex":
		if cfg.DocURL == "" {
			return nil, fmt.Errorf("doc_url is required")
		}
		yd := yandex.NewYandexDocsTransport(cfg.DocURL, tc)
		yd.SetCaptchaSolveMode(yandex.CaptchaSolveModeExternal)
		raw, trans = yd, wrapYandex(yd, cfg, -1)
	default:
		built, err := buildTransport(cfg, tc)
		if err != nil {
			return nil, err
		}
		raw, trans = built, built
	}
	s := &QThroneSession{raw: raw, trans: trans}
	transport.SetEventCallback(raw, func(code, detail string) {
		s.mu.Lock()
		defer s.mu.Unlock()
		switch code {
		case transport.EventCaptchaRequired:
			s.captcha = detail
		case transport.EventConnected:
			s.captcha = ""
		}
	})
	if err := trans.Start(); err != nil {
		return nil, fmt.Errorf("start transport: %w", err)
	}
	s.tun = nodetunnel.NewTCPTunnel(trans, false)
	s.tun.TuneFor(cfg.Transport)
	if cfg.MTU > 0 {
		s.tun.SetMTU(uint32(cfg.MTU))
	}
	return s, nil
}

func (s *QThroneSession) Connected() bool { return s.trans.IsConnected() }

// PendingCaptcha is the page the provider wants passed, "" when nothing is pending.
func (s *QThroneSession) PendingCaptcha() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.captcha
}

// SubmitCookies hands a Cookie header from the browser to the transport, which reconnects with it.
func (s *QThroneSession) SubmitCookies(header string) error {
	provider, ok := s.raw.(transport.CookieProvider)
	if !ok {
		return fmt.Errorf("this transport does not take cookies")
	}
	s.mu.Lock()
	s.captcha = ""
	s.mu.Unlock()
	provider.ProvideCookies(header)
	return nil
}

// Listen opens the authenticated SOCKS5 listener.
func (s *QThroneSession) Listen(addr, user, pass string) error {
	s.server = socks5.NewSOCKS5Server(addr, s.tun)
	s.server.SetAuth(user, pass)
	if err := s.server.Bind(); err != nil {
		return err
	}
	go func() { _ = s.server.Start() }()
	return nil
}

func (s *QThroneSession) Stop() {
	if s.server != nil {
		_ = s.server.Close()
	}
	_ = s.trans.Stop()
	s.tun.Close()
}
