package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"

	"golang.zx2c4.com/wireguard/tun/netstack"
	"wg-turn-client/internal/csqtt"
)

// Android: as the original app hands over its TUN descriptor, but a private socketpair and filesystem endpoint:
// a loopback UDP dispatcher or an abstract name would accept traffic from every app on the phone.
type mrttPackets struct {
	ipc    *mobileCSQTTIPC
	listen string
}

func newMRTTPackets(cache string, c *mrttConfig) (*mrttPackets, error) {
	ipc, err := newMobileCSQTTIPC(cache)
	if err != nil {
		return nil, err
	}
	return &mrttPackets{ipc, c.Listen}, nil
}
func (p *mrttPackets) arguments() []string { return []string{"--listen", p.listen, "--tun-uds", p.ipc.path} }
func (p *mrttPackets) Close()              { p.ipc.Close() }
func (p *mrttPackets) attach(ctx context.Context, _ *mrttConfig) (io.ReadWriteCloser, error) {
	return p.ipc.attach(ctx)
}
func (p *mrttPackets) relay(ctx context.Context, device io.ReadWriteCloser, socket io.ReadWriteCloser) {
	_ = csqtt.RelayIP(ctx, device, socket)
}
func mrttTransportName() string { return "libmrtt_client.so" }

// CAPTCHA_SOLVE goes to the app's WebView, which answers on stdin.
func mrttSolveCaptcha(context.Context, string, bool) (string, error) {
	return "", errors.New("android answers through stdin")
}

func mrttServeSOCKS(ctx context.Context, listener net.Listener, network *netstack.Net, _ netip.Addr, dns []string, c *mrttConfig) error {
	return serveBridgeSOCKS(ctx, listener, csqttTunnelDial(network.DialContext, dns), c.SOCKSUser, c.SOCKSPass)
}
