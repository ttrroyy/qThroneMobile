package main

import (
	"bufio"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"
)

// MRTT is its own transport: this runs the original client the way its Android app starts it
// (TunnelManager.startTunnel): non-secret flags in argv, the password and device in the environment,
// the VK or MAX token in the first stdin line. The packets arrive here instead of a VpnService.
type mrttConfig struct {
	Peer          string   `json:"peer"`
	Password      string   `json:"password"`
	DeviceID      string   `json:"device_id"`
	DeviceName    string   `json:"device_name"`
	Transport     string   `json:"transport"`
	Hashes        []string `json:"hashes"`
	HashMode      string   `json:"vk_hash_mode"`
	VKAccessToken string   `json:"vk_access_token"`
	MaxGroup      string   `json:"max_group"`
	MaxToken      string   `json:"max_token"`
	JitsiRoom     string   `json:"jitsi_room"`
	Workers       int      `json:"workers"`
	VKAuthMode    string   `json:"vk_auth_mode"`
	CaptchaMode   string   `json:"captcha_mode"`
	Fingerprint   string   `json:"fingerprint"`
	ClientIDs     string   `json:"client_ids"`
	Obfs          string   `json:"obfs"`
	TurnTransport string   `json:"turn_transport"`
	SRTPMode      string   `json:"srtp_mode"`
	CustomTurnIPs string   `json:"custom_turn_ips"`
	MTU           int      `json:"mtu"`
	Listen        string   `json:"listen"`
	SOCKS         string   `json:"socks"`
	SOCKSUser     string   `json:"socks_user"`
	SOCKSPass     string   `json:"socks_pass"`
	Diagnostics   bool     `json:"diagnostics"`
	ProbeOnly     bool     `json:"probe_only"`
	Interactive   bool     `json:"interactive_captcha"`
}

const (
	mrttMaxWorkers      = 126
	mrttMinWorkers      = 3
	mrttMaxJitsiWorkers = 16
	mrttEventPrefix     = "__MRTT_EVENT__|"
)

var (
	mrttMaxGroupPattern  = regexp.MustCompile(`^-?[0-9]{1,64}$`)
	mrttJitsiRoomPattern = regexp.MustCompile(`^[a-z0-9.:-]+/[a-z0-9]{1,64}$`)
)

func validMRTTToken(token string) bool {
	return token != "" && len(token) <= 4096 &&
		strings.IndexFunc(token, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) < 0
}

