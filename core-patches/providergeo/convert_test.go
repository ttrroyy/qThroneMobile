package providergeo

import (
	"github.com/sagernet/sing-box/common/srs"
	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
	"os"
	"path/filepath"
	"testing"
)

func TestProviderCategoryConversion(t *testing.T) {
	dir := t.TempDir()
	input, output := filepath.Join(dir, "geosite.dat"), filepath.Join(dir, "whitelist.srs")
	data, err := proto.Marshal(&geodata.GeoSiteList{Entry: []*geodata.GeoSite{{Code: "WHITELIST", Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "example.com"}, {Type: geodata.Domain_Full, Value: "cdn.example.net"}}}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(input, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err = Convert(input, output, "geosite", "whitelist"); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(output)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	rules, err := srs.Read(f, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(rules.Options.Rules) != 1 || len(rules.Options.Rules[0].DefaultOptions.DomainSuffix) != 1 || rules.Options.Rules[0].DefaultOptions.DomainSuffix[0] != "example.com" {
		t.Fatalf("wrong converted rules: %+v", rules)
	}
	original, _ := os.ReadFile(output)
	if err = Convert(input, output, "geosite", "missing"); err == nil {
		t.Fatal("missing category accepted")
	}
	after, _ := os.ReadFile(output)
	if string(after) != string(original) {
		t.Fatal("failed conversion replaced good output")
	}
	if err = os.WriteFile(input, []byte("not protobuf"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = Convert(input, output, "geosite", "WHITELIST"); err == nil {
		t.Fatal("damaged geo data accepted")
	}
}

func TestProviderGeoIPConversion(t *testing.T) {
	dir := t.TempDir()
	input, output := filepath.Join(dir, "geoip.dat"), filepath.Join(dir, "local.srs")
	data, err := proto.Marshal(&geodata.GeoIPList{Entry: []*geodata.GeoIP{{Code: "local", Cidr: []*geodata.CIDR{{Ip: []byte{192, 0, 2, 1}, Prefix: 24}}}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(input, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err = Convert(input, output, "geoip", "LOCAL"); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(output)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	rules, err := srs.Read(f, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(rules.Options.Rules) != 1 {
		t.Fatal("missing IP rule")
	}
}

func TestXrayProviderFileIsolation(t *testing.T) {
	dir := t.TempDir()
	provider := filepath.Join(dir, "provider-geodata")
	if err := os.Mkdir(provider, 0700); err != nil {
		t.Fatal(err)
	}
	data, err := proto.Marshal(&geodata.GeoSiteList{Entry: []*geodata.GeoSite{{Code: "WHITELIST", Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "provider.example"}}}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(provider, "one.dat"), data, 0600); err != nil {
		t.Fatal(err)
	}
	other, err := proto.Marshal(&geodata.GeoSiteList{Entry: []*geodata.GeoSite{{Code: "OTHER"}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(provider, "two.dat"), other, 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XRAY_LOCATION_ASSET", dir)
	if _, err = geodata.ParseDomainRule("ext:provider-geodata/one.dat:WHITELIST", geodata.Domain_Domain); err != nil {
		t.Fatal(err)
	}
	if _, err = geodata.ParseDomainRule("ext:provider-geodata/two.dat:WHITELIST", geodata.Domain_Domain); err == nil {
		t.Fatal("other provider silently reused whitelist")
	}
}
