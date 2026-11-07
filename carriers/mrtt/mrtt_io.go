//go:build !android

package main

import (
	"context"
	"io"
	"net"
	"net/netip"
	"runtime"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
	"wg-turn-client/internal/csqtt"
)

// mrttPackets connects the client's UDP dispatcher to the userspace IP device: desktops have no VpnService descriptor to hand over.
type mrttPackets struct{ listen string }

func newMRTTPackets(_ string, c *mrttConfig) (*mrttPackets, error) { return &mrttPackets{c.Listen}, nil }
func (p *mrttPackets) arguments() []string                         { return []string{"--listen", p.listen} }
func (p *mrttPackets) Close()                                      {}

func (p *mrttPackets) attach(_ context.Context, c *mrttConfig) (*net.UDPConn, error) {
	address, err := net.ResolveUDPAddr("udp4", c.Listen)
	if err != nil {
		return nil, err
	}
	socket, err := net.DialUDP("udp4", nil, address)
	if err != nil {
		return nil, err
	}
	// Loopback UDP drops on a full buffer; the Android path never does.
	_ = socket.SetReadBuffer(socketBufSize)
	_ = socket.SetWriteBuffer(socketBufSize)
	return socket, nil
}

func (p *mrttPackets) relay(ctx context.Context, device io.ReadWriteCloser, socket *net.UDPConn) {
	_ = csqtt.RelayIP(ctx, device, socket)
}

func mrttTransportName() string {
	if runtime.GOOS == "windows" {
		return "mrtt-transport.exe"
	}
	return "mrtt-transport"
}

// The client itself waits 10 s for a hidden attempt: answer before it gives up. The visible window closes itself.
func mrttSolveCaptcha(ctx context.Context, redirect string, visible bool) (string, error) {
	budget := captchaManualWebViewTimeout
	if !visible {
		budget = 9 * time.Second
	}
	solveCtx, cancel := context.WithTimeout(ctx, budget)
	defer cancel()
	return desktopCaptchaSolver(solveCtx, redirect, visible)
}

func mrttServeSOCKS(ctx context.Context, listener net.Listener, network *netstack.Net, address netip.Addr, dns []string, c *mrttConfig) error {
	listen := func() (net.PacketConn, error) {
		return network.ListenUDPAddrPort(netip.AddrPortFrom(address, 0))
	}
	return serveBridgeSOCKS(ctx, listener, csqttTunnelDial(network.DialContext, dns), listen, c.SOCKSUser, c.SOCKSPass)
}