func readMRTTConfig(path string) (*mrttConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, errors.New("cannot read private MRTT configuration")
	}
	var c mrttConfig
	if json.Unmarshal(data, &c) != nil {
		return nil, errors.New("invalid MRTT configuration")
	}
	host, port, err := net.SplitHostPort(c.Peer)
	p, _ := strconv.Atoi(port)
	if err != nil || host == "" || p < 1 || p > 65535 {
		return nil, errors.New("invalid MRTT peer")
	}
	if c.Password == "" || len(c.Password) > 128 || strings.ContainsAny(c.Password, "|\r\n ") || strings.ContainsAny(c.DeviceID+c.DeviceName, "|\r\n") {
		return nil, errors.New("invalid MRTT credentials")
	}
	switch c.Transport {
	case "vk":
		if c.HashMode == "auto_js" {
			if !validMRTTToken(c.VKAccessToken) {
				return nil, errors.New("MRTT needs a VK token for automatic calls")
			}
		} else if len(c.Hashes) < 1 {
			return nil, errors.New("MRTT needs one to six VK hashes")
		}
		if len(c.Hashes) > 6 {
			return nil, errors.New("MRTT needs one to six VK hashes")
		}
		for _, hash := range c.Hashes {
			if len(hash) < 16 || strings.ContainsAny(hash, ",|\r\n ") {
				return nil, errors.New("invalid MRTT hash")
			}
		}
	case "max":
		if !mrttMaxGroupPattern.MatchString(c.MaxGroup) || !validMRTTToken(c.MaxToken) {
			return nil, errors.New("MRTT needs a bound MAX account and group")
		}
	case "jitsi":
		if !mrttJitsiRoomPattern.MatchString(c.JitsiRoom) {
			return nil, errors.New("MRTT needs a Jitsi room")
		}
	default:
		return nil, errors.New("unknown MRTT transport")
	}
	limit := mrttMaxWorkers
	if c.Transport == "jitsi" {
		limit = mrttMaxJitsiWorkers
	}
	if c.Workers < mrttMinWorkers || c.Workers > limit {
		return nil, errors.New("invalid MRTT worker count")
	}
	if c.MTU != 1280 && c.MTU != 1400 && c.MTU != 1500 {
		return nil, errors.New("invalid MRTT MTU")
	}
	for _, address := range []string{c.Listen, c.SOCKS} {
		host, port, err := net.SplitHostPort(address)
		p, _ := strconv.Atoi(port)
		if err != nil || host != "127.0.0.1" || p < 1 || p > 65535 {
			return nil, errors.New("invalid MRTT loopback listener")
		}
	}
	if c.SOCKSUser == "" || c.SOCKSPass == "" {
		return nil, errors.New("MRTT SOCKS credentials required")
	}
	if c.TurnTransport != "udp" && c.TurnTransport != "tcp_tls" {
		return nil, errors.New("invalid MRTT TURN transport")
	}
	if c.SRTPMode != "auto" && c.SRTPMode != "wrap" && c.SRTPMode != "dtls" {
		return nil, errors.New("invalid MRTT SRTP mode")
	}
	if c.Obfs != "unified" {
		return nil, errors.New("invalid MRTT obfuscation")
	}
	if c.CaptchaMode != "auto" && c.CaptchaMode != "wv" && c.CaptchaMode != "rjs" {
		return nil, errors.New("invalid MRTT captcha mode")
	}
	if c.VKAuthMode != "vkcalls" && c.VKAuthMode != "legacy" && c.VKAuthMode != "auto_js" {
		return nil, errors.New("invalid MRTT authorization")
	}
	return &c, nil
}

// mrttArguments is the command of the original TunnelManager; tun holds how packets reach this bridge.
func mrttArguments(c *mrttConfig, generation uint64, salt string, tun []string) []string {
	args := []string{"--peer", c.Peer, "-n", strconv.Itoa(c.Workers), "--exact-workers"}
	args = append(args, tun...)
	hashMode := "manual"
	authMode := c.VKAuthMode
	switch c.Transport {
	case "jitsi":
		args = append(args, "--jitsi", c.JitsiRoom)
		authMode = "vkcalls"
	case "max":
		args = append(args, "--max", c.MaxGroup)
		authMode = "vkcalls"
	default:
		if len(c.Hashes) > 0 {
			args = append(args, "--vk", strings.Join(c.Hashes, ","))
		}
		if c.HashMode != "" {
			hashMode = c.HashMode
		}
		if hashMode == "auto_js" {
			authMode = "auto_js"
		}
	}
	args = append(args, "--vk-hash-mode", hashMode)
	if c.Fingerprint != "" {
		args = append(args, "--fingerprint", c.Fingerprint)
	}
	if c.ClientIDs != "" {
		args = append(args, "--client-ids", c.ClientIDs)
	}
	args = append(args, "--obfs", c.Obfs, "--turn-transport", c.TurnTransport, "--srtp-mode", c.SRTPMode)
	// Own TURN subnets belong to VK and MAX; Jitsi goes through the room without TURN.
	if c.CustomTurnIPs != "" && c.Transport != "jitsi" {
		args = append(args, "--custom-turn-ips", c.CustomTurnIPs)
	}
	args = append(args, "--vk-auth-mode", authMode)
	if generation > 0 {
		args = append(args, "--gen", strconv.FormatUint(generation, 10))
	}
	if salt != "" {
		args = append(args, "--salt", salt)
	}
	return append(args, "--captcha-mode", c.CaptchaMode)
}

