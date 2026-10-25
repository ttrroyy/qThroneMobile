package obfs

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/wireguard"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// TypeWireGuardObfs is the hidden UDP outbound a WireGuard endpoint with "obfs" dials through.
const TypeWireGuardObfs = "qthrone-wgobfs"

// WireGuardObfsOptions selects the obfuscator a WireGuard endpoint speaks to its server.
type WireGuardObfsOptions struct {
	// "wg-obfuscator" (ClusterM, Phobos) or "wgobfs" (xt_wgobfs, rs-wgobfs).
	Type           string `json:"type"`
	Key            string `json:"key"`
	Masking        string `json:"masking,omitempty"`
	MaxDummy       *int   `json:"max_dummy,omitempty"`
	ObfuscateBytes int    `json:"obfuscate_bytes,omitempty"`
	MediaPT        int    `json:"media_pt,omitempty"`
	MediaSSRC      uint32 `json:"media_ssrc,omitempty"`
	MediaClock     int    `json:"media_clock,omitempty"`
}

type WireGuardEndpointOptions struct {
	option.WireGuardEndpointOptions
	Obfs *WireGuardObfsOptions `json:"obfs,omitempty"`
}

type WireGuardObfsOutboundOptions struct {
	option.DialerOptions
	Obfs           WireGuardObfsOptions `json:"obfs"`
	RemoteIsDomain bool                 `json:"remote_is_domain,omitempty"`
}

// Register adds the obfuscating WireGuard endpoint and the Phobos SOCKS5 outbound to the sing-box registries.
func Register(outbounds *outbound.Registry, endpoints *endpoint.Registry) {
	outbound.Register[WireGuardObfsOutboundOptions](outbounds, TypeWireGuardObfs, newWireGuardObfsOutbound)
	outbound.Register[PhobosOutboundOptions](outbounds, TypePhobos, newPhobosOutbound)
	endpoint.Register[WireGuardEndpointOptions](endpoints, C.TypeWireGuard, newWireGuardEndpoint)
}

// newWireGuardEndpoint is sing-box's WireGuard endpoint whose server traffic goes through
// a hidden obfuscating outbound that holds the endpoint's own dial options.
func newWireGuardEndpoint(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options WireGuardEndpointOptions) (adapter.Endpoint, error) {
	if options.Obfs == nil {
		return wireguard.NewEndpoint(ctx, router, logger, tag, options.WireGuardEndpointOptions)
	}
	if _, err := newWireGuardCodec(options.Obfs); err != nil {
		return nil, err
	}
	if options.AmneziaWG != nil {
		return nil, errors.New("WireGuard obfuscation cannot be combined with AmneziaWG")
	}
	if options.ListenPort != 0 {
		return nil, errors.New("WireGuard obfuscation needs a client endpoint without listen_port")
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	if manager == nil {
		return nil, errors.New("missing outbound manager")
	}
	hidden := "wgobfs-" + tag
	err := manager.Create(ctx, router, logger, hidden, TypeWireGuardObfs, &WireGuardObfsOutboundOptions{
		DialerOptions: options.DialerOptions,
		Obfs:          *options.Obfs,
		RemoteIsDomain: common.Any(options.Peers, func(it option.WireGuardPeer) bool {
			return !M.ParseAddr(it.Address).IsValid()
		}),
	})
	if err != nil {
		return nil, err
	}
	options.DialerOptions = option.DialerOptions{Detour: hidden}
	return wireguard.NewEndpoint(ctx, router, logger, tag, options.WireGuardEndpointOptions)
}

type wireGuardCodec interface {
	encode(packet []byte) []byte
	decode(raw []byte, from netip.AddrPort) (packet, reply []byte)
	timerInterval() int
}

type xtCodec struct{ *xtWGObfs }

func (c xtCodec) decode(raw []byte, _ netip.AddrPort) ([]byte, []byte) {
	return c.xtWGObfs.decode(raw), nil
}
func (xtCodec) timerInterval() int { return 0 }

func newWireGuardCodec(o *WireGuardObfsOptions) (wireGuardCodec, error) {
	switch strings.ToLower(o.Type) {
	case "wg-obfuscator", "clusterm", "phobos":
		return newClusterM(o)
	case "wgobfs", "xt_wgobfs":
		x, err := newXTWGObfs(o)
		if err != nil {
			return nil, err
		}
		return xtCodec{x}, nil
	}
	return nil, errors.New("unknown WireGuard obfuscator: " + o.Type)
}

type wireGuardObfsOutbound struct {
	outbound.Adapter
	dialer  N.Dialer
	options WireGuardObfsOptions
}

func newWireGuardObfsOutbound(ctx context.Context, _ adapter.Router, _ log.ContextLogger, tag string, options WireGuardObfsOutboundOptions) (adapter.Outbound, error) {
	if _, err := newWireGuardCodec(&options.Obfs); err != nil {
		return nil, err
	}
	outboundDialer, err := dialer.NewWithOptions(dialer.Options{
		Context:          ctx,
		Options:          options.DialerOptions,
		RemoteIsDomain:   options.RemoteIsDomain,
		ResolverOnDetour: true,
	})
	if err != nil {
		return nil, err
	}
	return &wireGuardObfsOutbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypeWireGuardObfs, tag, []string{N.NetworkUDP}, options.DialerOptions),
		dialer:  outboundDialer,
		options: options.Obfs,
	}, nil
}

