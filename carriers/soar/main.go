// Command qthrone-soar runs one Soar profile for qThroneMobile.
//
// It is Lantern's Soar client library (github.com/getlantern/soar) behind an
// authenticated loopback SOCKS5 listener with the per-launch credentials:
// every CONNECT becomes a Soar stream to the server named by the profile's
// zone and Ed25519 public key, through the profile's or the network's resolvers.
package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

// settings are the advanced Soar options of a WhiteDNS-style profile; zero keeps the default.
type settings struct {
	Transport            string `json:"transport"`
	Discovery            *bool  `json:"discovery"`
	Country              string `json:"country"`
	DiscoveryWant        int    `json:"discoveryWant"`
	DiscoveryConcurrency int    `json:"discoveryConcurrency"`
	DiscoveryPaceMs      int    `json:"discoveryPaceMs"`
	SafeQName            int    `json:"safeQName"`
	MaxQName             int    `json:"maxQName"`
	MaxAnswer            int    `json:"maxAnswer"`
	HoldMs               int    `json:"holdMs"`
	MaxInflight          int    `json:"maxInflight"`
	MaxPathWindow        int    `json:"maxPathWindow"`
	MaxStreams           int    `json:"maxStreams"`
	StreamWindowKiB      int    `json:"streamWindowKiB"`
	SendBufferKiB        int    `json:"sendBufferKiB"`
	SessionIdleSeconds   int    `json:"sessionIdleSeconds"`
	DialTimeoutSeconds   int    `json:"dialTimeoutSeconds"`
}

type profile struct {
	Domain          string   `json:"domain"`
	ServerKey       string   `json:"server_public_key"`
	Resolvers       []string `json:"resolvers"`
	SystemResolvers []string `json:"system_resolvers"`
	Soar            settings `json:"soar"`
	SOCKS           string   `json:"socks"`
	SOCKSUser       string   `json:"socks_user"`
	SOCKSPass       string   `json:"socks_pass"`
}

// resolverAddresses keeps IP or IP:port resolvers as Soar wants them, port 53 by default.
func resolverAddresses(list []string) []string {
	var out []string
	for _, r := range list {
		r = strings.TrimPrefix(strings.TrimSpace(r), "udp://")
		if address, err := netip.ParseAddr(r); err == nil {
			out = append(out, netip.AddrPortFrom(address, 53).String())
		} else if address, err := netip.ParseAddrPort(r); err == nil && address.Port() != 0 {
			out = append(out, address.String())
		}
	}
	return out
}

func newClient(p profile) (*soar.Client, time.Duration, error) {
	key, err := base64.StdEncoding.DecodeString(strings.TrimSpace(p.ServerKey))
	if err != nil || len(key) != ed25519.PublicKeySize {
		return nil, 0, errors.New("server public key must be a base64 32-byte Ed25519 key")
	}
	resolvers := resolverAddresses(p.Resolvers)
	if len(resolvers) == 0 {
		resolvers = resolverAddresses(p.SystemResolvers)
	}
	s := p.Soar
	cfg := soar.Config{
		Zone:          strings.TrimSuffix(strings.TrimSpace(p.Domain), "."),
		ServerKey:     ed25519.PublicKey(key),
		Resolvers:     resolvers,
		SafeQName:     s.SafeQName,
		MaxQName:      s.MaxQName,
		MaxAnswer:     s.MaxAnswer,
		MaxInflight:   s.MaxInflight,
		MaxPathWindow: s.MaxPathWindow,
		MaxStreams:    s.MaxStreams,
		StreamWindow:  uint64(s.StreamWindowKiB) * 1024,
		SendBuffer:    s.SendBufferKiB * 1024,
		Hold:          time.Duration(s.HoldMs) * time.Millisecond,
		SessionIdle:   time.Duration(s.SessionIdleSeconds) * time.Second,
		TCP:           s.Transport,
		Logger:        slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn})),
	}
	// Discovery of in-country resolvers is on unless the profile turns it off, as in the original app.
	cfg.Discovery = soar.Discovery{Enabled: s.Discovery == nil || *s.Discovery, Country: strings.ToUpper(s.Country),
		Want: s.DiscoveryWant, Concurrency: s.DiscoveryConcurrency, Pace: time.Duration(s.DiscoveryPaceMs) * time.Millisecond}
	if s.Discovery == nil && cfg.Discovery.Country == "" {
		cfg.Discovery.Country = "IR"
	}
	if cfg.Discovery.Enabled && cfg.Discovery.Want == 0 {
		cfg.Discovery.Want, cfg.Discovery.Concurrency, cfg.Discovery.Pace = 2, 2, time.Second
	}
	if len(cfg.Resolvers) == 0 && !cfg.Discovery.Enabled {
		cfg.Resolvers = []string{"1.1.1.1:53", "8.8.8.8:53"}
	}
	client, err := soar.NewClient(cfg)
	dialTimeout := time.Duration(s.DialTimeoutSeconds) * time.Second
	if dialTimeout <= 0 {
		dialTimeout = 90 * time.Second
	}
	return client, dialTimeout, err
}

func run() int {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		fmt.Fprintln(os.Stderr, "Soar: expected private configuration file")
		return 2
	}
	data, err := os.ReadFile(os.Args[2])
	var p profile
	if err != nil || len(data) > 1<<20 || json.Unmarshal(data, &p) != nil {
		fmt.Fprintln(os.Stderr, "Soar: invalid configuration")
		return 2
	}
	host, port, err := net.SplitHostPort(p.SOCKS)
	listenPort, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || listenPort < 1 || listenPort > 65535 {
		fmt.Fprintln(os.Stderr, "Soar: listener must be IPv4 loopback")
		return 2
	}
	if len(p.SOCKSUser) < 1 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) < 1 || len(p.SOCKSPass) > 255 {
		fmt.Fprintln(os.Stderr, "Soar: authenticated bridge required")
		return 2
	}
	client, dialTimeout, err := newClient(p)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Soar: %v\n", err)
		return 1
	}
	defer client.Close()
	listener, err := net.Listen("tcp", p.SOCKS)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Soar: %v\n", err)
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 4096))
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		stop()
	}()
	context.AfterFunc(ctx, func() { _ = listener.Close() })
	serveSOCKS(ctx, listener, p.SOCKSUser, p.SOCKSPass, dialTimeout, func(ctx context.Context, target string) (net.Conn, error) {
		return client.DialContext(ctx, "tcp", target)
	})
	return 0
}

func main() { os.Exit(run()) }
