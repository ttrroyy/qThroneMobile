// Command rawline-dnstun runs one DNS tunnel profile (DNSTT, VayDNS or
// Slipstream) behind an authenticated loopback SOCKS5 listener for
// RawlineMobile.
//
// The tunnel carries TCP streams to the server's backend, as in every client
// of the dnst:// format: a SOCKS5 proxy, an SSH server or a Shadowsocks
// server. The backend is spoken here, per stream, so the app only ever sees an
// authenticated SOCKS5 proxy on loopback and other apps cannot use the tunnel.
// DNSTT and VayDNS run through VayDNS's client library (DNSTT in its wire
// compatibility mode); Slipstream runs as a child process listening on a Unix
// socket in the app's private directory.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"time"
)

type backendConfig struct {
	Type     string `json:"type"`
	User     string `json:"user"`
	Password string `json:"password"`
	PK       string `json:"pk"`
	Method   string `json:"method"`
}

type config struct {
	Type            string        `json:"type"`
	Domain          string        `json:"domain"`
	Pubkey          string        `json:"pubkey"`
	RecordType      string        `json:"record_type"`
	DnsttCompat     bool          `json:"dnstt_compat"`
	ClientIDSize    int           `json:"clientid_size"`
	IdleTimeout     string        `json:"idle_timeout"`
	Keepalive       string        `json:"keepalive"`
	Cert            string        `json:"cert"`
	Congestion      string        `json:"congestion_control"`
	KeepAliveMillis int           `json:"keep_alive_interval"`
	Authoritative   bool          `json:"authoritative"`
	Resolvers       []string      `json:"resolvers"`
	SystemResolvers []string      `json:"system_resolvers"`
	Backend         backendConfig `json:"backend"`
	SOCKS           string        `json:"socks"`
	SOCKSUser       string        `json:"socks_user"`
	SOCKSPass       string        `json:"socks_pass"`
}

// fallbackResolvers are used when the profile names none and the network's
// own resolvers are unknown.
var fallbackResolvers = []string{"8.8.8.8:53", "1.1.1.1:53"}

func readConfig(path string) (config, error) {
	var c config
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 1<<20 {
		return c, errors.New("cannot read private configuration")
	}
	if err := json.Unmarshal(data, &c); err != nil {
		return c, errors.New("invalid configuration")
	}
	switch c.Type {
	case "dnstt", "vaydns", "slipstream":
	default:
		return c, fmt.Errorf("unsupported transport %q", c.Type)
	}
	c.Domain = strings.TrimSuffix(strings.TrimSpace(c.Domain), ".")
	if c.Domain == "" {
		return c, errors.New("tunnel domain required")
	}
	host, _, err := net.SplitHostPort(c.SOCKS)
	if err != nil || host != "127.0.0.1" {
		return c, errors.New("listener must be IPv4 loopback")
	}
	if len(c.SOCKSUser) < 1 || len(c.SOCKSUser) > 255 || len(c.SOCKSPass) < 1 || len(c.SOCKSPass) > 255 {
		return c, errors.New("authenticated bridge required")
	}
	switch c.Backend.Type {
	case "", "socks", "ssh", "shadowsocks":
	case "custom":
		return c, errors.New("the custom backend forwards one fixed service; use a SOCKS, SSH or Shadowsocks backend")
	default:
		return c, fmt.Errorf("unsupported backend %q", c.Backend.Type)
	}
	return c, nil
}

func (c config) resolverList() []string {
	for _, list := range [][]string{c.Resolvers, c.SystemResolvers} {
		var out []string
		for _, r := range list {
			if r = strings.TrimSpace(r); r != "" {
				out = append(out, r)
			}
		}
		if len(out) > 0 {
			return out
		}
	}
	return fallbackResolvers
}

// streamSource opens one stream to the server's backend.
type streamSource interface {
	Open(ctx context.Context) (net.Conn, error)
	Close() error
}

func run() int {
	log.SetFlags(log.Ltime)
	if len(os.Args) != 3 || os.Args[1] != "--rawline-config" {
		fmt.Fprintln(os.Stderr, "DNS tunnel: expected private configuration file")
		return 2
	}
	c, err := readConfig(os.Args[2])
	if err != nil {
		fmt.Fprintf(os.Stderr, "DNS tunnel: %v\n", err)
		return 2
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 4096))
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		cancel()
	}()

	var source streamSource
	if c.Type == "slipstream" {
		source, err = startSlipstream(ctx, c, os.Args[2])
	} else {
		source, err = newDNSTunnel(c)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "DNS tunnel: %v\n", err)
		return 1
	}
	defer source.Close()
	dialer, err := newBackend(c.Backend, source)
	if err != nil {
		fmt.Fprintf(os.Stderr, "DNS tunnel: %v\n", err)
		return 1
	}

	// Ready means a stream reached the server; the listener opens only then.
	for attempt := 1; ; attempt++ {
		probeCtx, probeCancel := context.WithTimeout(ctx, 30*time.Second)
		err = dialer.Check(probeCtx)
		probeCancel()
		if err == nil {
			break
		}
		if ctx.Err() != nil {
			return 0
		}
		log.Printf("[DNS] tunnel not ready (attempt %d): %v", attempt, err)
		select {
		case <-ctx.Done():
			return 0
		case <-time.After(time.Duration(min(attempt, 5)) * time.Second):
		}
	}
	listener, err := net.Listen("tcp4", c.SOCKS)
	if err != nil {
		fmt.Fprintln(os.Stderr, "DNS tunnel: cannot open the local listener")
		return 1
	}
	go func() { <-ctx.Done(); listener.Close() }()
	fmt.Println("[DNS] READY")
	serveSOCKS(ctx, listener, c.SOCKSUser, c.SOCKSPass, dialer.Dial)
	return 0
}

func main() { os.Exit(run()) }