func (h *wireGuardObfsOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkUDP {
		return nil, errors.New("WireGuard obfuscation carries UDP only")
	}
	codec, err := newWireGuardCodec(&h.options)
	if err != nil {
		return nil, err
	}
	conn, err := h.dialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	return newObfsConn(conn, codec), nil
}

func (h *wireGuardObfsOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	codec, err := newWireGuardCodec(&h.options)
	if err != nil {
		return nil, err
	}
	conn, err := h.dialer.ListenPacket(ctx, destination)
	if err != nil {
		return nil, err
	}
	return newObfsPacketConn(conn, codec), nil
}

// keepMasking sends the STUN binding requests a call keeps up while the session lives.
func keepMasking(codec wireGuardCodec, done <-chan struct{}, send func([]byte)) {
	interval := codec.timerInterval()
	if interval == 0 {
		return
	}
	go func() {
		ticker := time.NewTicker(time.Duration(interval) * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				send(stunBindingRequestPacket())
			}
		}
	}()
}

func startsHandshake(codec wireGuardCodec, packet []byte) bool {
	c, ok := codec.(*clusterM)
	return ok && c.masking != maskNone && len(packet) >= 4 && packet[0] == wgHandshake && packet[1]|packet[2]|packet[3] == 0
}

// obfsConn wraps a connected UDP socket (the endpoint's single-peer bind).
type obfsConn struct {
	net.Conn
	codec     wireGuardCodec
	done      chan struct{}
	closeOnce sync.Once
	timerOnce sync.Once
	buf       []byte
}

func newObfsConn(conn net.Conn, codec wireGuardCodec) *obfsConn {
	return &obfsConn{Conn: conn, codec: codec, done: make(chan struct{}), buf: make([]byte, 65535)}
}

func (c *obfsConn) Write(b []byte) (int, error) {
	if startsHandshake(c.codec, b) {
		if _, err := c.Conn.Write(stunBindingRequestPacket()); err != nil {
			return 0, err
		}
		c.timerOnce.Do(func() { keepMasking(c.codec, c.done, func(p []byte) { _, _ = c.Conn.Write(p) }) })
	}
	if out := c.codec.encode(b); out != nil {
		if _, err := c.Conn.Write(out); err != nil {
			return 0, err
		}
	}
	return len(b), nil
}

func (c *obfsConn) Read(b []byte) (int, error) {
	buf := c.buf
	for {
		n, err := c.Conn.Read(buf)
		if err != nil {
			return 0, err
		}
		from, _ := netip.ParseAddrPort(c.Conn.RemoteAddr().String())
		packet, reply := c.codec.decode(buf[:n], from)
		if reply != nil {
			_, _ = c.Conn.Write(reply)
		}
		if packet != nil {
			return copy(b, packet), nil
		}
	}
}

func (c *obfsConn) Close() error {
	c.closeOnce.Do(func() { close(c.done) })
	return c.Conn.Close()
}

// obfsPacketConn wraps an unconnected UDP socket (the bind used with several peers).
type obfsPacketConn struct {
	net.PacketConn
	codec     wireGuardCodec
	done      chan struct{}
	closeOnce sync.Once
	peers     sync.Map
	buf       []byte
}

func newObfsPacketConn(conn net.PacketConn, codec wireGuardCodec) *obfsPacketConn {
	return &obfsPacketConn{PacketConn: conn, codec: codec, done: make(chan struct{}), buf: make([]byte, 65535)}
}

func (c *obfsPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if startsHandshake(c.codec, b) {
		if _, err := c.PacketConn.WriteTo(stunBindingRequestPacket(), addr); err != nil {
			return 0, err
		}
		if _, loaded := c.peers.LoadOrStore(addr.String(), struct{}{}); !loaded {
			keepMasking(c.codec, c.done, func(p []byte) { _, _ = c.PacketConn.WriteTo(p, addr) })
		}
	}
	if out := c.codec.encode(b); out != nil {
		if _, err := c.PacketConn.WriteTo(out, addr); err != nil {
			return 0, err
		}
	}
	return len(b), nil
}

func (c *obfsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	buf := c.buf
	for {
		n, addr, err := c.PacketConn.ReadFrom(buf)
		if err != nil {
			return 0, nil, err
		}
		from := M.SocksaddrFromNet(addr).Unwrap().AddrPort()
		packet, reply := c.codec.decode(buf[:n], from)
		if reply != nil {
			_, _ = c.PacketConn.WriteTo(reply, addr)
		}
		if packet != nil {
			return copy(b, packet), addr, nil
		}
	}
}

func (c *obfsPacketConn) Close() error {
	c.closeOnce.Do(func() { close(c.done) })
	return c.PacketConn.Close()
}

// Registries are sing-box's outbound and endpoint registries with this package's types added.
func Registries() (*outbound.Registry, *endpoint.Registry) {
	outbounds, endpoints := include.OutboundRegistry(), include.EndpointRegistry()
	Register(outbounds, endpoints)
	return outbounds, endpoints
}
