// Command rawline runs one FreeTurn profile behind an authenticated loopback SOCKS5
// listener. The build copies this directory into the pinned free-turn-proxy source as
// cmd/rawline, the same session its Android app starts, so its internal packages apply.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"

	"github.com/samosvalishe/free-turn-proxy/internal/client/dnsdial"
	"github.com/samosvalishe/free-turn-proxy/internal/clientid"
	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk"
	"github.com/samosvalishe/free-turn-proxy/internal/session"
	"github.com/samosvalishe/free-turn-proxy/internal/statedir"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/bind"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/wgconf"
)

// profile is the private launch file; core is config.ClientJSON, kept verbatim so that its strict decoder judges it.
type profile struct {
	Core      json.RawMessage `json:"core"`
	StateDir  string          `json:"state_dir"`
	SOCKS     string          `json:"socks"`
	SOCKSUser string          `json:"socks_user"`
	SOCKSPass string          `json:"socks_pass"`
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
	host, port, err := net.SplitHostPort(p.SOCKS)
	listenPort, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || listenPort < 1 || listenPort > 65535 {
		return p, errors.New("SOCKS listener must be on IPv4 loopback (127.0.0.1:port)")
	}
	if len(p.SOCKSUser) < 1 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) < 1 || len(p.SOCKSPass) > 255 {
		return p, errors.New("SOCKS authentication credentials are required (1..255 bytes)")
	}
	return p, nil
}

// observer prints the stream count in the form the transport's status line already reads, and opens the bridge once.
type observer struct {
	logger  logx.Logger
	connect func()
	once    sync.Once
}

func (o *observer) OnPhase(phase session.Phase, streams, total int, errMsg string) {
	fmt.Printf("[FreeTurn] phase=%s active=%d total=%d\n", phase, streams, total)
	if errMsg != "" {
		o.logger.Warnf("%s", errMsg)
	}
	if phase == session.PhaseConnected {
		o.once.Do(o.connect)
	}
}

// wgDevice is the Android app's buildTunnel on a userspace network instead of a TUN descriptor.
func wgDevice(cfg *config.Client, logger logx.Logger) (*virtualNet, *device.Device, net.PacketConn, func(), error) {
	tunCfg, err := wgconf.Parse(cfg.Tunnel.Config)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	if cfg.Tunnel.MTU > 0 {
		tunCfg.MTU = cfg.Tunnel.MTU
	}
	if len(tunCfg.Addresses) == 0 {
		return nil, nil, nil, nil, errors.New("WireGuard configuration has no Address")
	}
	// The relay session is the only way out: the peer's own address plays no part.
	for i := range tunCfg.Peers {
		tunCfg.Peers[i].Endpoint = tunnel.EndpointSinglePeer
	}
	uapi, err := tunnel.UAPI(tunCfg)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	pipe := newPacketPipe(tunCfg.MTU)
	dns := make([]net.IP, 0, len(tunCfg.DNS)+2)
	for _, server := range tunCfg.DNS {
		dns = append(dns, net.IP(server.AsSlice()))
	}
	dns = append(dns, net.ParseIP("1.1.1.1"), net.ParseIP("8.8.8.8"))
	vn, err := newVirtualNet(pipe, tunCfg.MTU, tunCfg.Addresses, dns, func(format string, args ...any) { logger.Warnf(format, args...) })
	if err != nil {
		return nil, nil, nil, nil, err
	}
	deviceSide, relaySide := netconn.PacketPipe(tunCfg.MTU+512, 0)
	peerBind := bind.NewSinglePeerBind(deviceSide)
	dev := device.NewDevice(pipe, peerBind, &device.Logger{Verbosef: logger.Debugf, Errorf: logger.Errorf})
	closeAll := func() {
		_ = peerBind.Close()
		dev.Close()
		_ = deviceSide.Close()
		_ = relaySide.Close()
		vn.close()
	}
	if err := dev.IpcSet(uapi); err != nil {
		closeAll()
		return nil, nil, nil, nil, err
	}
	if err := dev.Up(); err != nil {
		closeAll()
		return nil, nil, nil, nil, err
	}
	return vn, dev, relaySide, closeAll, nil
}

