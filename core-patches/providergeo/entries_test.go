package providergeo

import (
	"bytes"
	"encoding/binary"
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

func TestLargeGeoDatabase(t *testing.T) {
	dir := t.TempDir()
	input, output := filepath.Join(dir, "large.dat"), filepath.Join(dir, "selected.srs")
	f, err := os.Create(input)
	if err != nil {
		t.Fatal(err)
	}
	const payload = 70 << 20
	code := []byte{10, 5, 'O', 'T', 'H', 'E', 'R'}
	ignored := binary.AppendUvarint([]byte{26}, payload)
	header := binary.AppendUvarint([]byte{10}, uint64(len(ignored)+payload+len(code)))
	if _, err = f.Write(append(header, ignored...)); err != nil {
		t.Fatal(err)
	}
	if _, err = f.Seek(payload, io.SeekCurrent); err != nil {
		t.Fatal(err)
	}
	if _, err = f.Write(code); err != nil {
		t.Fatal(err)
	}
	wanted, err := proto.Marshal(&geodata.GeoSiteList{Entry: []*geodata.GeoSite{{Code: "LOCAL", Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "example.com"}}}}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.Write(wanted); err != nil {
		t.Fatal(err)
	}
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	if err = Validate(input); err != nil {
		t.Fatal(err)
	}
	if err = Convert(input, output, "geosite", "local"); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(input, wanted, 0600); err != nil {
		t.Fatal(err)
	}
	expected := filepath.Join(dir, "expected.srs")
	if err = Convert(input, expected, "geosite", "local"); err != nil {
		t.Fatal(err)
	}
	a, _ := os.ReadFile(output)
	b, _ := os.ReadFile(expected)
	if !bytes.Equal(a, b) {
		t.Fatal("unrelated large category changed selected rules")
	}
}

func TestInvalidGeoDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geo.dat")
	for _, data := range [][]byte{nil, []byte("not protobuf"), {10}, {10, 8, 10, 1, 'X'}, {10, 1, 10}, {10, 2, 16, 128}, {10, 0}} {
		if err := os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
		if err := Validate(path); err == nil {
			t.Fatalf("accepted malformed database %x", data)
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = f.Truncate(MaxFileSize + 1); err != nil {
		t.Fatal(err)
	}
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	if err = Validate(path); err == nil {
		t.Fatal("oversized database accepted")
	}
	if err = Convert(path, filepath.Join(t.TempDir(), "out.srs"), "geosite", "x"); err == nil {
		t.Fatal("oversized conversion accepted")
	}
}
