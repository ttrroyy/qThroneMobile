// Command qthrone-sstp runs one SSTP VPN profile behind an authenticated
// loopback SOCKS5 listener for qThroneMobile.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"swiftsstp/tunnel"
)

type profile struct {
	Server        string `json:"server"`
	ServerPort    int    `json:"server_port"`
	Username      string `json:"username"`
	Password      string `json:"password"`
	SNI           string `json:"sni"`
	AllowInsecure bool   `json:"allow_insecure"`
	UseServerDNS  bool   `json:"use_server_dns"`

	SOCKS     string `json:"socks"`
	SOCKSUser string `json:"socks_user"`
	SOCKSPass string `json:"socks_pass"`
}

func readConfig(path string) (profile, error) {
	var p profile
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 65536 {
		return p, errors.New("cannot read private configuration")
	}
	if err := json.Unmarshal(data, &p); err != nil {
		return p, errors.New("invalid JSON configuration")
	}

	p.Server = strings.TrimSpace(p.Server)
	for _, prefix := range []string{"https://", "http://", "sstp://"} {
		if strings.HasPrefix(strings.ToLower(p.Server), prefix) {
			p.Server = p.Server[len(prefix):]
			break
		}
	}
	if slash := strings.Index(p.Server, "/"); slash != -1 {
		p.Server = p.Server[:slash]
	}
	if host, portStr, err := net.SplitHostPort(p.Server); err == nil {
		p.Server = host
		if port, err := strconv.Atoi(portStr); err == nil && port > 0 && port <= 65535 {
			p.ServerPort = port
		}
	}

	if p.Server == "" {
		return p, errors.New("SSTP server address is required")
	}
	if p.ServerPort <= 0 || p.ServerPort > 65535 {
		p.ServerPort = 443
	}
	p.Username = strings.TrimSpace(p.Username)
	if p.Username == "" {
		return p, errors.New("SSTP username is required")
	}

	host, portStr, err := net.SplitHostPort(p.SOCKS)
	listenPort, _ := strconv.Atoi(portStr)
	if err != nil || host != "127.0.0.1" || listenPort < 1 || listenPort > 65535 {
		return p, errors.New("SOCKS listener must be on IPv4 loopback (127.0.0.1:port)")
	}
	if len(p.SOCKSUser) < 1 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) < 1 || len(p.SOCKSPass) > 255 {
		return p, errors.New("SOCKS authentication credentials are required (1..255 bytes)")
	}
	return p, nil
}

func main() {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		fmt.Fprintln(os.Stderr, "Usage: libsstp.so --qthrone-config <config.json>")
		os.Exit(2)
	}

	p, err := readConfig(os.Args[2])
	if err != nil {
		fmt.Fprintf(os.Stderr, "FATAL_SSTP: %v\n", err)
		os.Exit(2)
	}

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)

	// Read "STOP" from stdin if sent by qThroneMobile CarrierProcess, or handle EOF
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				sig <- syscall.SIGTERM
				return
			}
		}
		sig <- syscall.SIGTERM
	}()

	tlsMode := tunnel.TLSVerify
	if p.AllowInsecure {
		tlsMode = tunnel.TLSInsecure
	}

	sni := p.SNI
	if sni == "" {
		sni = p.Server
	}

	cfg := tunnel.Config{
		Server:   p.Server,
		Port:     p.ServerPort,
		SNI:      sni,
		Username: p.Username,
		Password: p.Password,
		TLSMode:  tlsMode,
		Logger:   slog.Default(),
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { <-sig; cancel() }()

	log.Printf("[SSTP] Connecting to %s:%d (SNI: %s)...", p.Server, p.ServerPort, sni)
	dialCtx, dialCancel := context.WithTimeout(ctx, 30*time.Second)
	sess, res, err := tunnel.Dial(dialCtx, cfg)
	dialCancel()
	if err != nil {
		errStr := err.Error()
		if strings.Contains(errStr, "auth") || strings.Contains(errStr, "MS-CHAP") || strings.Contains(errStr, "credentials") {
			fmt.Fprintf(os.Stderr, "SSTP_AUTH_FAILED: %v\n", err)
		} else if strings.Contains(errStr, "certificate") || strings.Contains(errStr, "tls") || strings.Contains(errStr, "handshake") {
			fmt.Fprintf(os.Stderr, "SSTP_TLS_FAILED: %v\n", err)
		} else {
			fmt.Fprintf(os.Stderr, "FATAL_SSTP: %v\n", err)
		}
		os.Exit(1)
	}

	dnsServers := res.DNS
	if p.UseServerDNS && len(dnsServers) == 0 {
		_ = sess.Close()
		fmt.Fprintln(os.Stderr, "FATAL_SSTP: server did not provide DNS; disable Use VPN server DNS or configure DNS manually")
		os.Exit(1)
	}
	if len(dnsServers) == 0 {
		if res.PeerIP != nil {
			dnsServers = append(dnsServers, res.PeerIP)
		}
		dnsServers = append(dnsServers, net.ParseIP("1.1.1.1"), net.ParseIP("8.8.8.8"))
	}

	log.Printf("[SSTP] Tunnel connected! Assigned IP: %s, Peer IP: %s, DNS: %v", res.LocalIP, res.PeerIP, dnsServers)

	vn, err := newVirtualNet(sess, res, dnsServers, true, true, func(format string, args ...any) {
		log.Printf("[SSTP] "+format, args...)
	})
	if err != nil {
		_ = sess.Close()
		fmt.Fprintf(os.Stderr, "FATAL_SSTP: virtualNet error: %v\n", err)
		os.Exit(1)
	}

	// SOCKS5 listener on loopback
	socksLn, err := net.Listen("tcp", p.SOCKS)
	if err != nil {
		_ = sess.Close()
		vn.close()
		fmt.Fprintf(os.Stderr, "FATAL_SSTP: SOCKS listen failed on %s: %v\n", p.SOCKS, err)
		os.Exit(1)
	}

	proxy := &SOCKSProxy{
		vn:           vn,
		useServerDNS: p.UseServerDNS,
		socksUser:    p.SOCKSUser,
		socksPass:    p.SOCKSPass,
		logf: func(format string, args ...any) {
			log.Printf("[SSTP] "+format, args...)
		},
	}

	go func() {
		for {
			c, err := socksLn.Accept()
			if err != nil {
				return
			}
			go proxy.handleSOCKS(c)
		}
	}()

	log.Printf("[SSTP] SOCKS5 bridge listening on %s (auth=%v)", p.SOCKS, p.SOCKSUser != "")

	// Wait for shutdown signal or tunnel drop
	select {
	case <-ctx.Done():
		log.Printf("[SSTP] Shutting down...")
	case <-sessDone(sess):
		log.Printf("[SSTP] Tunnel session closed by server")
	}

	_ = socksLn.Close()
	_ = sess.Close()
	vn.close()
	log.Printf("[SSTP] Shutdown complete")
}

func sessDone(sess *tunnel.Session) <-chan struct{} {
	ch := make(chan struct{})
	go func() {
		_ = sess.Wait()
		close(ch)
	}()
	return ch
}
