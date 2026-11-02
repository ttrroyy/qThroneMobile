package providergeo

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"strings"
	"unicode/utf8"
)

const MaxFileSize = 256 << 20

type geoReader struct {
	source *io.SectionReader
	buffer *bufio.Reader
}

func newGeoReader(r *io.SectionReader) *geoReader {
	return &geoReader{r, bufio.NewReader(r)}
}

// field skips payloads with seeks so unrelated categories never enter memory.
func field(r *geoReader) (uint64, *io.SectionReader, error) {
	tag, err := binary.ReadUvarint(r.buffer)
	if err != nil {
		return 0, nil, err
	}
	if tag>>3 == 0 || tag>>3 > (1<<29)-1 {
		return 0, nil, fmt.Errorf("invalid geo field")
	}
	var size uint64
	switch tag & 7 {
	case 0:
		_, err = binary.ReadUvarint(r.buffer)
	case 1:
		size = 8
	case 2:
		size, err = binary.ReadUvarint(r.buffer)
	case 5:
		size = 4
	default:
		err = fmt.Errorf("invalid geo wire type")
	}
	if err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		return 0, nil, err
	}
	pos, _ := r.source.Seek(0, io.SeekCurrent)
	pos -= int64(r.buffer.Buffered())
	if size > uint64(r.source.Size()-pos) {
		return 0, nil, io.ErrUnexpectedEOF
	}
	payload := io.NewSectionReader(r.source, pos, int64(size))
	if size <= uint64(r.buffer.Buffered()) {
		_, err = r.buffer.Discard(int(size))
	} else {
		_, err = r.source.Seek(pos+int64(size), io.SeekStart)
		r.buffer.Reset(r.source)
	}
	return tag, payload, err
}

func entryCode(entry *io.SectionReader) (string, error) {
	var code string
	r := newGeoReader(entry)
	for {
		tag, data, err := field(r)
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", err
		}
		if tag == 10 {
			if data.Size() > 1024 {
				return "", fmt.Errorf("geo category name too long")
			}
			b, err := io.ReadAll(data)
			if err != nil || !utf8.Valid(b) {
				return "", fmt.Errorf("invalid geo category name")
			}
			code = string(b)
		}
	}
	if strings.TrimSpace(code) == "" {
		return "", fmt.Errorf("empty geo category")
	}
	_, err := entry.Seek(0, io.SeekStart)
	return code, err
}

func entries(path string, visit func(string, *io.SectionReader) (bool, error)) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	if st.Size() > MaxFileSize {
		return fmt.Errorf("geo file too large")
	}
	r := newGeoReader(io.NewSectionReader(f, 0, st.Size()))
	count := 0
	for {
		tag, entry, err := field(r)
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if tag>>3 != 1 {
			continue
		}
		if tag != 10 {
			return fmt.Errorf("invalid geo entry")
		}
		code, err := entryCode(entry)
		if err != nil {
			return err
		}
		count++
		stop, err := visit(code, entry)
		if err != nil || stop {
			return err
		}
	}
	if count == 0 {
		return fmt.Errorf("empty geo database")
	}
	return nil
}

// Validate checks the database framing before it replaces a previously working cache.
func Validate(path string) error {
	return entries(path, func(string, *io.SectionReader) (bool, error) { return false, nil })
}
