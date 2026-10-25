package obfs

import (
	"encoding/binary"
	"errors"
	"math/bits"
)

const (
	xtMinWG      = 32
	xtMinPadding = 4
	xtMaxPadding = 32
)

// xtWGObfs is the obfs side of xt_wgobfs / rs-wgobfs for one key.
type xtWGObfs struct {
	key [32]byte
}

func newXTWGObfs(o *WireGuardObfsOptions) (*xtWGObfs, error) {
	if o.Key == "" {
		return nil, errors.New("wgobfs: key required")
	}
	x := &xtWGObfs{}
	// rs-wgobfs repeats or truncates the shared secret to 32 bytes.
	for i := range x.key {
		x.key[i] = o.Key[i%len(o.Key)]
	}
	return x, nil
}

// chacha6 is rs-wgobfs's chacha_hash: the 16-byte input fills the counter and nonce words.
func chacha6(input []byte, key *[32]byte, out []byte) {
	var s, x [16]uint32
	s[0], s[1], s[2], s[3] = 0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
	for i := range 8 {
		s[4+i] = binary.LittleEndian.Uint32(key[i*4:])
	}
	for i := range 4 {
		s[12+i] = binary.LittleEndian.Uint32(input[i*4:])
	}
	x = s
	quarter := func(a, b, c, d int) {
		x[a] += x[b]
		x[d] = bits.RotateLeft32(x[d]^x[a], 16)
		x[c] += x[d]
		x[b] = bits.RotateLeft32(x[b]^x[c], 12)
		x[a] += x[b]
		x[d] = bits.RotateLeft32(x[d]^x[a], 8)
		x[c] += x[d]
		x[b] = bits.RotateLeft32(x[b]^x[c], 7)
	}
	for range 3 {
		quarter(0, 4, 8, 12)
		quarter(1, 5, 9, 13)
		quarter(2, 6, 10, 14)
		quarter(3, 7, 11, 15)
		quarter(0, 5, 10, 15)
		quarter(1, 6, 11, 12)
		quarter(2, 7, 8, 13)
		quarter(3, 4, 9, 14)
	}
	for i := 0; i*4 < len(out); i++ {
		binary.LittleEndian.PutUint32(out[i*4:], x[i]+s[i])
	}
}

// encode is obfs_udp_payload; nil means the keepalive is dropped, as the module drops most of them.
func (x *xtWGObfs) encode(packet []byte) []byte {
	n := len(packet)
	if n < xtMinWG {
		return nil
	}
	buf := make([]byte, n, n+xtMaxPadding)
	copy(buf, packet)
	var in [16]byte
	copy(in[:], buf[16:32])
	in[0] += 42
	var word [4]byte
	if buf[0] == wgData && n == 32 {
		in[0]++
		chacha6(in[:], &x.key, word[:])
		if word[0] > 50 {
			return nil
		}
	}
	mac2 := func(offset int) {
		if binary.LittleEndian.Uint32(buf[offset:]) == 0 {
			in[0]++
			chacha6(in[:], &x.key, buf[offset:offset+16])
			buf[0] |= 0x10
		}
	}
	if buf[0] == wgHandshake && n == 148 {
		mac2(132)
	} else if buf[0] == wgHandshakeResp && n == 92 {
		mac2(76)
	}
	maxPadding := xtMaxPadding
	if n > 200 {
		maxPadding = 8
	}
	var rnd [xtMaxPadding]byte
	padding := 0
	for padding == 0 {
		in[0]++
		chacha6(in[:], &x.key, rnd[:])
		for _, r := range rnd {
			if int(r) >= xtMinPadding && int(r) <= maxPadding {
				padding = int(r)
				break
			}
		}
	}
	buf = append(buf, rnd[:padding]...)
	var prn [20]byte
	chacha6(buf[16:32], &x.key, prn[:])
	buf[n+padding-1] = byte(padding) ^ prn[16]
	for i := range 16 {
		buf[i] ^= prn[i]
	}
	return buf
}

// decode is unobfs_udp_payload; nil drops what is not an obfuscated WireGuard message.
func (x *xtWGObfs) decode(raw []byte) []byte {
	n := len(raw)
	if n < xtMinWG+xtMinPadding {
		return nil
	}
	var prn [20]byte
	chacha6(raw[16:32], &x.key, prn[:])
	padding := int(raw[n-1] ^ prn[16])
	if padding == 0 || padding+xtMinWG > n {
		return nil
	}
	for i := range 16 {
		raw[i] ^= prn[i]
	}
	packet := raw[:n-padding]
	switch {
	case packet[0] == 0x10|wgHandshake && len(packet) == 148:
		clear(packet[132:148])
	case packet[0] == 0x10|wgHandshakeResp && len(packet) == 92:
		clear(packet[76:92])
	}
	packet[0] &= 0x0F
	return packet
}
