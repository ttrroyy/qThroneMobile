"""Adapt the pinned qWDTT/CSQTT bridge to the private Android captcha channel."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / "creds.go"
s = p.read_text(encoding="utf-8")
start = "func requestWebViewCaptcha(streamID int, captchaErr *VkCaptchaError, mode string, timeout time.Duration) (string, error) {"
assert s.count(start) == 1
# The original per-mode timeouts stay: auto 10 s, manual 60 s, selected 120 s.
s = s.replace(start, start + '\n\tif runtime.GOOS == "android" && desktopProbeOnly && !desktopInteractiveProbe {\n\t\treturn "", errCaptchaInteractionRequired\n\t}')
s = s.replace("if desktopBridgeMode {", 'if desktopBridgeMode && runtime.GOOS != "android" {', 1)
if '"runtime"' not in s:
    s = s.replace("import (", 'import (\n\t"runtime"', 1)
old = "waitCtx, cancel := context.WithTimeout(context.Background(), timeout)"
assert s.count(old) == 1
s = s.replace(old, "parent := bridgeContext\n\tif parent == nil { parent = context.Background() }\n\twaitCtx, cancel := context.WithTimeout(parent, timeout)")
p.write_text(s, encoding="utf-8")

# Preserve account TURN authorization in the private bridge configuration.
p = root / "bridge_config.go"
s = p.read_text(encoding="utf-8")
marker = "type bridgeConfig struct {"
assert s.count(marker) == 1
s = s.replace(marker, marker + '\n\tVKAuth string `json:"vk_auth"`')
marker = '\t\t"captcha-mode": c.CaptchaMode, "vk-auth": "anonymous", "vk-anon-path": c.VKAnonPath,'
assert s.count(marker) == 1
s = s.replace(marker, '\t\t"captcha-mode": c.CaptchaMode, "vk-auth": c.VKAuth, "vk-anon-path": c.VKAnonPath,')
marker = '\tif c.Mode == "" {'
assert s.count(marker) == 1
s = s.replace(marker, '\tif c.VKAuth == "" { c.VKAuth = "anonymous" }\n\tif c.VKAuth != "anonymous" && c.VKAuth != "account" { return nil, fmt.Errorf("invalid VK account mode") }\n' + marker)
p.write_text(s, encoding="utf-8")
p = root / "csqtt_bridge.go"
s = p.read_text(encoding="utf-8")
a = s.index("\tgo func() {\n\t\tdefer cancel()\n\t\tscanner := bufio.NewScanner(os.Stdin)")
b = s.index("\t}()", a) + len("\t}()")
s = s[:a] + s[b:]
marker = "\tconfigs := make(chan string, 1)"
assert s.count(marker) == 1
s = s.replace(marker, '''\tgo func() {
        defer cancel()
        scanner := bufio.NewScanner(os.Stdin)
        scanner.Buffer(make([]byte, 4096), 32768)
        for scanner.Scan() {
            line := strings.TrimSpace(scanner.Text())
            if line == "STOP" { cancel(); return }
            if runtime.GOOS == "android" && strings.HasPrefix(line, "CAPTCHA_RESULT|") { send(line) }
        }
    }()
''' + marker)
marker = '\t\t\tvisible := parts[1] != "auto"'
assert s.count(marker) == 1
s = s.replace(marker, '\t\t\tif runtime.GOOS == "android" {\n\t\t\t\tif c.ProbeOnly && !c.InteractiveCaptcha { send("CAPTCHA_RESULT|error:cancelled"); continue }\n\t\t\t\tfmt.Println(line[i:]); continue\n\t\t\t}\n' + marker)
p.write_text(s, encoding="utf-8")

# The account stdin protocol uses one result channel: serialize concurrent hashes.
p = root / "vk_account.go"
s = p.read_text(encoding="utf-8")
marker = "func requestAccountTurnCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {"
assert s.count(marker) == 1
s = s.replace(marker, "var RawlineAccountAuthMu sync.Mutex\n\n" + marker + "\n\tRawlineAccountAuthMu.Lock()\n\tdefer RawlineAccountAuthMu.Unlock()")
p.write_text(s, encoding="utf-8")
