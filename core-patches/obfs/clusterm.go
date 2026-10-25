// Package obfs carries WireGuard and SOCKS5 traffic in the formats of
// external obfuscators, client side, inside the core:
//
//   - ClusterM/wg-obfuscator and its Phobos fork (github.com/Ground-Zerro/Phobos):
//     CRC8 keystream XOR with random padding, optional STUN or RTP (MEDIA)
//     framing; in SOCKS5 mode a stream cipher with STUN, MEDIA or TLS framing.
//   - infinet/xt_wgobfs and rs-wgobfs: ChaCha6 masks of the WireGuard header.
//
// The byte layouts follow the C and Rust sources of those projects so that
// their servers accept the traffic unchanged.
package obfs

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"hash/crc32"
	mrand "math/rand/v2"
	"net/netip"
	"strings"
	"sync"
)

const (
	maskNone = iota
	maskSTUN
	maskMedia
	maskTLS // SOCKS5 mode only
)

const (
	wgHandshake     = 1
	wgHandshakeResp = 2
	wgCookie        = 3
	wgData          = 4

	maxKeyLength        = 256
	maxDummyTotal       = 1024
	maxDummyHandshake   = 512
	defaultMaxDummy     = 4
	mediaObfuscateBytes = 16

	stunHeader     = 20
	stunDataHeader = 24
	rtpHeader      = 12

	stunDataIndication  = 0x0115
	stunBindingRequest  = 0x0001
	stunBindingSuccess  = 0x0101
	stunAttrXORMapped   = 0x0020
	stunAttrFingerprint = 0x8028
	stunAttrData        = 0x0013
)

var stunCookie = [4]byte{0x21, 0x12, 0xA4, 0x42}

// The reflected CRC8 (polynomial 0x8C) step of wg-obfuscator: crc8Table[crc^in].
var crc8Table = func() (t [256]byte) {
	for i := range t {
		crc, in := byte(0), byte(i)
		for range 8 {
			mix := (crc ^ in) & 1
			crc >>= 1
			if mix != 0 {
				crc ^= 0x8C
			}
			in >>= 1
		}
		t[i] = crc
	}
	return
}()

func parseMasking(value string, allowTLS bool) (int, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "none", "auto":
		return maskNone, nil
	case "stun":
		return maskSTUN, nil
	case "media":
		return maskMedia, nil
	case "tls":
		if allowTLS {
			return maskTLS, nil
		}
		return 0, errors.New("TLS masking is available in SOCKS5 mode only")
	}
	return 0, errors.New("unknown masking: " + value)
}

// xorPacket is xor_data of wg-obfuscator: the keystream depends on the key and on the length.
func xorPacket(buffer, key []byte) {
	base := byte(len(buffer) + len(key))
	crc, ki := byte(0), 0
	for i := range buffer {
		crc = crc8Table[crc^(key[ki]+base)]
		buffer[i] ^= crc
		if ki++; ki == len(key) {
			ki = 0
		}
	}
}

func isObfuscated(data []byte) bool {
	return data[0] < 1 || data[0] > 4 || data[1]|data[2]|data[3] != 0
}

// mediaPresets are the H.264-over-RTP (payload type, 90 kHz timestamp step) pairs of the Phobos fork.
var mediaPresets = [][2]int{
	{96, 3000}, {96, 3600}, {96, 3750}, {96, 1500}, {96, 6000},
	{97, 3000}, {97, 3600}, {97, 4500}, {97, 9000}, {97, 18000},
	{98, 3000}, {98, 3600}, {98, 3750}, {98, 1500}, {98, 6000},
	{99, 3000}, {99, 3600}, {100, 3000}, {100, 1500}, {100, 3750},
	{102, 3000}, {102, 1500}, {102, 3600}, {104, 3000}, {104, 1500},
	{106, 3000}, {106, 3750}, {108, 3000}, {108, 1500}, {110, 3000},
	{112, 3000}, {112, 3600}, {114, 3000}, {114, 1500}, {116, 3000},
	{118, 3000}, {119, 3000}, {120, 3000}, {122, 3000}, {123, 3000},
	{124, 3000}, {125, 3000}, {125, 1500}, {126, 3000}, {127, 3000},
	{127, 1500}, {127, 3750}, {96, 1502}, {96, 3003}, {97, 3753},
}

type mediaConfig struct {
	pt     byte
	ssrc   uint32
	tsStep uint16
}

func newMediaConfig(pt int, ssrc uint32, clock int) (mediaConfig, error) {
	if pt < 0 || pt > 127 || clock < 0 || clock > 1000 {
		return mediaConfig{}, errors.New("media options out of range")
	}
	c := mediaConfig{pt: byte(pt), ssrc: ssrc}
	if clock > 0 {
		c.tsStep = uint16(90000 / clock)
	}
	return c, nil
}

