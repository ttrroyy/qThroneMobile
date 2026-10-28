package main

import (
	"bufio"
	"bytes"
	"io"
	"net"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"swiftsstp/tunnel"
)

type dnsPacketPipe struct {
	incoming, outgoing chan []byte
	done               chan struct{}
}

func (p dnsPacketPipe) ReadIP(buf []byte) (int, error) {
	select {
	case packet := <-p.incoming:
		return copy(buf, packet), nil
	case <-p.done:
		return 0, io.EOF
	}
}
func (p dnsPacketPipe) WriteIP(packet []byte) error {
	select {
	case p.outgoing <- append([]byte(nil), packet...):
		return nil
	case <-p.done:
		return io.EOF
	}
}

func TestServerDNSUsesNegotiatedResolversInsideAuthenticatedBridge(t *testing.T) {
	a, b, done := make(chan []byte, 16), make(chan []byte, 16), make(chan struct{})
	dnsIP := net.ParseIP("192.0.2.53")
	client, err := newVirtualNet(dnsPacketPipe{a, b, done}, tunnel.Result{LocalIP: net.ParseIP("192.0.2.10")}, []net.IP{dnsIP, dnsIP}, true, false, t.Logf)
	if err != nil {
		t.Fatal(err)
	}
	server, err := newVirtualNet(dnsPacketPipe{b, a, done}, tunnel.Result{LocalIP: dnsIP}, nil, true, false, t.Logf)
	if err != nil {
		close(done)
		client.close()
		t.Fatal(err)
	}
	defer func() { close(done); client.close(); server.close() }()
	listener, err := gonet.DialUDP(server.stack, &tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFrom4Slice(dnsIP.To4()), Port: 53}, nil, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	tcpListener, err := gonet.ListenTCP(server.stack, tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFrom4Slice(dnsIP.To4()), Port: 53}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer tcpListener.Close()
	query := buildDNSQuery(1234, "lan.example", 16) // TXT must pass through without A/AAAA-only parsing.
	answer := append([]byte(nil), query...)
	answer[2] |= 0x80
	answer[3] = 0x83 // Preserve NXDOMAIN, too.
	tcpServed := make(chan error, 1)
	go func() {
		conn, err := tcpListener.Accept()
		if err != nil {
			tcpServed <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		got, err := readDNSFrame(conn)
		if err == nil && !bytes.Equal(got, query) {
			err = io.ErrUnexpectedEOF
		}
		if err == nil {
			err = writeDNSFrame(conn, answer)
		}
		tcpServed <- err
	}()
	served := make(chan error, 1)
	go func() {
		_ = listener.SetDeadline(time.Now().Add(5 * time.Second))
		for attempt := 0; attempt < 2; attempt++ {
			buf := make([]byte, 65535)
			n, addr, err := listener.ReadFrom(buf)
			if err != nil {
				served <- err
				return
			}
			if !bytes.Equal(buf[:n], query) {
				served <- io.ErrUnexpectedEOF
				return
			}
			reply := append([]byte(nil), answer...)
			if attempt == 0 {
				reply[0]++
			} else {
				reply[2] |= 0x02 // A truncated UDP answer must retry TCP inside the same tunnel.
			} // Retry the next negotiated resolver on a bad transaction ID.
			if _, err = listener.WriteTo(reply, addr); err != nil {
				served <- err
				return
			}
		}
		served <- nil
	}()
	proxy := &SOCKSProxy{vn: client, useServerDNS: true, socksUser: "u", socksPass: "p", logf: t.Logf}
	socksServer, socksClient := net.Pipe()
	defer socksClient.Close()
	go proxy.handleSOCKS(socksServer)
	_ = socksClient.SetDeadline(time.Now().Add(5 * time.Second))
	response := make([]byte, 2)
	socksClient.Write([]byte{5, 1, 2})
	if _, err = io.ReadFull(socksClient, response); err != nil || response[1] != 2 {
		t.Fatal("authentication negotiation", err)
	}
	socksClient.Write([]byte{1, 1, 'u', 1, 'p'})
	if _, err = io.ReadFull(socksClient, response); err != nil || response[1] != 0 {
		t.Fatal("authentication", err)
	}
	socksClient.Write([]byte{5, 1, 0, 1, 127, 0, 0, 53, 0, 53})
	reply := make([]byte, 10)
	if _, err = io.ReadFull(socksClient, reply); err != nil || reply[1] != 0 {
		t.Fatal("DNS alias", err)
	}
	if err = writeDNSFrame(socksClient, query); err != nil {
		t.Fatal(err)
	}
	got, err := readDNSFrame(bufio.NewReader(socksClient))
	if err != nil || !bytes.Equal(got, answer) {
		t.Fatalf("server DNS response changed: %x %v", got, err)
	}
	if err = <-served; err != nil {
		t.Fatal(err)
	}
	if err = <-tcpServed; err != nil {
		t.Fatal(err)
	}
}

func TestServerDNSRejectsMalformedQueriesAndHasNoPublicFallback(t *testing.T) {
	vn := &virtualNet{}
	for _, query := range [][]byte{nil, make([]byte, 11), buildDNSQuery(123, "example", 1)} {
		if _, err := vn.exchangeServerDNS(query); err == nil {
			t.Fatal("accepted query without any VPN resolver")
		}
	}
	if _, err := readDNSFrame(bytes.NewReader([]byte{0, 1, 0})); err == nil {
		t.Fatal("accepted short frame")
	}
}
