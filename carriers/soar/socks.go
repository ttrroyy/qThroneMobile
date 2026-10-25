package main

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"
)

// readAddress reads a SOCKS5 address (type, address, port).
func readAddress(r io.Reader) (string, error) {
	var kind [1]byte
	if _, err := io.ReadFull(r, kind[:]); err != nil {
		return "", err
	}
	var host string
	switch kind[0] {
	case 1, 4:
		size := net.IPv4len
		if kind[0] == 4 {
			size = net.IPv6len
		}
		ip := make([]byte, size)
		if _, err := io.ReadFull(r, ip); err != nil {
			return "", err
		}
		host = net.IP(ip).String()
	case 3:
		var length [1]byte
		if _, err := io.ReadFull(r, length[:]); err != nil {
			return "", err
		}
		if length[0] == 0 {
			return "", errors.New("empty domain")
		}
		name := make([]byte, length[0])
		if _, err := io.ReadFull(r, name); err != nil {
			return "", err
		}
		host = string(name)
	default:
		return "", errors.New("unsupported address type")
	}
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:])))), nil
}

// handshake accepts only username/password authentication with the
// per-launch credentials.
func handshake(conn net.Conn, user, pass string) error {
	var head [2]byte
	if _, err := io.ReadFull(conn, head[:]); err != nil {
		return err
	}
	if head[0] != 5 || head[1] == 0 {
		return errors.New("not SOCKS5")
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	offered := false
	for _, m := range methods {
		offered = offered || m == 2
	}
	if !offered {
		_, _ = conn.Write([]byte{5, 255})
		return errors.New("authentication required")
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return err
	}
	if _, err := io.ReadFull(conn, head[:]); err != nil {
		return err
	}
	if head[0] != 1 {
		return errors.New("bad authentication version")
	}
	u := make([]byte, head[1])
	if _, err := io.ReadFull(conn, u); err != nil {
		return err
	}
	var length [1]byte
	if _, err := io.ReadFull(conn, length[:]); err != nil {
		return err
	}
	p := make([]byte, length[0])
	if _, err := io.ReadFull(conn, p); err != nil {
		return err
	}
	ok := subtle.ConstantTimeCompare(u, []byte(user))&subtle.ConstantTimeCompare(p, []byte(pass)) == 1
	status := byte(1)
	if ok {
		status = 0
	}
	if _, err := conn.Write([]byte{1, status}); err != nil {
		return err
	}
	if !ok {
		return errors.New("wrong credentials")
	}
	return nil
}

func reply(conn net.Conn, code byte) {
	_, _ = conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
}

func serveSOCKS(ctx context.Context, listener net.Listener, user, pass string, dialTimeout time.Duration,
	dial func(ctx context.Context, target string) (net.Conn, error)) {
	var wg sync.WaitGroup
	defer wg.Wait()
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer conn.Close()
			stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
			defer stop()
			_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
			if handshake(conn, user, pass) != nil {
				return
			}
			var request [3]byte
			if _, err := io.ReadFull(conn, request[:]); err != nil || request[0] != 5 {
				return
			}
			target, err := readAddress(conn)
			if err != nil {
				reply(conn, 8)
				return
			}
			if request[1] != 1 {
				// The tunnel carries TCP streams only.
				reply(conn, 7)
				return
			}
			dialCtx, cancel := context.WithTimeout(ctx, dialTimeout)
			upstream, err := dial(dialCtx, target)
			cancel()
			if err != nil {
				log.Printf("[Soar] connect failed: %v", err)
				reply(conn, 5)
				return
			}
			defer upstream.Close()
			_ = conn.SetDeadline(time.Time{})
			reply(conn, 0)
			relay(conn, upstream)
		}()
	}
}

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	copyHalf := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if c, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		} else {
			_ = dst.Close()
		}
		done <- struct{}{}
	}
	go copyHalf(a, b)
	go copyHalf(b, a)
	<-done
	<-done
}