// rtpState is one direction of a MEDIA stream: sequence, timestamp and SSRC as a camera would send them.
type rtpState struct {
	access sync.Mutex
	init   bool
	seq    uint16
	ts     uint32
	ssrc   uint32
	step   uint32
	pt     byte
}

func (s *rtpState) header(c mediaConfig, out []byte) {
	s.access.Lock()
	if !s.init {
		s.seq, s.ts = uint16(mrand.Uint32()), mrand.Uint32()
		s.ssrc = c.ssrc
		for s.ssrc == 0 {
			s.ssrc = mrand.Uint32()
		}
		preset := mediaPresets[mrand.IntN(len(mediaPresets))]
		s.pt, s.step = byte(preset[0]), uint32(preset[1])
		if c.pt != 0 {
			s.pt = c.pt
		}
		if c.tsStep != 0 {
			s.step = uint32(c.tsStep)
		}
		s.init = true
	}
	seq, ts := s.seq, s.ts
	s.seq++
	s.ts += s.step
	s.access.Unlock()
	out[0] = 0x80
	out[1] = 0x80 | s.pt&0x7F
	binary.BigEndian.PutUint16(out[2:], seq)
	binary.BigEndian.PutUint32(out[4:], ts)
	binary.BigEndian.PutUint32(out[8:], s.ssrc)
}

// clusterM is the client half of wg-obfuscator in WireGuard mode.
type clusterM struct {
	key            []byte
	masking        int
	maxDummy       int
	obfuscateBytes int
	media          mediaConfig
	rtp            rtpState
}

func newClusterM(o *WireGuardObfsOptions) (*clusterM, error) {
	if o.Key == "" {
		return nil, errors.New("wg-obfuscator: key required")
	}
	masking, err := parseMasking(o.Masking, false)
	if err != nil {
		return nil, err
	}
	media, err := newMediaConfig(o.MediaPT, o.MediaSSRC, o.MediaClock)
	if err != nil {
		return nil, err
	}
	key := []byte(o.Key)
	if len(key) > maxKeyLength {
		key = key[:maxKeyLength]
	}
	c := &clusterM{key: key, masking: masking, maxDummy: defaultMaxDummy, obfuscateBytes: o.ObfuscateBytes, media: media}
	if o.MaxDummy != nil {
		c.maxDummy = *o.MaxDummy
	}
	if c.maxDummy < 0 || c.maxDummy > maxDummyTotal || c.obfuscateBytes < 0 {
		return nil, errors.New("wg-obfuscator: max-dummy or obfuscate-bytes out of range")
	}
	// The fork's binary obfuscates only the header under MEDIA unless told otherwise; both sides share the default.
	if masking == maskMedia && c.obfuscateBytes == 0 {
		c.obfuscateBytes = mediaObfuscateBytes
	}
	return c, nil
}

func (c *clusterM) timerInterval() int {
	switch c.masking {
	case maskSTUN:
		return 10
	case maskMedia:
		return 5
	}
	return 0
}

// encode is wg-obfuscator's encode() (version 1) followed by the masking frame; nil drops the packet.
func (c *clusterM) encode(packet []byte) []byte {
	length := len(packet)
	if length < 4 {
		return nil
	}
	partial := c.obfuscateBytes > 0 && c.obfuscateBytes < length
	dummy := 0
	if !partial && length < maxDummyTotal {
		room := maxDummyTotal - length
		if c.obfuscateBytes > 0 {
			room = min(room, c.obfuscateBytes-length+1)
		}
		switch binary.LittleEndian.Uint32(packet) {
		case wgHandshake, wgHandshakeResp:
			dummy = mrand.IntN(min(room, maxDummyHandshake))
		case wgCookie, wgData:
			if c.maxDummy > 0 {
				dummy = mrand.IntN(min(room, c.maxDummy))
			}
		}
	}
	frame := 0
	switch c.masking {
	case maskSTUN:
		frame = stunDataHeader
	case maskMedia:
		frame = rtpHeader
	}
	out := make([]byte, frame+length+dummy)
	body := out[frame:]
	copy(body, packet)
	rnd := byte(1 + mrand.IntN(255))
	body[0] ^= rnd
	body[1] = rnd
	binary.LittleEndian.PutUint16(body[2:], uint16(dummy))
	if dummy > 0 {
		_, _ = rand.Read(body[length:])
	}
	if partial {
		xorPacket(body[:c.obfuscateBytes], c.key)
	} else {
		xorPacket(body, c.key)
	}
	switch c.masking {
	case maskSTUN:
		writeSTUNHeader(out, stunDataIndication, 0)
		binary.BigEndian.PutUint16(out[20:], stunAttrData)
		binary.BigEndian.PutUint16(out[22:], uint16(len(body)))
	case maskMedia:
		c.rtp.header(c.media, out)
	}
	return out
}

