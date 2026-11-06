package main

// Local provider fixture adapted from olcrtc/internal/client/listener_test.go (WTFPL).
import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

type listenerTestRoom struct {
	mu       sync.Mutex
	sessions map[*listenerTestSession]struct{}
}

type listenerTestSession struct {
	inbox  chan []byte
	done   chan struct{}
	room   *listenerTestRoom
	onData func([]byte)
	mu     sync.Mutex
	ready  bool
	closed bool
}

func (r *listenerTestRoom) newSession(onData func([]byte)) *listenerTestSession {
	session := &listenerTestSession{room: r, onData: onData, inbox: make(chan []byte, 128), done: make(chan struct{})}
	go func() {
		for {
			select {
			case packet := <-session.inbox:
				session.deliver(packet)
			case <-session.done:
				return
			}
		}
	}()
	r.mu.Lock()
	r.sessions[session] = struct{}{}
	r.mu.Unlock()
	return session
}

func (r *listenerTestRoom) waitConnected(t *testing.T, want int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if r.connected() >= want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("connected sessions = %d, want %d", r.connected(), want)
}

func (r *listenerTestRoom) connected() int {
	r.mu.Lock()
	sessions := make([]*listenerTestSession, 0, len(r.sessions))
	for session := range r.sessions {
		sessions = append(sessions, session)
	}
	r.mu.Unlock()
	count := 0
	for _, session := range sessions {
		if session.CanSend() {
			count++
		}
	}
	return count
}

func (s *listenerTestSession) Connect(context.Context) error {
	s.mu.Lock()
	s.ready = true
	s.mu.Unlock()
	return nil
}

func (s *listenerTestSession) Send(data []byte) error {
	if !s.CanSend() {
		return io.ErrClosedPipe
	}
	s.room.mu.Lock()
	peers := make([]*listenerTestSession, 0, len(s.room.sessions))
	for peer := range s.room.sessions {
		if peer != s {
			peers = append(peers, peer)
		}
	}
	s.room.mu.Unlock()
	for _, peer := range peers {
		select {
		case peer.inbox <- append([]byte(nil), data...):
		case <-peer.done:
		}
	}
	return nil
}

func (s *listenerTestSession) deliver(data []byte) {
	s.mu.Lock()
	ready := s.ready && !s.closed
	onData := s.onData
	s.mu.Unlock()
	if ready && onData != nil {
		onData(append([]byte(nil), data...))
	}
}

func (s *listenerTestSession) Close() error {
	s.mu.Lock()
	if !s.closed {
		close(s.done)
	}
	s.closed = true
	s.ready = false
	s.mu.Unlock()
	return nil
}

func (*listenerTestSession) SetReconnectCallback(func())         {}
func (*listenerTestSession) SetShouldReconnect(func() bool)      {}
func (*listenerTestSession) SetEndedCallback(func(string))       {}
func (*listenerTestSession) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (s *listenerTestSession) CanSend() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ready && !s.closed
}
func (s *listenerTestSession) SubscriberCanSend() bool { return s.CanSend() }
func (*listenerTestSession) GetBufferedAmount() uint64 { return 0 }
func (*listenerTestSession) Reconnect(string)          {}

func TestEncryptedHTTPBulkAndRestart(t *testing.T) {
	base := fixture()
	cfg, err := makeClient(base)
	if err != nil {
		t.Fatal(err)
	}
	client.New(cfg)
	payload := strings.Repeat("olcrtc-transfer-", 32768)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/bulk" {
			_, _ = io.WriteString(w, payload)
		} else {
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer target.Close()
	for iteration := 0; iteration < 2; iteration++ {
		room := &listenerTestRoom{sessions: make(map[*listenerTestSession]struct{})}
		providerName := "rawline-local-integration-" + string(rune('0'+iteration))
		enginebuiltin.Register(providerName, func(_ context.Context, c enginebuiltin.Config) (engine.Session, error) {
			return room.newSession(c.OnData), nil
		})
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		srvDone := make(chan error, 1)
		go func() {
			srvDone <- tunnel.New(tunnel.Config{Provider: providerName, RoomURL: base.Room, Transport: base.Transport, KeyHex: base.Key, AuthHook: func(id string, _ map[string]any) (string, error) {
				if id != base.DeviceID {
					t.Error("device identity changed on restart")
				}
				return "test-session", nil
			}}).Run(ctx)
		}()
		room.waitConnected(t, 1)
		done := make(chan error, 1)
		address := make(chan string, 1)
		cfg.Provider = providerName
		cfg.LocalAddr = "127.0.0.1:0"
		go func() { done <- client.New(cfg).RunWithAddress(ctx, func(addr string) { address <- addr }) }()
		var addr string
		select {
		case addr = <-address:
		case e := <-done:
			cancel()
			t.Fatal(e)
		case <-ctx.Done():
			cancel()
			t.Fatal("client startup timed out")
		}
		proxy, _ := url.Parse("socks5://" + base.SOCKSUser + ":" + base.SOCKSPass + "@" + addr)
		transport := &http.Transport{Proxy: http.ProxyURL(proxy)}
		httpClient := &http.Client{Transport: transport, Timeout: 3 * time.Second}
		for n := 0; n < 4; n++ {
			response, e := httpClient.Get(target.URL + "/ping")
			if e != nil {
				cancel()
				t.Fatal(e)
			}
			response.Body.Close()
			if response.StatusCode != http.StatusNoContent {
				cancel()
				t.Fatal("HTTP ping failed")
			}
		}
		response, e := httpClient.Get(target.URL + "/bulk")
		if e != nil {
			cancel()
			t.Fatal(e)
		}
		got, e := io.ReadAll(response.Body)
		response.Body.Close()
		if e != nil || string(got) != payload {
			cancel()
			t.Fatal("bulk transfer corrupted")
		}
		transport.CloseIdleConnections()
		cancel()
		for _, completed := range []chan error{done, srvDone} {
			select {
			case e := <-completed:
				if e != nil {
					t.Fatal(e)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("endpoint did not stop")
			}
		}
	}
}