// The first stdin line the client waits for; the token never enters argv.
func mrttBootstrap(c *mrttConfig) string {
	token, prefix := "", ""
	if c.Transport == "max" {
		token, prefix = c.MaxToken, "MAX_BOOTSTRAP"
	} else if c.Transport == "vk" && c.HashMode == "auto_js" {
		token, prefix = c.VKAccessToken, "VK_JS_BOOTSTRAP"
	}
	if prefix == "" {
		return ""
	}
	payload, _ := json.Marshal(map[string]string{"token": token})
	return prefix + ":" + base64.StdEncoding.EncodeToString(payload)
}

// TUNCONF:ip:dns[,dns]:port:stream-v1[:workers=N]; only the address and DNS concern the packet bridge.
func parseMRTTTunnel(raw string) (netip.Addr, []string, error) {
	invalid := errors.New("invalid MRTT tunnel configuration")
	fields := strings.Split(strings.TrimPrefix(raw, "TUNCONF:"), ":")
	if !strings.HasPrefix(raw, "TUNCONF:") || len(fields) < 2 {
		return netip.Addr{}, nil, invalid
	}
	address, err := netip.ParseAddr(fields[0])
	if err != nil || !address.Is4() {
		return netip.Addr{}, nil, invalid
	}
	var dns []string
	for _, item := range strings.Split(fields[1], ",") {
		if parsed, err := netip.ParseAddr(strings.TrimSpace(item)); err == nil {
			dns = append(dns, parsed.String())
		}
	}
	if len(dns) == 0 {
		return netip.Addr{}, nil, invalid
	}
	return address, dns, nil
}

// The server's routing rule for this config, as UPDATE_DOMAINS of the client.
// shortcut: the rule's app list has no meaning behind a local SOCKS, per-app routing belongs to Rawline's own settings.
func mrttRoutingCommand(raw string) (string, bool) {
	var rule struct {
		Domains       []string `json:"domains"`
		Template      bool     `json:"template_domains"`
		IPs           []string `json:"ips"`
		IsWhitelist   *bool    `json:"is_whitelist"`
		Mode          string   `json:"mode"`
		DomainsWhite  *bool    `json:"domains_is_whitelist"`
		DomainsMode   string   `json:"domains_mode"`
		IPIsWhitelist *bool    `json:"ip_is_whitelist"`
		IPMode        string   `json:"ip_mode"`
	}
	if json.Unmarshal([]byte(raw), &rule) != nil {
		return "", false
	}
	flag := func(value *bool, mode, fallback string) bool {
		if value != nil {
			return *value
		}
		if mode == "" {
			mode = fallback
		}
		return mode == "whitelist"
	}
	apps := "blacklist"
	if flag(rule.IsWhitelist, rule.Mode, "blacklist") {
		apps = "whitelist"
	}
	number := func(value bool) int {
		if value {
			return 1
		}
		return 0
	}
	clean := func(list []string) string {
		var out []string
		for _, item := range list {
			if item = strings.TrimSpace(item); item != "" && !strings.ContainsAny(item, ",;|\r\n") {
				out = append(out, item)
			}
		}
		return strings.Join(out, ",")
	}
	domains, ips := clean(rule.Domains), clean(rule.IPs)
	return fmt.Sprintf("UPDATE_DOMAINS|enabled=%d;ru=0;template=%d;whitelist=%d;domains=%s;ip_enabled=%d;ip_whitelist=%d;ips=%s",
		number(rule.Template || domains != ""), number(rule.Template), number(flag(rule.DomainsWhite, rule.DomainsMode, apps)), domains,
		number(ips != ""), number(flag(rule.IPIsWhitelist, rule.IPMode, "blacklist")), ips), true
}

