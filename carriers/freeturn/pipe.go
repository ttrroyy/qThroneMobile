package main

import (
	"errors"
	"os"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// packetPipe is the WireGuard device's TUN on one side and the userspace network's packets on the other.
type packetPipe struct {
	mtu    int
	toWG   chan []byte
	fromWG chan []byte
	events chan tun.Event
	done   chan struct{}
	once   sync.Once
}

func newPacketPipe(mtu int) *packetPipe {
	p := &packetPipe{mtu: mtu, toWG: make(chan []byte, 512), fromWG: make(chan []byte, 512), events: make(chan tun.Event, 1), done: make(chan struct{})}
	p.events <- tun.EventUp
	return p
}

var errPipeClosed = errors.New("packet pipe closed")

// PacketStream, as the userspace network sees it.
func (p *packetPipe) ReadIP(buf []byte) (int, error) {
	select {
	case pkt := <-p.fromWG:
		return copy(buf, pkt), nil
	case <-p.done:
		return 0, errPipeClosed
	}
}

func (p *packetPipe) WriteIP(pkt []byte) error {
	select {
	case p.toWG <- append([]byte(nil), pkt...):
		return nil
	case <-p.done:
		return errPipeClosed
	}
}

// tun.Device, as the WireGuard device sees it.
func (p *packetPipe) File() *os.File           { return nil }
func (p *packetPipe) MTU() (int, error)        { return p.mtu, nil }
func (p *packetPipe) Name() (string, error)    { return "freeturn", nil }
func (p *packetPipe) Events() <-chan tun.Event { return p.events }
func (p *packetPipe) BatchSize() int           { return 1 }

func (p *packetPipe) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case pkt := <-p.toWG:
		sizes[0] = copy(bufs[0][offset:], pkt)
		return 1, nil
	case <-p.done:
		return 0, os.ErrClosed
	}
}

func (p *packetPipe) Write(bufs [][]byte, offset int) (int, error) {
	for _, buf := range bufs {
		select {
		case p.fromWG <- append([]byte(nil), buf[offset:]...):
		case <-p.done:
			return 0, os.ErrClosed
		}
	}
	return len(bufs), nil
}

func (p *packetPipe) Close() error {
	p.once.Do(func() { close(p.done); close(p.events) })
	return nil
}
