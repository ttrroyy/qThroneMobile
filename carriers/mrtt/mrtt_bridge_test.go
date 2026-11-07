package main

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func mrttTestConfig(t *testing.T, change func(map[string]any)) (*mrttConfig, error) {
	t.Helper()
	config := map[string]any{"peer": "1.2.3.4:46000", "password": "secretPASS", "device_id": "d", "device_name": "pc", "transport": "vk",
		"hashes": []string{"aaaaaaaaaaaaaaaa"}, "workers": 18, "vk_auth_mode": "vkcalls", "captcha_mode": "auto", "fingerprint": "firefox",
		"client_ids": "8202606,6287487", "obfs": "unified", "turn_transport": "udp", "srtp_mode": "auto", "mtu": 1280,
		"listen": "127.0.0.1:9000", "socks": "127.0.0.1:9001", "socks_user": "u", "socks_pass": "p"}
	if change != nil {
		change(config)
	}
	data, _ := json.Marshal(config)
	path := filepath.Join(t.TempDir(), "c.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	return readMRTTConfig(path)
}

func TestMRTTArgumentsFollowTheOriginalAppAndKeepSecretsOut(t *testing.T) {
	c, err := mrttTestConfig(t, func(m map[string]any) {
		m["transport"], m["hashes"], m["max_group"], m["max_token"] = "max", nil, "-80123", "tokenTOKENtoken"
	})
	if err != nil {
		t.Fatal(err)
	}
	args := mrttArguments(c, 7, "salt", []string{"--listen", c.Listen})
	joined := strings.Join(args, " ")
	if strings.Contains(joined, "secretPASS") || strings.Contains(joined, "tokenTOKENtoken") {
		t.Fatal(joined)
	}
	for _, want := range []string{"-n 18 --exact-workers", "--max -80123", "--vk-hash-mode manual", "--vk-auth-mode vkcalls", "--gen 7", "--salt salt"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("%q missing in %s", want, joined)
		}
	}
	line, ok := strings.CutPrefix(mrttBootstrap(c), "MAX_BOOTSTRAP:")
	decoded, _ := base64.StdEncoding.DecodeString(line)
	if !ok || string(decoded) != `{"token":"tokenTOKENtoken"}` {
		t.Fatal(line)
	}
}

func TestMRTTTransportsValidate(t *testing.T) {
	jitsi, err := mrttTestConfig(t, func(m map[string]any) { m["transport"], m["hashes"], m["jitsi_room"], m["workers"] = "jitsi", nil, "meet.example.org/room", 6 })
	if err != nil {
		t.Fatal(err)
	}
	args := mrttArguments(jitsi, 0, "", nil)
	if !slices.Contains(args, "--jitsi") || slices.Contains(args, "--vk") || mrttBootstrap(jitsi) != "" {
		t.Fatal(args)
	}
	for name, change := range map[string]func(map[string]any){
		"jitsi seats": func(m map[string]any) { m["transport"], m["jitsi_room"], m["workers"] = "jitsi", "a.b/c", 17 },
		"max group":   func(m map[string]any) { m["transport"], m["max_group"], m["max_token"] = "max", "abc", "t" },
		"no hashes":   func(m map[string]any) { m["hashes"] = nil },
		"mtu":         func(m map[string]any) { m["mtu"] = 1300 },
		"password":    func(m map[string]any) { m["password"] = "a|b" },
	} {
		if _, err := mrttTestConfig(t, change); err == nil {
			t.Fatal(name)
		}
	}
	auto, err := mrttTestConfig(t, func(m map[string]any) { m["hashes"], m["vk_hash_mode"], m["vk_access_token"] = nil, "auto_js", "vk1.token" })
	if err != nil || !strings.HasPrefix(mrttBootstrap(auto), "VK_JS_BOOTSTRAP:") || !slices.Contains(mrttArguments(auto, 0, "", nil), "auto_js") {
		t.Fatal(err)
	}
}

func TestMRTTTunnelAndRouting(t *testing.T) {
	address, dns, err := parseMRTTTunnel("TUNCONF:10.66.67.42:8.8.8.8,8.8.4.4:0:stream-v1:workers=18")
	if err != nil || address.String() != "10.66.67.42" || len(dns) != 2 {
		t.Fatal(address, dns, err)
	}
	if _, _, err := parseMRTTTunnel("RAW:1"); err == nil {
		t.Fatal("foreign config accepted")
	}
	command, ok := mrttRoutingCommand(`{"mode":"whitelist","domains":["a.ru","b;c"],"ips":["1.1.1.1"],"ip_is_whitelist":true,"template_domains":false}`)
	if !ok || command != "UPDATE_DOMAINS|enabled=1;ru=0;template=0;whitelist=1;domains=a.ru;ip_enabled=1;ip_whitelist=1;ips=1.1.1.1" {
		t.Fatal(command)
	}
}

func TestMRTTShareMatchesTheOriginalLink(t *testing.T) {
	plain := "connect?v=2&host=1.2.3.4&peer=46000&password=abc&transport=jitsi"
	link, err := mrttSharePayload(plain, true)
	if err != nil || !strings.HasPrefix(link, "mrtt://") {
		t.Fatal(link, err)
	}
	if again, err := mrttSharePayload(link, false); err != nil || again != plain {
		t.Fatal(again, err)
	}
	sub, err := mrttSharePayload(`{"v":1}`, true)
	if err != nil || !strings.HasPrefix(sub, "mrttsub://") {
		t.Fatal(sub, err)
	}
	if _, err := mrttSharePayload("csqtt://AAAA", false); err == nil {
		t.Fatal("foreign scheme accepted")
	}
}
