package main

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"sync"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const nicID = tcpip.NICID(1)

type virtualNet struct {
	stack  *stack.Stack
	link   *channel.Endpoint
	dns    []net.IP
	ipv4OK bool
	ipv6OK bool
	// TCP mode: every CONNECT goes to this local forward of the server's backend.
	forward string
	// UDP mode without a built-in tunnel: every datagram goes to the local relay, for a VPN profile in the chain.
	forwardUDP string

	cancel context.CancelFunc
	wg     sync.WaitGroup
}

type PacketStream interface {
	ReadIP(buf []byte) (int, error)
	WriteIP(pkt []byte) error
}

func newVirtualNet(sess PacketStream, mtu int, addresses []netip.Prefix, dns []net.IP, logf func(string, ...any)) (*virtualNet, error) {
	link := channel.New(512, uint32(mtu), "")
	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
		},
	})
	if err := s.CreateNIC(nicID, link); err != nil {
		return nil, fmt.Errorf("netstack: CreateNIC: %s", err)
	}

	vn := &virtualNet{
		stack: s,
		link:  link,
		dns:   dns,
	}

	var routes []tcpip.Route
	for _, prefix := range addresses {
		addr := prefix.Addr().Unmap()
		a := tcpip.AddrFromSlice(addr.AsSlice())
		protocol, destination := tcpip.NetworkProtocolNumber(ipv6.ProtocolNumber), header.IPv6EmptySubnet
		if addr.Is4() {
			protocol, destination = ipv4.ProtocolNumber, header.IPv4EmptySubnet
			vn.ipv4OK = true
		} else {
			vn.ipv6OK = true
		}
		if err := s.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
			Protocol:          protocol,
			AddressWithPrefix: tcpip.AddressWithPrefix{Address: a, PrefixLen: addr.BitLen()},
		}, stack.AddressProperties{}); err != nil {
			return nil, fmt.Errorf("netstack: address %s: %s", addr, err)
		}
		routes = append(routes, tcpip.Route{Destination: destination, NIC: nicID, SourceHint: a})
	}
	s.SetRouteTable(routes)

	tcpFwd := tcp.NewForwarder(s, 0, 64, func(r *tcp.ForwarderRequest) {
		r.Complete(true)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	udpFwd := udp.NewForwarder(s, func(*udp.ForwarderRequest) bool { return false })
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	ctx, cancel := context.WithCancel(context.Background())
	vn.cancel = cancel

	// Outbound: netstack -> tunnel
	vn.wg.Add(2)
	go func() {
		defer vn.wg.Done()
		for {
			pkt := link.ReadContext(ctx)
			if pkt == nil {
				return
			}
			v := pkt.ToView()
			pkt.DecRef()
			data := v.AsSlice()
			if err := sess.WriteIP(data); err != nil {
				logf("netstack: write to tunnel: %v", err)
				return
			}
		}
	}()

	// Inbound: tunnel -> netstack
	go func() {
		defer vn.wg.Done()
		buf := make([]byte, 65535)
		for {
			n, err := sess.ReadIP(buf)
			if err != nil {
				return
			}
			if n == 0 {
				continue
			}
			p := ipv4.ProtocolNumber
			if buf[0]>>4 == 6 {
				p = ipv6.ProtocolNumber
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
				Payload: buffer.MakeWithData(buf[:n]),
			})
			link.InjectInbound(p, pkt)
			pkt.DecRef()
		}
	}()

	return vn, nil
}

func (vn *virtualNet) close() {
	if vn == nil || vn.stack == nil {
		return
	}
	vn.cancel()
	vn.stack.Close()
	vn.link.Close()
	vn.wg.Wait()
}

func (vn *virtualNet) netProtoFor(ip net.IP) (tcpip.NetworkProtocolNumber, tcpip.Address, error) {
	if ip.To4() != nil && !vn.ipv4OK {
		return 0, tcpip.Address{}, fmt.Errorf("IPv4 disabled by profile")
	}
	if ip.To4() == nil && !vn.ipv6OK {
		return 0, tcpip.Address{}, fmt.Errorf("IPv6 disabled by profile")
	}
	if ip.To4() != nil {
		return ipv4.ProtocolNumber, tcpip.AddrFrom4Slice(ip.To4()), nil
	}
	var b [16]byte
	copy(b[:], ip.To16())
	return ipv6.ProtocolNumber, tcpip.AddrFrom16(b), nil
}

func (vn *virtualNet) dialTCP(ctx context.Context, ip net.IP, port int) (net.Conn, error) {
	proto, addr, err := vn.netProtoFor(ip)
	if err != nil {
		return nil, err
	}
	return gonet.DialContextTCP(ctx, vn.stack, tcpip.FullAddress{NIC: nicID, Addr: addr, Port: uint16(port)}, proto)
}

func (vn *virtualNet) dialUDP(ip net.IP, port int) (*gonet.UDPConn, error) {
	proto, addr, err := vn.netProtoFor(ip)
	if err != nil {
		return nil, err
	}
	return gonet.DialUDP(vn.stack, nil, &tcpip.FullAddress{NIC: nicID, Addr: addr, Port: uint16(port)}, proto)
}
