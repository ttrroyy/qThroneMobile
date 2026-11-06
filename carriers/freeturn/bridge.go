package main

import (
	"bufio"
	"io"
	"net"
)

// bridge copies bytes between the local client and the virtual connection through the tunnel.
func bridge(local net.Conn, lbr *bufio.Reader, remote net.Conn) {
	doneA := make(chan struct{})
	doneB := make(chan struct{})

	go func() {
		defer close(doneA)
		_, _ = io.Copy(remote, lbr)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = remote.Close()
		}
	}()
	go func() {
		defer close(doneB)
		_, _ = io.Copy(local, remote)
		if cw, ok := local.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = local.Close()
		}
	}()

	select {
	case <-doneA:
		_ = local.Close()
		_ = remote.Close()
		<-doneB
	case <-doneB:
		_ = local.Close()
		_ = remote.Close()
		<-doneA
	}
}
