// Command qthrone-cottendns runs one CottenDNS profile for qThroneMobile.
//
// It is CottenDNS's own client (cmd/client) with the configuration written
// from the app's private JSON: the client's SOCKS5 listener on loopback with
// the per-launch credentials, the profile's domain, key and method, and the
// resolvers. The client opens its listener only once the session is up.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"cottendns-go/internal/client"
	"cottendns-go/internal/config"

	"github.com/BurntSushi/toml"
)

type profile struct {
	Domain          string         `json:"domain"`
	Key             string         `json:"mkey"`
	Encryption      int            `json:"encryption"`
	Resolvers       []string       `json:"resolvers"`
	SystemResolvers []string       `json:"system_resolvers"`
	Options         map[string]any `json:"options"`
	SOCKS           string         `json:"socks"`
	SOCKSUser       string         `json:"socks_user"`
	SOCKSPass       string         `json:"socks_pass"`
}

// fallbackResolvers are used when the profile names none and the network's
// own resolvers are unknown.
var fallbackResolvers = []string{"8.8.8.8", "1.1.1.1"}

// writeConfig turns the profile into CottenDNS's TOML configuration and
// resolver file inside the private session directory.
func writeConfig(p profile, folder string) (string, string, error) {
	domains := strings.FieldsFunc(p.Domain, func(r rune) bool { return r == ',' || r == ' ' })
	if len(domains) == 0 || strings.TrimSpace(p.Key) == "" {
		return "", "", errors.New("domain and encryption key required")
	}
	if p.Encryption < 0 || p.Encryption > 5 {
		return "", "", errors.New("encryption method must be 0..5")
	}
	host, port, err := net.SplitHostPort(p.SOCKS)
	listenPort, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || listenPort < 1 || listenPort > 65535 {
		return "", "", errors.New("listener must be IPv4 loopback")
	}
	if len(p.SOCKSUser) < 1 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) < 1 || len(p.SOCKSPass) > 255 {
		return "", "", errors.New("authenticated bridge required")
	}
	cfg := map[string]any{}
	// Advanced CottenDNS keys may come with the profile; identity, listener and logging stay ours.
	for key, value := range p.Options {
		cfg[strings.ToUpper(key)] = value
	}
	for i := range domains {
		domains[i] = strings.TrimSuffix(strings.TrimSpace(domains[i]), ".")
	}
	cfg["DOMAINS"] = domains
	cfg["ENCRYPTION_KEY"] = strings.TrimSpace(p.Key)
	cfg["DATA_ENCRYPTION_METHOD"] = p.Encryption
	cfg["PROTOCOL_TYPE"] = "SOCKS5"
	cfg["LISTEN_IP"] = "127.0.0.1"
	cfg["LISTEN_PORT"] = listenPort
	cfg["SOCKS5_AUTH"] = true
	cfg["SOCKS5_USER"] = p.SOCKSUser
	cfg["SOCKS5_PASS"] = p.SOCKSPass
	cfg["LOCAL_DNS_ENABLED"] = false
	cfg["LOG_LEVEL"] = "INFO"
	var data strings.Builder
	if err := toml.NewEncoder(&data).Encode(cfg); err != nil {
		return "", "", err
	}
	configPath := filepath.Join(folder, "cottendns.toml")
	if err := os.WriteFile(configPath, []byte(data.String()), 0o600); err != nil {
		return "", "", err
	}

	resolvers := p.Resolvers
	if len(clean(resolvers)) == 0 {
		resolvers = p.SystemResolvers
	}
	if len(clean(resolvers)) == 0 {
		resolvers = fallbackResolvers
	}
	resolversPath := filepath.Join(folder, "cottendns_resolvers.txt")
	if err := os.WriteFile(resolversPath, []byte(strings.Join(clean(resolvers), "\n")+"\n"), 0o600); err != nil {
		return "", "", err
	}
	return configPath, resolversPath, nil
}

// clean keeps the UDP resolver forms CottenDNS reads (IP, IP:port, CIDR).
func clean(list []string) []string {
	var out []string
	for _, r := range list {
		r = strings.TrimPrefix(strings.TrimSpace(r), "udp://")
		if r != "" && !strings.Contains(r, "://") {
			out = append(out, r)
		}
	}
	return out
}

func run() int {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		fmt.Fprintln(os.Stderr, "CottenDNS: expected private configuration file")
		return 2
	}
	data, err := os.ReadFile(os.Args[2])
	var p profile
	if err != nil || len(data) > 1<<20 || json.Unmarshal(data, &p) != nil {
		fmt.Fprintln(os.Stderr, "CottenDNS: invalid configuration")
		return 2
	}
	folder, _ := filepath.Abs(filepath.Dir(os.Args[2]))
	configPath, resolversPath, err := writeConfig(p, folder)
	if err != nil {
		fmt.Fprintf(os.Stderr, "CottenDNS: %v\n", err)
		return 2
	}
	app, err := client.Bootstrap(configPath, config.ClientConfigOverrides{ResolversFilePath: &resolversPath})
	if err != nil {
		fmt.Fprintf(os.Stderr, "CottenDNS: %v\n", err)
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 4096))
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		stop()
	}()
	if err := app.Run(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "CottenDNS: %v\n", err)
		return 1
	}
	return 0
}

func main() { os.Exit(run()) }
