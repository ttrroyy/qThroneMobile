package obfs

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

// TypePhobos is the client role of the Phobos obfuscator in SOCKS5 mode.
const TypePhobos = "phobos"

const (
	phobosFramePayload = 1024
	phobosFrameMax     = 2048
	tlsRecordHeader    = 5
	rtpTCPHeader       = 2 + rtpHeader
)

type PhobosOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	Username   string `json:"username,omitempty"`
	Password   string `json:"password,omitempty"`
	Key        string `json:"key"`
	Masking    string `json:"masking,omitempty"`
	MediaPT    int    `json:"media_pt,omitempty"`
	MediaSSRC  uint32 `json:"media_ssrc,omitempty"`
	MediaClock int    `json:"media_clock,omitempty"`
}

// The client role relays the application's SOCKS5 unchanged; the server role is the real
// SOCKS5 server, so this is a SOCKS5 client over the obfuscated stream.
type phobosOutbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *socks.Client
}

type phobosStream struct {
	key     []byte
	masking int
	media   mediaConfig
}

func newPhobosOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options PhobosOutboundOptions) (adapter.Outbound, error) {
	if options.Key == "" {
		return nil, errors.New("Phobos: key required")
	}
	masking, err := parseMasking(options.Masking, true)
	if err != nil {
		return nil, err
	}
	media, err := newMediaConfig(options.MediaPT, options.MediaSSRC, options.MediaClock)
	if err != nil {
		return nil, err
	}
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	key := []byte(options.Key)
	if len(key) > maxKeyLength {
		key = key[:maxKeyLength]
	}
	stream := &phobosStream{key: key, masking: masking, media: media}
	return &phobosOutbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypePhobos, tag, []string{N.NetworkTCP}, options.DialerOptions),
		logger:  logger,
		client:  socks.NewClient(&phobosDialer{outboundDialer, stream}, options.ServerOptions.Build(), socks.Version5, options.Username, options.Password),
	}, nil
}

