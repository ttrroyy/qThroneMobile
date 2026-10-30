package main

import (
	"reflect"
	"unsafe"

	"golang.zx2c4.com/wireguard/tun/netstack"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

// tuneNetstack gives the userspace TCP of a WireGuard netstack what the kernel TCP of the
// original apps' VPN mode has: CUBIC instead of gVisor's default Reno, and receive windows that
// grow with the path. Reno and a fixed window lose most of the speed on lossy TURN relays.
// netstack keeps its stack unexported; a layout change only skips the tuning.
func tuneNetstack(network *netstack.Net) {
	field := reflect.ValueOf(network).Elem().FieldByName("stack")
	if !field.IsValid() || field.Kind() != reflect.Pointer || field.Type() != reflect.TypeOf((*stack.Stack)(nil)) {
		return
	}
	s := (*stack.Stack)(unsafe.Pointer(field.Pointer()))
	cubic := tcpip.CongestionControlOption("cubic")
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	receive := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	send := tcpip.TCPSendBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	for _, option := range []tcpip.SettableTransportProtocolOption{&cubic, &moderate, &receive, &send} {
		_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, option)
	}
}
