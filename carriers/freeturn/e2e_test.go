package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/crypto/curve25519"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
)

func wgKey(t *testing.T, seed byte) (private, public []byte) {
	t.Helper()
	private = make([]byte, 32)
	for i := range private {
		private[i] = seed + byte(i)
	}
	private[0] &= 248
	private[31] = private[31]&127 | 64
	public, err := curve25519.X25519(private, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return private, public
}

func freePort(t *testing.T, network string) int {
	t.Helper()
	if network == "udp" {
		c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		return c.LocalAddr().(*net.UDPAddr).Port
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// The real path in direct mode: this client, the real FreeTurn server, a WireGuard backend, a web server inside it.
func TestSOCKSThroughServerAndWireGuard(t *testing.T) {
	if testing.Short() {
		t.Skip("builds and runs the client and server")
	}
	dir := t.TempDir()
	exe := func(name string) string {
		if os.PathSeparator == '\\' {
			return filepath.Join(dir, name+".exe")
		}
		return filepath.Join(dir, name)
	}
	for name, pkg := range map[string]string{"client": ".", "server": "../server"} {
		if out, err := exec.Command("go", "build", "-mod=mod", "-o", exe(name), pkg).CombinedOutput(); err != nil {
			t.Fatalf("build %s: %v\n%s", name, err, out)
		}
	}

	// The backend: a WireGuard peer on loopback UDP serving HTTP from its own userspace network.
	serverPrivate, serverPublic := wgKey(t, 1)
	clientPrivate, clientPublic := wgKey(t, 101)
	wgPort := freePort(t, "udp")
	pipe := newPacketPipe(1280)
	vn, err := newVirtualNet(pipe, 1280, []netip.Prefix{netip.MustParsePrefix("10.13.13.1/32")}, nil, t.Logf)
	if err != nil {
		t.Fatal(err)
	}
	defer vn.close()
	backend := device.NewDevice(pipe, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, ""))
	defer backend.Close()
	if err := backend.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.13.2/32\n", hex.EncodeToString(serverPrivate), wgPort, hex.EncodeToString(clientPublic))); err != nil {
		t.Fatal(err)
	}
	if err := backend.Up(); err != nil {
		t.Fatal(err)
	}
	listener, err := gonet.ListenTCP(vn.stack, tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFromSlice(net.IPv4(10, 13, 13, 1).To4()), Port: 80}, 2048)
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(listener, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { fmt.Fprint(w, "through the tunnel") }))

	key := strings.Repeat("ab", 32)
	peerPort := freePort(t, "udp")
	server := exec.Command(exe("server"), "-listen", fmt.Sprintf("127.0.0.1:%d", peerPort), "-connect", fmt.Sprintf("127.0.0.1:%d", wgPort), "-obf-profile", "rtpopus3", "-obf-key", key)
	server.Stdout, server.Stderr = os.Stderr, os.Stderr
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Process.Kill()

	socksPort := freePort(t, "tcp")
	conf := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.13.13.2/32\nDNS = 10.13.13.1\nMTU = 1280\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:%d\nPersistentKeepalive = 5\n",
		base64.StdEncoding.EncodeToString(clientPrivate), base64.StdEncoding.EncodeToString(serverPublic), wgPort)
	escaped := strings.ReplaceAll(conf, "\n", `\n`)
	file := filepath.Join(dir, "config.json")
	if err := os.WriteFile(file, []byte(fmt.Sprintf(`{"socks":"127.0.0.1:%d","socks_user":"u","socks_pass":"p","state_dir":%q,"core":{
		"peer":"127.0.0.1:%d","clientId":"e2e-client","subUrl":"","provider":"direct","routes":false,
		"turn":{"n":1,"transport":"tcp","host":"","port":""},"proxy":{"mode":"udp","listen":"127.0.0.1:%d"},
		"vk":{"links":[""],"streamsPerCred":12,"manualCaptcha":false,"autoCaptcha":true,"advancedCaptcha":false,"platform":"mobile"},
		"obf":{"profile":"rtpopus3","key":"%s","timingMs":0},"dns":{"mode":"auto","servers":[]},"log":{"debug":false},
		"tunnel":{"mode":"awg","config":"%s","mtu":1280}}}`, socksPort, dir, peerPort, freePort(t, "udp"), key, escaped)), 0o600); err != nil {
		t.Fatal(err)
	}
	client := exec.Command(exe("client"), "--rawline-config", file)
	client.Stdout, client.Stderr = os.Stderr, os.Stderr
	stdin, _ := client.StdinPipe()
	defer stdin.Close()
	if err := client.Start(); err != nil {
		t.Fatal(err)
	}
	defer client.Process.Kill()

	deadline := time.Now().Add(40 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		body, err := throughSOCKS(socksPort)
		if err == nil && body == "through the tunnel" {
			return
		}
		last = err
		time.Sleep(time.Second)
	}
	t.Fatalf("no answer through the tunnel: %v", last)
}

func throughSOCKS(port int) (string, error) {
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 2*time.Second)
	if err != nil {
		return "", err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(8 * time.Second))
	br := bufio.NewReader(c)
	reply := make([]byte, 2)
	c.Write([]byte{5, 1, 2})
	if _, err := io.ReadFull(br, reply); err != nil {
		return "", err
	}
	c.Write(append([]byte{1, 1}, append([]byte("u"), 1, 'p')...))
	if _, err := io.ReadFull(br, reply); err != nil || reply[1] != 0 {
		return "", fmt.Errorf("auth: %v %v", reply, err)
	}
	c.Write([]byte{5, 1, 0, 1, 10, 13, 13, 1, 0, 80})
	head := make([]byte, 10)
	if _, err := io.ReadFull(br, head); err != nil || head[1] != 0 {
		return "", fmt.Errorf("connect: %v %v", head, err)
	}
	fmt.Fprint(c, "GET / HTTP/1.0\r\nHost: x\r\n\r\n")
	response, err := http.ReadResponse(br, nil)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	return string(body), err
}