// control answers the lifecycle lines the app writes: the network handover and wake of its original monitor.
func control(sess *session.Session, cancel context.CancelFunc) {
	var handover atomic.Int64
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		switch {
		case line == "STOP":
			cancel()
			return
		case line == "DEVICE_WAKE":
			sess.Wake()
		case line == "CONNECTION_NETWORK":
			// The original waits two seconds too: one handover arrives as a burst of events.
			mark := handover.Add(1)
			time.AfterFunc(2*time.Second, func() {
				if handover.Load() == mark {
					sess.Reconnect()
				}
			})
		case strings.HasPrefix(line, "DNS|"):
			if servers := strings.TrimPrefix(line, "DNS|"); servers != "" {
				dnsdial.SetUDPDNSServers(strings.Split(servers, ","))
			}
		}
	}
	// The owner is gone; desktop keeps stdin closed from the start, so only a pipe that was open ends the session.
	if runtime.GOOS == "android" {
		cancel()
	}
}

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "FATAL_FREETURN: "+format+"\n", args...)
	os.Exit(1)
}

func main() {
	if len(os.Args) != 3 || os.Args[1] != "--rawline-config" {
		fmt.Fprintln(os.Stderr, "Usage: freeturn-client --rawline-config <config.json>")
		os.Exit(2)
	}
	p, err := readConfig(os.Args[2])
	if err != nil {
		fatal("%v", err)
	}
	if p.StateDir != "" {
		statedir.SetDir(p.StateDir)
	}
	cfg, err := config.ParseClientJSON(p.Core, "")
	if err != nil {
		fatal("%v", err)
	}
	logger := logx.New(cfg.Log.Debug)
	id, _, err := clientid.Resolve(cfg.ClientID, clientid.DefaultPaths())
	if err != nil {
		fatal("%v", err)
	}
	cfg.ClientID = id

	var vn *virtualNet
	deps := session.Deps{Logger: logger, Solver: manualSolver(), Options: session.Options{Traffic: true}}
	closeTunnel := func() {}
	if cfg.Tunnel.Enabled() {
		var relaySide net.PacketConn
		vn, _, relaySide, closeTunnel, err = wgDevice(cfg, logger)
		if err != nil {
			fatal("%v", err)
		}
		deps.LocalPipe = relaySide
	} else {
		if cfg.Proxy.Mode != config.ProxyModeTCP {
			fatal("a WireGuard or AmneziaWG configuration is required in UDP mode")
		}
		// TCP mode forwards one stream to the server's backend: the next hop in the chain speaks to it, whatever it asks SOCKS for.
		vn = &virtualNet{forward: cfg.Proxy.Listen}
	}

	socksLn, err := net.Listen("tcp", p.SOCKS)
	if err != nil {
		fatal("SOCKS listen failed on %s: %v", p.SOCKS, err)
	}
	// Nothing is accepted before the relay is up, so the owner's readiness probe means a working tunnel.
	proxy := &SOCKSProxy{vn: vn, socksUser: p.SOCKSUser, socksPass: p.SOCKSPass, logf: func(format string, args ...any) { logger.Debugf(format, args...) }}
	deps.Observer = &observer{logger: logger, connect: func() {
		fmt.Println("[READY]")
		go func() {
			for {
				c, err := socksLn.Accept()
				if err != nil {
					return
				}
				go proxy.handleSOCKS(c)
			}
		}()
	}}

	sess, err := session.New(cfg, deps)
	if err != nil {
		fatal("%v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sig; cancel() }()
	go control(sess, cancel)

	runErr := sess.Run(ctx)
	_ = socksLn.Close()
	closeTunnel()
	if runErr != nil && !errors.Is(runErr, context.Canceled) {
		fatal("%v", runErr)
	}
}

// manualSolver shows the local captcha page: in the app's WebView on Android, in the browser elsewhere.
func manualSolver() vk.ManualSolverFunc {
	if runtime.GOOS != "android" {
		return vk.DefaultManualSolver
	}
	return vk.ProxyManualSolver(func(url string) {
		fmt.Printf("CAPTCHA_SOLVE|manual|%s|freeturn\n", url)
	}, func() {
		fmt.Println("CAPTCHA_DONE")
	})
}
