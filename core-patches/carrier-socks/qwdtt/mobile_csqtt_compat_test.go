package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMobileSOCKSCSQTTLegacyRemainsDefaultAndCompatibilityIsExplicit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	for _, test := range []struct{ document, expected string }{
		{`{}`, "libcsqtt_transport.so"},
		{`{"protocol_variant":"auto"}`, "libcsqtt_transport.so"},
		{`{"protocol_variant":"legacy"}`, "libcsqtt_transport.so"},
		{`{"protocol_variant":"compat"}`, "libcsqtt_compat.so"},
		{`{"protocol_variant":"../../other"}`, ""},
		{`not json`, ""},
	} {
		if err := os.WriteFile(path, []byte(test.document), 0600); err != nil {
			t.Fatal(err)
		}
		actual, err := mobileCSQTTTransportName(path)
		if actual != test.expected || (err != nil) != (test.expected == "") {
			t.Fatalf("configuration %q selected %q, error=%v", test.document, actual, err)
		}
	}
}

func TestMobileSOCKSCSQTTCompatibilityHintsDoNotDiscloseProviderSecrets(t *testing.T) {
	for _, test := range []struct{ input, expected string }{
		{"WRAP_AUTH_TIMEOUT password=secret", "[CSQTT] WRAP_AUTH_TIMEOUT"},
		{"FATAL_PROTOCOL: private-host and token", "[CSQTT] FATAL_PROTOCOL:"},
		{"access denied (invalid_epoch) private-data", "[CSQTT] FATAL_PROTOCOL:"},
		{"DENIED:invalid_epoch private-data", "[CSQTT] FATAL_PROTOCOL:"},
		{"CAPTCHA_SOLVE|secret", ""},
		{"FATAL_AUTH: wrong password", ""},
	} {
		if got := mobileCSQTTHint(test.input); got != test.expected {
			t.Fatalf("unexpected diagnostic %q", got)
		}
	}
}
