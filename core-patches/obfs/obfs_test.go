package obfs

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"hash/crc32"
	"io"
	"net"
	"net/netip"
	"testing"
)

// Vectors from a bit-by-bit transliteration of wg-obfuscator's crc8_step.
func TestXorMatchesWgObfuscator(t *testing.T) {
	data := make([]byte, 40)
	for i := range data {
		data[i] = byte(i)
	}
	xorPacket(data, []byte("secret-key"))
	if got := hex.EncodeToString(data); got != "9082428bd883cd014781d4d10b4027c2bdafadef534260fc439090050c0fafe1659601906ef18266" {
		t.Fatal(got)
	}
	handshake := make([]byte, 148)
	xorPacket(handshake, []byte("XR0NEf8MhGAGcCpc"))
	if got := hex.EncodeToString(handshake[:32]); got != "d77d33940541ec60c6de9ec7cadeb4d080252a955b8547ef0be9a3846e1061b8" {
		t.Fatal(got)
	}
	stream := []byte("\x05\x01\x02hello world")
	cipher := newStreamCipher([]byte("XR0NEf8MhGAGcCpc"))
	cipher.apply(stream[:5])
	cipher.apply(stream[5:])
	if got := hex.EncodeToString(stream); got != "a2f40f51a35eea186137ac028e08" {
		t.Fatal(got)
	}
}

func TestChaCha6MatchesRsWgobfs(t *testing.T) {
	key := [32]byte{}
	for i := range key {
		key[i] = byte(i%8 + 1)
	}
	input := []byte{0, 0xff, 0, 0, 0, 0xff, 0, 0, 0, 0xff, 0, 0, 0, 0xff, 0, 0}
	out := make([]byte, 32)
	chacha6(input, &key, out)
	if got := hex.EncodeToString(out); got != "c5925587ca38aac431e72eed19723193511396ee67ea0843d73c9ea396d4831f" {
		t.Fatal(got)
	}
}

func wgPacket(kind byte, size int) []byte {
	p := make([]byte, size)
	p[0] = kind
	for i := 4; i < size; i++ {
		p[i] = byte(i*7 + 3)
	}
	return p
}

func TestClusterMRoundTrip(t *testing.T) {
	server := netip.MustParseAddrPort("203.0.113.5:51820")
	for _, masking := range []string{"none", "stun", "media"} {
		dummy := 45
		c, err := newClusterM(&WireGuardObfsOptions{Type: "wg-obfuscator", Key: "XR0NEf8MhGAGcCpc", Masking: masking, MaxDummy: &dummy, MediaSSRC: 0x4D454449})
		if err != nil {
			t.Fatal(err)
		}
		for _, p := range [][]byte{wgPacket(wgHandshake, 148), wgPacket(wgHandshakeResp, 92), wgPacket(wgData, 32), wgPacket(wgData, 1400)} {
			wire := c.encode(p)
			if !isObfuscated(wire) {
				t.Fatalf("%s: plain WireGuard on the wire", masking)
			}
			got, reply := c.decode(append([]byte(nil), wire...), server)
			if reply != nil || !bytes.Equal(got, p) {
				t.Fatalf("%s: round trip of type %d size %d failed", masking, p[0], len(p))
			}
		}
	}
	c, _ := newClusterM(&WireGuardObfsOptions{Key: "k", Masking: "media"})
	if c.obfuscateBytes != mediaObfuscateBytes {
		t.Fatal("MEDIA must default to header-only obfuscation")
	}
}

func TestSTUNSignalling(t *testing.T) {
	request := stunBindingRequestPacket()
	if len(request) != 28 || binary.BigEndian.Uint16(request[2:]) != 8 {
		t.Fatal("binding request layout")
	}
	header := append([]byte(nil), request[:20]...)
	binary.BigEndian.PutUint16(header[2:], 0)
	if binary.BigEndian.Uint32(request[24:]) != crc32.ChecksumIEEE(header)^0x5354554E {
		t.Fatal("fingerprint")
	}
	c, _ := newClusterM(&WireGuardObfsOptions{Key: "k", Masking: "stun"})
	packet, reply := c.decode(request, netip.MustParseAddrPort("198.51.100.7:3478"))
	if packet != nil || len(reply) != 40 || binary.BigEndian.Uint16(reply) != stunBindingSuccess || !bytes.Equal(reply[8:20], request[8:20]) {
		t.Fatal("binding request from the server must be answered and not passed to WireGuard")
	}
}

func TestXTWGObfsRoundTrip(t *testing.T) {
	x, _ := newXTWGObfs(&WireGuardObfsOptions{Key: "mysecretkey"})
	for _, p := range [][]byte{wgPacket(wgHandshake, 148), wgPacket(wgHandshakeResp, 92), wgPacket(wgData, 300)} {
		wire := x.encode(p)
		if wire == nil || bytes.Equal(wire[:16], p[:16]) || wire[0]&0x0F == p[0] && wire[1]|wire[2]|wire[3] == 0 {
			t.Fatal("header not masked")
		}
		if got := x.decode(append([]byte(nil), wire...)); !bytes.Equal(got, p) {
			t.Fatalf("round trip of type %d failed", p[0])
		}
	}
	// A handshake with an empty cookie carries a pseudo-random MAC2 that the peer clears again.
	p := wgPacket(wgHandshake, 148)
	clear(p[132:])
	wire := x.encode(p)
	if bytes.Equal(wire[132:148], make([]byte, 16)) {
		t.Fatal("empty MAC2 must be masked")
	}
	if got := x.decode(wire); !bytes.Equal(got, p) {
		t.Fatal("MAC2 not restored")
	}
}

func TestPhobosFraming(t *testing.T) {
	for _, masking := range []string{"none", "stun", "media", "tls"} {
		m, _ := parseMasking(masking, true)
		stream := &phobosStream{key: []byte("XR0NEf8MhGAGcCpc"), masking: m}
		client, server := net.Pipe()
		conn := stream.wrap(client)
		peer := stream.wrap(server).(*phobosConn)
		payload := bytes.Repeat([]byte("socks5 bytes "), 300)
		go func() { _, _ = conn.Write(payload); _ = conn.Close() }()
		// The far side decodes with the opposite cipher, as the server role does.
		peer.down = newStreamCipher(stream.key)
		got, err := io.ReadAll(peer)
		if err != nil && err != io.EOF && len(got) != len(payload) {
			t.Fatalf("%s: %v", masking, err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("%s: stream differs", masking)
		}
	}
}
