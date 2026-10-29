package mobile

import (
	"context"
	"net"

	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

// The app UID bypasses Android's TUN; use the same outbound as FetchThroughProxy.
func (i *Instance) WarpRegister(request *WarpRegisterRequest) (*WarpIdentity, error) {
	if i == nil || !i.running() || i.outbounds == nil {
		return nil, E.New("instance is not running")
	}
	outbound, ok := i.outbounds.Outbound("proxy")
	if !ok {
		outbound = i.outbounds.Default()
	}
	if outbound == nil {
		return nil, E.New("no proxy outbound")
	}
	return warpRegister(i.handle.Context(), request, func(ctx context.Context, network, address string) (net.Conn, error) {
		return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
	})
}