func (h *phobosOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkTCP {
		return nil, errors.New("Phobos SOCKS5 carries TCP only")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound connection to ", destination)
	return h.client.DialContext(ctx, network, destination)
}

func (h *phobosOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("Phobos SOCKS5 carries TCP only")
}

type phobosDialer struct {
	N.Dialer
	stream *phobosStream
}

func (d *phobosDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	conn, err := d.Dialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	return d.stream.wrap(conn), nil
}

// streamCipher is stream_cipher_t: the CRC8 keystream runs on across the whole direction.
type streamCipher struct {
	key []byte
	crc byte
	ki  int
}

func newStreamCipher(key []byte) *streamCipher {
	adjusted := make([]byte, len(key))
	for i, b := range key {
		adjusted[i] = b + byte(len(key))
	}
	return &streamCipher{key: adjusted}
}

func (s *streamCipher) apply(b []byte) {
	for i := range b {
		s.crc = crc8Table[s.crc^s.key[s.ki]]
		b[i] ^= s.crc
		if s.ki++; s.ki == len(s.key) {
			s.ki = 0
		}
	}
}

func (p *phobosStream) wrap(conn net.Conn) net.Conn {
	return &phobosConn{Conn: conn, stream: p, up: newStreamCipher(p.key), down: newStreamCipher(p.key)}
}

type phobosConn struct {
	net.Conn
	stream      *phobosStream
	writeAccess sync.Mutex
	up, down    *streamCipher
	rtp         rtpState
	pending     []byte // decoded bytes not yet returned
	acc         []byte // undecoded frame bytes
}

func (c *phobosConn) Write(b []byte) (int, error) {
	c.writeAccess.Lock()
	defer c.writeAccess.Unlock()
	data := append([]byte(nil), b...)
	c.up.apply(data)
	if c.stream.masking == maskNone {
		if _, err := c.Conn.Write(data); err != nil {
			return 0, err
		}
		return len(b), nil
	}
	var out []byte
	for len(data) > 0 {
		chunk := data[:min(len(data), phobosFramePayload)]
		data = data[len(chunk):]
		out = c.frame(out, chunk)
	}
	if _, err := c.Conn.Write(out); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *phobosConn) frame(out, chunk []byte) []byte {
	n := len(chunk)
	switch c.stream.masking {
	case maskSTUN:
		pad := (4 - n&3) & 3
		header := make([]byte, stunDataHeader)
		binary.BigEndian.PutUint16(header, stunDataIndication)
		binary.BigEndian.PutUint16(header[2:], uint16(4+n+pad))
		copy(header[4:], stunCookie[:])
		_, _ = rand.Read(header[8:20])
		binary.BigEndian.PutUint16(header[20:], stunAttrData)
		binary.BigEndian.PutUint16(header[22:], uint16(n))
		out = append(append(out, header...), chunk...)
		return append(out, make([]byte, pad)...)
	case maskTLS:
		out = append(out, 0x17, 0x03, 0x03, byte(n>>8), byte(n))
		return append(out, chunk...)
	default:
		header := make([]byte, rtpTCPHeader)
		binary.BigEndian.PutUint16(header, uint16(rtpHeader+n))
		c.rtp.header(c.stream.media, header[2:])
		return append(append(out, header...), chunk...)
	}
}

func (c *phobosConn) Read(b []byte) (int, error) {
	for len(c.pending) == 0 {
		buf := make([]byte, 16384)
		n, err := c.Conn.Read(buf)
		if n > 0 {
			if c.stream.masking == maskNone {
				c.pending = buf[:n]
			} else if err := c.unframe(buf[:n]); err != nil {
				return 0, err
			}
			c.down.apply(c.pending)
		}
		if err != nil && len(c.pending) == 0 {
			return 0, err
		}
	}
	n := copy(b, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

var errPhobosFrame = errors.New("Phobos: unexpected frame from server")

// unframe is s5_mask_decode: whole frames go to pending, a partial one waits in acc.
func (c *phobosConn) unframe(in []byte) error {
	c.acc = append(c.acc, in...)
	pos := 0
	for {
		rest := c.acc[pos:]
		var header, size int
		switch c.stream.masking {
		case maskSTUN:
			if len(rest) < stunDataHeader {
				goto done
			}
			if [4]byte(rest[4:8]) != stunCookie || binary.BigEndian.Uint16(rest) != stunDataIndication ||
				binary.BigEndian.Uint16(rest[20:]) != stunAttrData {
				return errPhobosFrame
			}
			total := stunHeader + int(binary.BigEndian.Uint16(rest[2:]))
			if total < stunDataHeader || total > phobosFrameMax {
				return errPhobosFrame
			}
			if len(rest) < total {
				goto done
			}
			size = int(binary.BigEndian.Uint16(rest[22:]))
			if stunDataHeader+size > total {
				return errPhobosFrame
			}
			c.pending = append(c.pending, rest[stunDataHeader:stunDataHeader+size]...)
			pos += total
			continue
		case maskTLS:
			header = tlsRecordHeader
			if len(rest) < header {
				goto done
			}
			if rest[0] != 0x17 || rest[1] != 0x03 || rest[2] != 0x03 {
				return errPhobosFrame
			}
			size = int(binary.BigEndian.Uint16(rest[3:]))
			if size < 1 || size > phobosFrameMax {
				return errPhobosFrame
			}
		default:
			if len(rest) < 2 {
				goto done
			}
			length := int(binary.BigEndian.Uint16(rest))
			if length < rtpHeader || length > phobosFrameMax {
				return errPhobosFrame
			}
			header, size = rtpTCPHeader, length-rtpHeader
			if len(rest) >= 4 && (rest[2]&0xC0 != 0x80 || c.stream.media.pt != 0 && rest[3]&0x7F != c.stream.media.pt) {
				return errPhobosFrame
			}
		}
		if len(rest) < header+size {
			goto done
		}
		c.pending = append(c.pending, rest[header:header+size]...)
		pos += header + size
	}
done:
	c.acc = append(c.acc[:0], c.acc[pos:]...)
	return nil
}