func mrttFatalMessage(line string) string {
	switch {
	case strings.Contains(line, "FATAL_PROTOCOL"):
		return "FATAL_PROTOCOL: Клиент и сервер используют разные версии протокола. Обновите сервер."
	case strings.Contains(line, "неверный пароль"):
		return "FATAL_AUTH: Неверный пароль подключения"
	case strings.Contains(line, "истёк"):
		return "FATAL_AUTH: Срок действия пароля истёк"
	case strings.Contains(line, "деактивирован"):
		return "FATAL_AUTH: Аккаунт деактивирован"
	case strings.Contains(line, "устройств"):
		return "FATAL_AUTH: Достигнут лимит устройств этого конфига"
	}
	return "FATAL_AUTH: Ошибка авторизации"
}

func runMRTTBridge(path string) error {
	c, err := readMRTTConfig(path)
	if err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		return err
	}
	// One epoch per device, as the server keeps it: a launch replaces this device's older sessions.
	peerHost, _, _ := net.SplitHostPort(c.Peer)
	key := sha256.Sum256([]byte(strings.ToLower(peerHost) + "\x00" + c.Password + "\x00" + c.DeviceID))
	state := filepath.Join(cache, "Rawline", "mrtt", fmt.Sprintf("%x", key[:16]))
	if err := os.MkdirAll(state, 0700); err != nil {
		return err
	}
	generation, err := reserveMRTTGeneration(state, time.Now())
	if err != nil {
		return err
	}
	saltBytes := make([]byte, 16)
	if _, err := rand.Read(saltBytes); err != nil {
		return err
	}
	salt := hex.EncodeToString(saltBytes)
	packets, err := newMRTTPackets(cache, c)
	if err != nil {
		return err
	}
	defer packets.Close()
	command := exec.Command(filepath.Join(filepath.Dir(exe), mrttTransportName()), mrttArguments(c, generation, salt, packets.arguments())...)
	command.Dir = state
	command.Env = append(os.Environ(), "MRTT_EVENTS=1", "MRTT_PASSWORD="+c.Password, "MRTT_DEVICE_ID="+c.DeviceID, "MRTT_DEVICE_NAME="+c.DeviceName, "RAYON_NUM_THREADS=2")
	input, err := command.StdinPipe()
	if err != nil {
		return err
	}
	reader, writer := io.Pipe()
	command.Stdout, command.Stderr = writer, writer
	defer reader.Close()
	defer writer.Close()
	var inputMu sync.Mutex
	send := func(line string) { inputMu.Lock(); defer inputMu.Unlock(); _, _ = io.WriteString(input, line+"\n") }
	go func() {
		defer cancel()
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == "STOP" {
				return
			}
			// Android answers the client's captcha from its own WebView.
			if strings.HasPrefix(line, "CAPTCHA_RESULT|") {
				send(line)
			}
		}
	}()
	configs := make(chan string, 1)
	scanDone := make(chan struct{})
	go func() { defer close(scanDone); scanMRTTOutput(ctx, c, reader, configs, send, cancel) }()
	defer func() { cancel(); reader.Close(); <-scanDone }()
	if err = command.Start(); err != nil {
		return errors.New("cannot start bundled MRTT transport")
	}
	closeChildOwner, ownerErr := ownCSQTTChild(command)
	if ownerErr != nil {
		command.Process.Kill()
		command.Wait()
		return errors.New("cannot establish MRTT transport lifetime ownership")
	}
	defer closeChildOwner()
	done := make(chan struct{})
	go func() { command.Wait(); writer.Close(); close(done) }()
	defer func() {
		cancel()
		send("STOP")
		select {
		case <-done:
		// The client allows up to 9 s and 8 s for its call leave tasks: do not leave a stale call behind.
		case <-time.After(20 * time.Second):
			command.Process.Kill()
			<-done
		}
		input.Close()
	}()
	if bootstrap := mrttBootstrap(c); bootstrap != "" {
		send(bootstrap)
	}
	var config string
	timer := time.NewTimer(5 * time.Minute)
	defer timer.Stop()
	select {
	case config = <-configs:
	case <-done:
		return errors.New("MRTT transport stopped before tunnel was ready")
	case <-ctx.Done():
		return nil
	case <-timer.C:
		return errors.New("MRTT connection timed out")
	}
	address, dns, err := parseMRTTTunnel(config)
	if err != nil {
		return err
	}
	device, network, err := startRawNetstack("RAWCONF:" + address.String() + "|" + strings.Join(dns, ",") + "|" + strconv.Itoa(c.MTU))
	if err != nil {
		return err
	}
	relay := make(chan struct{})
	socket, err := packets.attach(ctx, c)
	if err != nil {
		device.Close()
		return err
	}
	go func() { packets.relay(ctx, device, socket); close(relay); cancel() }()
	defer func() { cancel(); <-relay }()
	listener, err := net.Listen("tcp4", c.SOCKS)
	if err != nil {
		return err
	}
	defer listener.Close()
	go func() {
		select {
		case <-done:
		case <-ctx.Done():
		}
		cancel()
		listener.Close()
	}()
	fmt.Println("[MRTT] SOCKS bridge ready")
	err = mrttServeSOCKS(ctx, listener, network, address, dns, c)
	if ctx.Err() != nil {
		return nil
	}
	return err
}