// decode unwraps one datagram from the server. reply is a STUN answer owed to the server;
// a nil packet with no error is masking signalling, not WireGuard.
func (c *clusterM) decode(raw []byte, from netip.AddrPort) (packet, reply []byte) {
	if len(raw) < 4 {
		return nil, nil
	}
	if !isObfuscated(raw) {
		return raw, nil
	}
	payload := raw
	switch c.masking {
	case maskSTUN:
		payload, reply = unwrapSTUN(raw, from)
	case maskMedia:
		if len(raw) >= 8 && [4]byte(raw[4:8]) == stunCookie {
			payload, reply = unwrapSTUN(raw, from)
			break
		}
		if len(raw) < rtpHeader+4 || raw[0]&0xC0 != 0x80 ||
			c.media.pt != 0 && raw[1]&0x7F != c.media.pt ||
			c.media.ssrc != 0 && binary.BigEndian.Uint32(raw[8:]) != c.media.ssrc {
			return nil, nil
		}
		payload = raw[rtpHeader:]
	}
	if len(payload) < 4 {
		return nil, reply
	}
	if c.obfuscateBytes > 0 && c.obfuscateBytes < len(payload) {
		xorPacket(payload[:c.obfuscateBytes], c.key)
	} else {
		xorPacket(payload, c.key)
	}
	if !isObfuscated(payload) {
		return payload, reply
	}
	payload[0] ^= payload[1]
	dummy := int(binary.LittleEndian.Uint16(payload[2:]))
	payload[1], payload[2], payload[3] = 0, 0, 0
	if dummy > len(payload)-4 {
		return nil, reply
	}
	return payload[:len(payload)-dummy], reply
}

func writeSTUNHeader(b []byte, messageType, length uint16) {
	binary.BigEndian.PutUint16(b, messageType)
	binary.BigEndian.PutUint16(b[2:], length)
	copy(b[4:], stunCookie[:])
	_, _ = rand.Read(b[8:20])
}

// stunFingerprint appends FINGERPRINT over b, as the C code does before it writes the message length.
func stunFingerprint(b []byte) []byte {
	out := append(b, 0x80, 0x28, 0, 4, 0, 0, 0, 0)
	binary.BigEndian.PutUint32(out[len(out)-4:], crc32.ChecksumIEEE(b)^0x5354554E)
	return out
}

func stunBindingRequestPacket() []byte {
	b := make([]byte, stunHeader, stunHeader+8)
	writeSTUNHeader(b, stunBindingRequest, 0)
	b = stunFingerprint(b)
	binary.BigEndian.PutUint16(b[2:], 8)
	return b
}

func stunBindingSuccessPacket(txid []byte, from netip.AddrPort) []byte {
	if !from.Addr().Unmap().Is4() {
		return nil
	}
	b := make([]byte, stunHeader, stunHeader+20)
	writeSTUNHeader(b, stunBindingSuccess, 0)
	copy(b[8:20], txid)
	port, addr := from.Port(), from.Addr().Unmap().As4()
	b = append(b, 0, 0x20, 0, 8, 0, 1,
		byte(port>>8)^stunCookie[0], byte(port)^stunCookie[1],
		addr[0]^stunCookie[0], addr[1]^stunCookie[1], addr[2]^stunCookie[2], addr[3]^stunCookie[3])
	b = stunFingerprint(b)
	binary.BigEndian.PutUint16(b[2:], 20)
	return b
}

func unwrapSTUN(raw []byte, from netip.AddrPort) (payload, reply []byte) {
	if len(raw) < 8 || [4]byte(raw[4:8]) != stunCookie {
		return nil, nil
	}
	switch binary.BigEndian.Uint16(raw) {
	case stunBindingRequest:
		if len(raw) >= stunHeader {
			return nil, stunBindingSuccessPacket(raw[8:20], from)
		}
	case stunDataIndication:
		if len(raw) < stunDataHeader || int(binary.BigEndian.Uint16(raw[2:]))+stunHeader > len(raw) ||
			binary.BigEndian.Uint16(raw[20:]) != stunAttrData {
			return nil, nil
		}
		size := int(binary.BigEndian.Uint16(raw[22:]))
		if size+stunDataHeader > len(raw) {
			return nil, nil
		}
		return raw[stunDataHeader : stunDataHeader+size], nil
	}
	return nil, nil
}
