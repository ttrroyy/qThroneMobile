package main

import (
	"os"
	"testing"

	"cottendns-go/internal/config"
)

// The engine refuses unknown or mistyped keys at start, so what the app sends
// for a link without settings is loaded by the pinned engine at build time.
func TestAppOptionsLoadInTheEngine(t *testing.T) {
	p := profile{
		Domain: "t.example.com", Key: "0123456789abcdef0123456789abcdef", Encryption: 5,
		SOCKS: "127.0.0.1:18080", SOCKSUser: "u", SOCKSPass: "p", SystemResolvers: []string{"8.8.8.8"},
		Options: map[string]any{
			"STARTUP_MODE": "resolvers", "MIN_UPLOAD_MTU": 40.0, "MIN_DOWNLOAD_MTU": 300.0, "MAX_UPLOAD_MTU": 140.0, "MAX_DOWNLOAD_MTU": 3000.0,
			"MTU_TEST_TIMEOUT_RESOLVERS": 2.5, "MTU_TEST_TIMEOUT_LOGS": 2.5, "RESOLVER_UDP_CONNECTION_POOL_SIZE": 64.0,
			"STREAM_QUEUE_INITIAL_CAPACITY": 128.0, "ORPHAN_QUEUE_INITIAL_CAPACITY": 32.0, "DNS_RESPONSE_FRAGMENT_STORE_CAPACITY": 256.0,
			"SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS": 30.0, "CONFIG_PRESET": "default", "LEGACY_SESSION_ID": false, "TUNNEL_MODE": "legacy",
			"RESOLVER_IP_MODE": "auto", "FAST_CONNECT": true, "MTU_BACKGROUND_PARALLELISM": 1.0, "QUERY_TYPES": []any{"TXT"},
			"RESOLVER_TRANSPORT": "auto", "QNAME_LABEL_LENGTH": 63.0, "MTU_PROBE_SAMPLES": 1.0, "MTU_MAX_LOSS": 0.0, "ADAPTIVE_DUPLICATION": true,
			"DNS_EDNS_COOKIE": true, "EDNS_UDP_SIZE": 4096.0, "MTU_ADAPTIVE_GROUPING": true, "MTU_GROUP_GAP_RATIO": 0.25,
		},
	}
	configPath, resolversPath, err := writeConfig(p, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.LoadClientConfigWithOverrides(configPath, config.ClientConfigOverrides{ResolversFilePath: &resolversPath})
	if err != nil {
		text, _ := os.ReadFile(configPath)
		t.Fatalf("%v\n%s", err, text)
	}
	if cfg.MinDownloadMTU != 300 || !cfg.FastConnect || cfg.StartupMode != "resolvers" {
		t.Fatalf("options not applied: %d %v %q", cfg.MinDownloadMTU, cfg.FastConnect, cfg.StartupMode)
	}
}