// nextTunnelGenerationId of the original app: never below the clock, always above the last one, across restarts too.
func reserveMRTTGeneration(state string, now time.Time) (uint64, error) {
	file := filepath.Join(state, "generation")
	var last uint64
	if data, err := os.ReadFile(file); err == nil {
		last, _ = strconv.ParseUint(strings.TrimSpace(string(data)), 10, 64)
	}
	next := uint64(max(now.Unix(), 0))
	if last != math.MaxUint64 && last+1 > next {
		next = last + 1
	}
	if err := os.WriteFile(file, []byte(strconv.FormatUint(next, 10)), 0600); err != nil {
		return 0, errors.New("cannot store the MRTT session generation")
	}
	return next, nil
}

func scanMRTTOutput(ctx context.Context, c *mrttConfig, reader io.Reader, configs chan<- string, send func(string), abort context.CancelFunc) {
	var solvers sync.WaitGroup
	defer solvers.Wait()
	pendingConfig := ""
	workerReady, configSent := false, false
	diagnostics := c.Diagnostics || os.Getenv("MRTT_DIAGNOSTICS") == "1"
	lastActive := -1
	forward := func() {
		if !workerReady || pendingConfig == "" || configSent || ctx.Err() != nil {
			return
		}
		select {
		case configs <- pendingConfig:
			configSent = true
		default:
		}
	}
	event := func(line string, kind string, target any) bool {
		prefix := mrttEventPrefix + kind + "|"
		index := strings.Index(line, prefix)
		return index >= 0 && json.Unmarshal([]byte(line[index+len(prefix):]), target) == nil
	}
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	for scanner.Scan() {
		line := scanner.Text()
		var ready struct {
			Worker *int `json:"worker"`
		}
		if event(line, "READY", &ready) && ready.Worker != nil {
			workerReady = true
			forward()
		}
		var config struct {
			Config string `json:"config"`
		}
		if event(line, "CONFIG", &config) {
			pendingConfig = config.Config
			forward()
		}
		var routing struct {
			Routing string `json:"routing"`
		}
		if event(line, "ROUTING", &routing) {
			if update, ok := mrttRoutingCommand(routing.Routing); ok {
				send(update)
			}
		}
		if diagnostics {
			var stats struct {
				Active int   `json:"active"`
				Up     int64 `json:"bytes_up"`
				Down   int64 `json:"bytes_down"`
			}
			if event(line, "STATS", &stats) && (os.Getenv("MRTT_DIAGNOSTICS") == "1" || stats.Active != lastActive) {
				lastActive = stats.Active
				fmt.Printf("[MRTT diagnostic] active=%d up=%d down=%d\n", stats.Active, stats.Up, stats.Down)
			}
			for _, kind := range []string{"READY", "ACTIVE_ZERO", "NETWORK_SUSPECT", "SERVER_RESTART", "TUN_LOST", "STOPPED"} {
				if strings.Contains(line, mrttEventPrefix+kind+"|") {
					fmt.Println("[MRTT diagnostic] " + kind)
				}
			}
		}
		if strings.Contains(line, "FATAL_AUTH") || strings.Contains(line, "AUTH_RETRY") || strings.Contains(line, "FATAL_PROTOCOL") {
			fmt.Println("[MRTT] " + mrttFatalMessage(line))
			abort()
			return
		}
		// Room and group problems are the only clue when these transports stay at zero workers.
		for _, public := range []string{"[JITSI]", "[MAX]"} {
			if i := strings.Index(line, public); i >= 0 {
				fmt.Println("[MRTT] " + line[i:])
			}
		}
		if i := strings.Index(line, "CAPTCHA_SOLVE|"); i >= 0 {
			if runtime.GOOS == "android" {
				fmt.Println(line[i:])
				continue
			}
			parts := strings.SplitN(line[i:], "|", 4)
			if len(parts) != 4 {
				continue
			}
			visible := parts[1] != "auto"
			fmt.Println("[MRTT] CAPTCHA_REQUIRED: требуется капча.")
			if visible && c.ProbeOnly && !c.Interactive {
				send("CAPTCHA_RESULT|error:cancelled")
				abort()
				continue
			}
			solvers.Add(1)
			go func(redirect string, visible bool) {
				defer solvers.Done()
				token, err := mrttSolveCaptcha(ctx, redirect, visible)
				if err != nil {
					token = "error:cancelled"
					if visible {
						abort()
					}
				}
				send("CAPTCHA_RESULT|" + token)
			}(parts[2], visible)
		}
		// Raw transport logs may contain hashes and captcha session tokens: nothing else crosses this line.
	}
}

