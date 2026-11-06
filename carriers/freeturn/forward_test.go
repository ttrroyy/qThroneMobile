package main

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// Without a tunnel a datagram for any peer reaches the local relay and its answer comes back wrapped.
func TestUDPForwardReachesTheLocalRelay(t *testing.T) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	go func() {
		buf := make([]byte, 2048)
		n, from, err := relay.ReadFromUDP(buf)
		if err == nil {
			_, _ = relay.WriteToUDP(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	proxy := &SOCKSProxy{vn: &virtualNet{forwardUDP: relay.LocalAddr().String(), ipv4OK: true, ipv6OK: true}, socksUser: "u", socksPass: "p", logf: t.Logf}
	go func() {
		c, err := listener.Accept()
		if err == nil {
			proxy.handleSOCKS(c)
		}
	}()
	c, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	reply := make([]byte, 2)
	c.Write([]byte{5, 1, 2})
	io.ReadFull(c, reply)
	c.Write(append([]byte{1, 1}, 'u', 1, 'p'))
	io.ReadFull(c, reply)
	if reply[1] != 0 {
		t.Fatalf("auth: %v", reply)
	}
	client, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, uint16(client.LocalAddr().(*net.UDPAddr).Port))
	c.Write(append([]byte{5, 3, 0, 1, 127, 0, 0, 1}, port...))
	head := make([]byte, 10)
	if _, err := io.ReadFull(c, head); err != nil || head[1] != 0 {
		t.Fatalf("associate: %v %v", head, err)
	}
	associated := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(head[8:]))}
	// Addressed to a peer that does not exist: the relay answers instead.
	packet := append([]byte{0, 0, 0, 1, 192, 0, 2, 9, 0xCA, 0x6C}, "ping"...)
	_, _ = client.WriteToUDP(packet, associated)
	_ = client.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 2048)
	n, _, err := client.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	if got := string(buf[10:n]); got != "echo:ping" {
		t.Fatalf("answer %q", got)
	}
}
