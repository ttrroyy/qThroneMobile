package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"
)

func readDNSFrame(r io.Reader) ([]byte, error) {
	var size [2]byte
	if _, err := io.ReadFull(r, size[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(size[:]))
	if n < 12 {
		return nil, fmt.Errorf("short DNS message")
	}
	packet := make([]byte, n)
	_, err := io.ReadFull(r, packet)
	return packet, err
}

func writeDNSFrame(w io.Writer, packet []byte) error {
	frame := make([]byte, 2, len(packet)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(packet)))
	_, err := w.Write(append(frame, packet...))
	return err
}

func (p *SOCKSProxy) serveServerDNS(c net.Conn, br *bufio.Reader) {
	if _, err := c.Write(socksReply(0, nil, 0)); err != nil {
		return
	}
	for {
		_ = c.SetDeadline(time.Now().Add(30 * time.Second))
		query, err := readDNSFrame(br)
		if err != nil {
			return
		}
		answer, err := p.vn.exchangeServerDNS(query)
		if err != nil {
			p.logf("server DNS: %v", err)
			return
		}
		if err := writeDNSFrame(c, answer); err != nil {
			return
		}
	}
}

// All resolver sockets belong to the SSTP netstack, including TCP retries of truncated UDP replies.
func (vn *virtualNet) exchangeServerDNS(query []byte) ([]byte, error) {
	if len(query) < 12 || query[2]&0x80 != 0 {
		return nil, fmt.Errorf("invalid DNS query")
	}
	var last error
	for _, server := range vn.dns {
		conn, err := vn.dialUDP(server, 53)
		if err != nil {
			last = err
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(dnsTimeout))
		_, err = conn.Write(query)
		answer := make([]byte, 65535)
		n := 0
		if err == nil {
			n, err = conn.Read(answer)
		}
		_ = conn.Close()
		answer = answer[:n]
		if err == nil && n >= 12 && answer[2]&0x02 != 0 {
			ctx, cancel := context.WithTimeout(context.Background(), dnsTimeout)
			tcp, dialErr := vn.dialTCP(ctx, server, 53)
			cancel()
			err = dialErr
			if err == nil {
				_ = tcp.SetDeadline(time.Now().Add(dnsTimeout))
				err = writeDNSFrame(tcp, query)
				if err == nil {
					answer, err = readDNSFrame(tcp)
				}
				_ = tcp.Close()
			}
		}
		if err == nil && len(answer) >= 12 && answer[0] == query[0] && answer[1] == query[1] && answer[2]&0x80 != 0 {
			return answer, nil
		}
		if err == nil {
			err = fmt.Errorf("invalid DNS response")
		}
		last = err
	}
	return nil, fmt.Errorf("no answer from VPN server DNS: %v", last)
}