// UiUtils.kt of MRTT: nonce (HMAC of the text) || AES-256-GCM ciphertext || tag, key = SHA-256 of the seed.
// Plain text of a link starts with "connect?", of a subscription with "{"; each has its own scheme.
func mrttSharePayload(value string, encrypt bool) (string, error) {
	invalid := errors.New("invalid MRTT link")
	if value == "" || len(value) > 1<<20 {
		return "", invalid
	}
	key := sha256.Sum256([]byte("mrtt-chiper-key"))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if encrypt {
		scheme := "mrtt://"
		if value[0] == '{' {
			scheme = "mrttsub://"
		}
		mac := hmac.New(sha256.New, key[:])
		mac.Write([]byte(value))
		nonce := mac.Sum(nil)[:gcm.NonceSize()]
		return scheme + base64.RawURLEncoding.EncodeToString(gcm.Seal(append([]byte{}, nonce...), nonce, []byte(value), nil)), nil
	}
	lower := strings.ToLower(value)
	var payload string
	switch {
	case strings.HasPrefix(lower, "mrtt://"):
		payload = value[len("mrtt://"):]
	case strings.HasPrefix(lower, "mrttsub://"):
		payload = value[len("mrttsub://"):]
	default:
		return "", invalid
	}
	payload = strings.NewReplacer("\r", "", "\n", "", " ", "").Replace(payload)
	var data []byte
	for _, encoding := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding} {
		if data, err = encoding.DecodeString(payload); err == nil {
			break
		}
	}
	if err != nil || len(data) < gcm.NonceSize()+gcm.Overhead() {
		return "", invalid
	}
	plain, err := gcm.Open(nil, data[:gcm.NonceSize()], data[gcm.NonceSize():], nil)
	if err != nil || !utf8.Valid(plain) {
		return "", invalid
	}
	return string(plain), nil
}

func mrttShare(encrypt bool) error {
	data, err := io.ReadAll(io.LimitReader(os.Stdin, 1<<20+1))
	if err != nil {
		return err
	}
	result, err := mrttSharePayload(strings.TrimSpace(string(data)), encrypt)
	if err != nil {
		return err
	}
	_, err = fmt.Print(result)
	return err
}
