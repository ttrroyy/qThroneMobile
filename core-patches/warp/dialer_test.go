package warp

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRegistrationUsesTunnelDialerForEveryModeAndHost(t *testing.T) {
	for _, mode := range []string{TunnelWireGuard, TunnelMASQUE} {
		t.Run(mode, func(t *testing.T) {
			calls := 0
			failure := errors.New("tunnel dial sentinel")
			dial := func(ctx context.Context, network, address string) (net.Conn, error) {
				calls++
				if network != "tcp" || !strings.HasSuffix(address, ":443") {
					t.Fatalf("unexpected dial %s %s", network, address)
				}
				return nil, failure
			}
			_, err := RegisterWithDialer(context.Background(), mode, "http://127.0.0.1:1", []string{"one.invalid", "two.invalid"}, dial)
			if err == nil || !strings.Contains(err.Error(), failure.Error()) || calls != 2 {
				t.Fatalf("calls=%d error=%v; must never dial the host or proxy directly", calls, err)
			}
		})
	}
}

func TestTunnelRegistrationKeepsTLSVerification(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v0a4471/reg" {
			t.Errorf("unexpected API request %s %s", r.Method, r.URL.Path)
		}
		io.WriteString(w, `{"id":"fixture","token":"fixture","config":{"client_id":"AQID","interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700::2"}},"peers":[{"public_key":"fixture","endpoint":{"host":"engage.cloudflareclient.com:2408"}}]}}`)
	}))
	defer server.Close()
	host := strings.TrimPrefix(server.URL, "https://")
	calls := 0
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		calls++
		return (&net.Dialer{}).DialContext(ctx, network, host)
	}
	if _, err := RegisterWithDialer(context.Background(), TunnelWireGuard, "", []string{host}, dial); err == nil {
		t.Fatal("untrusted API certificate must be rejected through the tunnel too")
	}
	previous := http.DefaultTransport
	transport := previous.(*http.Transport).Clone()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	http.DefaultTransport = transport
	defer func() { http.DefaultTransport = previous; transport.CloseIdleConnections() }()
	identity, err := RegisterWithDialer(context.Background(), TunnelWireGuard, "", []string{host}, dial)
	if err != nil || identity.DeviceID != "fixture" || len(identity.Reserved) != 3 || calls < 2 {
		t.Fatalf("registration through tunnel: identity=%v error=%v calls=%d", identity, err, calls)
	}
}
