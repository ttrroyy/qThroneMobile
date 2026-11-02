package mobile

import (
	"context"
	"io"
	"net"
	"net/http"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

// FetchThroughProxy GETs url through the running instance's "proxy" outbound, the exit the user's traffic takes. The
// app's own UID is outside the Android TUN and VPN mode has no local inbound, so an exit lookup has no other way out.
// Returns at most maxBytes of a 2xx body.
func FetchThroughProxy(current *Instance, url string, userAgent string, timeoutMs int32, maxBytes int32) (string, error) {
	if current == nil || !current.running() || current.outbounds == nil {
		return "", E.New("instance is not running")
	}
	outbound, ok := current.outbounds.Outbound("proxy")
	if !ok {
		outbound = current.outbounds.Default()
	}
	if outbound == nil {
		return "", E.New("no proxy outbound")
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
		},
		ForceAttemptHTTP2: true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: timeout}
	ctx, cancel := context.WithTimeout(current.handle.Context(), timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	if userAgent != "" {
		request.Header.Set("User-Agent", userAgent)
	}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode > 299 {
		return "", E.New("HTTP ", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, int64(maxBytes)))
	if err != nil {
		return "", err
	}
	return string(body), nil
}
