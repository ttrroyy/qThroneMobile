package main

import (
	"encoding/binary"
	"fmt"
	"math/rand"
	"net"
	"time"
)

const (
	dnsTimeout = 2500 * time.Millisecond
	qtypeA     = 1
	qtypeAAAA  = 28
)

// lookupIP resolves host through the tunnel using the netstack's UDP sockets.
func (vn *virtualNet) lookupIP(host string) (net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if ip.To4() != nil && !vn.ipv4OK {
			return nil, fmt.Errorf("IPv4 disabled by profile")
		}
		if ip.To4() == nil && !vn.ipv6OK {
			return nil, fmt.Errorf("IPv6 disabled by profile")
		}
		return ip, nil
	}
	var v4, v6 []net.IP
	for _, srv := range vn.dns {
		if srv.To4() != nil {
			if !vn.ipv4OK || v4 != nil {
				continue
			}
			ips, err := vn.queryDNS(srv, host, qtypeA)
			if err != nil || len(ips) == 0 {
				continue
			}
			v4 = ips
		} else {
			if !vn.ipv6OK || v6 != nil {
				continue
			}
			ips, err := vn.queryDNS(srv, host, qtypeAAAA)
			if err != nil || len(ips) == 0 {
				continue
			}
			v6 = ips
		}
	}
	if len(v4) > 0 {
		return v4[0], nil
	}
	if len(v6) > 0 {
		return v6[0], nil
	}
	return nil, fmt.Errorf("resolve %q: no answer from %d resolver(s)", host, len(vn.dns))
}

func (vn *virtualNet) queryDNS(server net.IP, name string, qtype uint16) ([]net.IP, error) {
	conn, err := vn.dialUDP(server, 53)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	id := uint16(rand.Intn(0xFFFF))
	msg := buildDNSQuery(id, name, qtype)
	if _, err := conn.Write(msg); err != nil {
		return nil, fmt.Errorf("dns write: %w", err)
	}

	type result struct {
		n   int
		err error
	}
	ch := make(chan result, 1)
	buf := make([]byte, 4096)
	go func() {
		n, _, err := conn.ReadFrom(buf)
		ch <- result{n, err}
	}()

	select {
	case r := <-ch:
		if r.err != nil {
			return nil, fmt.Errorf("dns read: %w", r.err)
		}
		return parseDNSResponse(buf[:r.n], id, qtype)
	case <-time.After(dnsTimeout):
		_ = conn.Close()
		<-ch
		return nil, fmt.Errorf("dns timeout from %s", server)
	}
}

func buildDNSQuery(id uint16, name string, qtype uint16) []byte {
	msg := make([]byte, 12, 64)
	binary.BigEndian.PutUint16(msg[0:2], id)
	binary.BigEndian.PutUint16(msg[2:4], 0x0100) // standard query, RD
	binary.BigEndian.PutUint16(msg[4:6], 1)      // QDCOUNT
	for _, label := range splitLabels(name) {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0)
	msg = binary.BigEndian.AppendUint16(msg, qtype)
	msg = binary.BigEndian.AppendUint16(msg, 1) // IN
	return msg
}

func splitLabels(name string) [][]byte {
	var out [][]byte
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			if i > start {
				out = append(out, []byte(name[start:i]))
			}
			start = i + 1
		}
	}
	return out
}

func parseDNSResponse(msg []byte, id uint16, qtype uint16) ([]net.IP, error) {
	if len(msg) < 12 {
		return nil, fmt.Errorf("dns: short response")
	}
	if binary.BigEndian.Uint16(msg[0:2]) != id {
		return nil, fmt.Errorf("dns: id mismatch")
	}
	flags := binary.BigEndian.Uint16(msg[2:4])
	if flags&0x8000 == 0 {
		return nil, fmt.Errorf("dns: not a response")
	}
	switch rcode := flags & 0xF; rcode {
	case 0:
	case 3:
		return nil, fmt.Errorf("dns: NXDOMAIN")
	default:
		return nil, fmt.Errorf("dns: rcode %d", rcode)
	}
	qd := binary.BigEndian.Uint16(msg[4:6])
	an := binary.BigEndian.Uint16(msg[6:8])
	off := 12
	var err error
	for i := 0; i < int(qd); i++ {
		if off, err = skipDNSName(msg, off); err != nil {
			return nil, err
		}
		off += 4
		if off > len(msg) {
			return nil, fmt.Errorf("dns: truncated question")
		}
	}
	var ips []net.IP
	for i := 0; i < int(an); i++ {
		if off, err = skipDNSName(msg, off); err != nil {
			return nil, err
		}
		if off+10 > len(msg) {
			return nil, fmt.Errorf("dns: truncated answer")
		}
		typ := binary.BigEndian.Uint16(msg[off : off+2])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8 : off+10]))
		off += 10
		if off+rdlen > len(msg) {
			return nil, fmt.Errorf("dns: truncated rdata")
		}
		if typ == qtype {
			switch {
			case typ == qtypeA && rdlen == net.IPv4len:
				ips = append(ips, net.IP(append([]byte(nil), msg[off:off+4]...)))
			case typ == qtypeAAAA && rdlen == net.IPv6len:
				ips = append(ips, net.IP(append([]byte(nil), msg[off:off+16]...)))
			}
		}
		off += rdlen
	}
	return ips, nil
}

func skipDNSName(msg []byte, off int) (int, error) {
	for {
		if off >= len(msg) {
			return 0, fmt.Errorf("dns: name out of range")
		}
		c := int(msg[off])
		switch {
		case c == 0:
			return off + 1, nil
		case c&0xC0 == 0xC0:
			if off+1 >= len(msg) {
				return 0, fmt.Errorf("dns: truncated pointer")
			}
			return off + 2, nil
		case c&0xC0 != 0:
			return 0, fmt.Errorf("dns: bad label byte")
		default:
			off += 1 + c
		}
	}
}
